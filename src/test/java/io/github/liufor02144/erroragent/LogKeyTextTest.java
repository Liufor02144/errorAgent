package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.service.template.LogKeyText;
import io.github.liufor02144.erroragent.service.template.TemplateExtractor;
import org.junit.Assert;
import org.junit.Test;

import java.util.regex.Pattern;

/**
 * 异常身份提取的验证。
 *
 * <p>要挡住的回归：<b>只取首行会把不同原因压成同一个模版</b>。
 * {@code doRefund Exception,orderId:123} 这一行只说明「在退款」，
 * 而「商户未开通微信支付」（业务拒绝）和「第三方接口超时」（故障）
 * 都在第二行。压成一个模版并标记为业务预期后，真正的退款失败就再也不报了 ——
 * 那意味着用户收不到钱。
 */
public class LogKeyTextTest {

    /** 业务拒绝：商户没开通支付 */
    private static final String REFUND_NOT_OPENED =
            "doRefund Exception,orderId:9000000000001\n"
                    + "java.lang.RuntimeException: 商户未开通微信支付，请联系商家\n"
                    + "\tat com.example.payment.PayService.doRefund(PayService.java:220)\n"
                    + "\tat com.example.payment.PayController.refund(PayController.java:88)";

    /** 同一行代码、完全不同的性质：第三方故障 */
    private static final String REFUND_TIMEOUT =
            "doRefund Exception,orderId:9000000000002\n"
                    + "java.net.SocketTimeoutException: Read timed out\n"
                    + "\tat com.example.payment.PayService.doRefund(PayService.java:220)";

    @Test
    public void 异常原因必须进入身份而不是只取首行() {
        String key = LogKeyText.of(REFUND_NOT_OPENED);

        Assert.assertTrue("首行要保留", key.contains("doRefund Exception"));
        Assert.assertTrue("异常原因才是关键，必须带上", key.contains("商户未开通微信支付"));
        Assert.assertFalse("堆栈帧没有语义，不该进身份", key.contains("at com.example"));
    }

    @Test
    public void 同一行代码的不同原因得到不同身份() {
        String a = LogKeyText.of(REFUND_NOT_OPENED);
        String b = LogKeyText.of(REFUND_TIMEOUT);

        Assert.assertNotEquals("业务拒绝和第三方超时不能是同一个身份", a, b);
        Assert.assertTrue(b.contains("SocketTimeoutException"));
    }

    @Test
    public void 业务拒绝的模版不会吞掉真正的故障() {
        // 这是整件事的要害：把「商户未开通」标成业务预期之后，
        // 「第三方超时」必须仍然匹配不上它，否则退款故障会被永久静默
        TemplateExtractor extractor = new TemplateExtractor();
        String pattern = extractor.patternFromTemplate(
                "doRefund Exception,orderId:{订单ID} java.lang.RuntimeException: 商户未开通微信支付，请联系商家");
        Pattern compiled = Pattern.compile(pattern);

        Assert.assertTrue("原样例要命中",
                compiled.matcher(LogKeyText.of(REFUND_NOT_OPENED)).find());
        Assert.assertFalse("第三方超时绝不能命中这个业务预期模版",
                compiled.matcher(LogKeyText.of(REFUND_TIMEOUT)).find());
    }

    @Test
    public void 拼成单行以便正则通配能跨越两段() {
        // Java 正则的 . 默认不匹配换行，身份若保留换行，
        // 「首行…{占位符}…异常行」这种模版永远匹配不上
        String key = LogKeyText.of(REFUND_NOT_OPENED);
        Assert.assertFalse("身份必须是单行", key.contains("\n"));
    }

    @Test
    public void 没有异常行时退回首行() {
        String key = LogKeyText.of("校验租户绑定状态失败 tenantDetail:TenantDetailResp(longitude=null)");
        Assert.assertTrue(key.startsWith("校验租户绑定状态失败"));
    }

    @Test
    public void 首行为空时顺延到第二行() {
        // 微信支付 SDK 的日志首行只有一个空格，内容在第二行
        String key = LogKeyText.of(" \n结果业务代码异常，返回结果：{nonce_str=Ab12Cd34}");
        Assert.assertTrue(key.contains("结果业务代码异常"));
    }

    @Test
    public void 前导符号归一() {
        // 采集侧会给部分行加 "- "，不归一会抽出两个只差前缀的模版
        Assert.assertEquals(
                LogKeyText.of("CACHE_METRIC_ABNORMAL|app=xxx"),
                LogKeyText.of("- CACHE_METRIC_ABNORMAL|app=xxx"));
    }

    @Test
    public void 首行本身就是异常行时不重复拼接() {
        String key = LogKeyText.of(
                "com.example.common.BusinessException: 商品已下架\n\tat com.example.Foo.bar(Foo.java:1)");
        Assert.assertEquals("商品已下架只应出现一次",
                key.indexOf("商品已下架"), key.lastIndexOf("商品已下架"));
    }

    @Test
    public void 空输入不抛异常() {
        Assert.assertEquals("", LogKeyText.of(null));
        Assert.assertEquals("", LogKeyText.of("   "));
    }
}
