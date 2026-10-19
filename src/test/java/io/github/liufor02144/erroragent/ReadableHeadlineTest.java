package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.service.push.InspectCardBuilder;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 卡片标题必须是人能读的一句话，不是模版原文。
 *
 * <p><b>模版是给机器匹配用的</b>，里面全是占位符和分隔符。直接拿去当标题：
 * <pre>
 * ErrorScene^_^{NUM}^_^aop.ACQ.SYSTEM_ERROR^_^{NUM}.{NUM}^_^Linux^_^{TIME}^_^Proto
 * </pre>
 * 一眼看过去像程序报错乱码了 —— 而这张卡是推到群里、给人扫一眼做判断的。
 *
 * <p>清理后应当是「ErrorScene · aop.ACQ.SYSTEM_ERROR · Linux」：
 * 分隔符换成中点，纯占位符的段整段丢掉，末尾截断的残词去掉。
 */
public class ReadableHeadlineTest {

    private final InspectCardBuilder builder = new InspectCardBuilder();

    private String readable(String headline) {
        return (String) ReflectionTestUtils.invokeMethod(builder, "readable", headline);
    }

    @Test
    public void 支付宝ErrorScene要能读() {
        String raw = "ErrorScene^_^{NUM}^_^aop.ACQ.SYSTEM_ERROR^_^{NUM}.{NUM}"
                + "^_^Linux^_^{TIME}^_^Proto";
        String out = readable(raw);

        Assert.assertFalse("机器分隔符必须去掉：" + out, out.contains("^_^"));
        Assert.assertTrue("真正有信息量的错误码要留着：" + out,
                out.contains("aop.ACQ.SYSTEM_ERROR"));
        Assert.assertFalse("纯占位符的段要丢掉：" + out, out.contains("{NUM}"));
        Assert.assertFalse("时间占位符也要丢：" + out, out.contains("{TIME}"));
    }

    @Test
    public void 句中的占位符保留其语序作用() {
        // 「订单 {订单ID} 同步失败」里的占位符是有意义的，去掉会读不通
        String out = readable("订单 {订单ID} 同步渠道失败");
        Assert.assertTrue("句子结构要保住：" + out, out.contains("同步渠道失败"));
        Assert.assertTrue("句中占位符保留：" + out, out.contains("{订单ID}"));
    }

    @Test
    public void 竖线分隔也当分隔符处理() {
        String out = readable("CACHE_METRIC_ABNORMAL|app={应用}|{指标详情}");
        Assert.assertFalse("竖线要换成中点：" + out, out.contains("|"));
        Assert.assertTrue("指标名要留着：" + out, out.contains("CACHE_METRIC_ABNORMAL"));
    }

    @Test
    public void 普通文案原样保留() {
        // 本来就是人话的，不该被动过
        String raw = "业务异常 BusinessException: 商品已下架";
        Assert.assertEquals(raw, readable(raw));
    }

    @Test
    public void 空值给兜底文案() {
        Assert.assertEquals("无可用描述", readable(null));
        Assert.assertEquals("无可用描述", readable("   "));
    }

    @Test
    public void 全是占位符时不能清成空串() {
        // 清完什么都不剩的话，标题会变成空白，比乱码更糟
        String out = readable("{NUM}^_^{TIME}^_^{NUM}");
        Assert.assertNotNull(out);
        Assert.assertFalse("不能清成空：" + out, out.trim().isEmpty());
    }
}
