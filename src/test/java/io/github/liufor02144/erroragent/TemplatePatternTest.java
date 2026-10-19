package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.service.template.TemplateExtractor;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.regex.Pattern;

/**
 * 由模版生成匹配正则的验证。
 *
 * <p>为什么不用模型给的正则：实测它会把模版里<b>已经抹成占位符的值又写回正则</b> ——
 * 模版是 {@code BusinessException&#123;code='&#123;错误码&#125;'&#125;}，
 * 正则却是 {@code BusinessException\&#123;code='500'\&#125;}，
 * 于是这个模版只能匹配 code=500 那一条，等于废掉。
 * 而「能否匹配自身样例」这条校验恰好放过这种写法。
 */
public class TemplatePatternTest {

    private TemplateExtractor extractor;

    @Before
    public void setUp() {
        extractor = new TemplateExtractor();
    }

    @Test
    public void 占位符变成通配而不是写死具体值() {
        // 这是实测翻车的那条：模型给的正则写死了 code='500'
        String p = extractor.patternFromTemplate(
                "业务异常 BusinessException{code='{错误码}', desc='{错误描述}'}");

        Pattern compiled = Pattern.compile(p);
        Assert.assertTrue("原样例要能匹配",
                compiled.matcher("业务异常 BusinessException{code='500', desc='系统繁忙，请稍后再试'}").find());
        Assert.assertTrue("换个错误码同样要能匹配 —— 这正是模型给的正则做不到的",
                compiled.matcher("业务异常 BusinessException{code='403', desc='无权限'}").find());
    }

    @Test
    public void 状态值变化也要能匹配() {
        String p = extractor.patternFromTemplate(
                "dealPayResult {订单状态} status,transactionId:{交易ID}");
        Pattern compiled = Pattern.compile(p);

        Assert.assertTrue(compiled.matcher("dealPayResult UN_KNOWN status,transactionId:1234567890").find());
        Assert.assertTrue("换个状态值仍要命中",
                compiled.matcher("dealPayResult FAILED status,transactionId:99887766").find());
    }

    @Test
    public void 中文变量能被通配吃掉() {
        // 商品名这类中文变量正是正则枚举治不了的
        String p = extractor.patternFromTemplate("组合商品下{商品名}未同步到渠道商城，请同步成功后再尝试");
        Pattern compiled = Pattern.compile(p);

        Assert.assertTrue(compiled.matcher("组合商品下“夏季限定”冰美式（大杯）未同步到渠道商城，请同步成功后再尝试").find());
        Assert.assertTrue(compiled.matcher("组合商品下“新品”燕麦拿铁未同步到渠道商城，请同步成功后再尝试").find());
    }

    @Test
    public void 正则元字符被转义而不是当语法() {
        // 模版里的 ( ) [ ] . | 都是字面量，不转义会改变匹配语义甚至编译失败
        String p = extractor.patternFromTemplate("CACHE_METRIC_ABNORMAL|{缓存指标详情}");
        Pattern compiled = Pattern.compile(p);

        Assert.assertTrue(compiled.matcher("CACHE_METRIC_ABNORMAL|app=xxx|hitRate=0.01").find());
        // 若 | 被当成「或」，这条不含该前缀的也会误命中
        Assert.assertFalse("竖线必须是字面量", compiled.matcher("完全无关的日志").find());
    }

    @Test
    public void 首尾通配被去掉() {
        // 「{商品名}未同步」前面那段通配没有任何约束力，
        // 留着只会拖慢匹配，还虚增「固定文字长度」这个度量
        String p = extractor.patternFromTemplate("{商品名}未同步到渠道商城");
        Assert.assertFalse("首部通配应被去掉", p.startsWith(".{0,300}"));
        Assert.assertTrue(Pattern.compile(p).matcher("某商品未同步到渠道商城").find());
    }

    @Test
    public void 整句都是占位符时拒绝生成() {
        // 这种模版没有任何固定语义，生成出来会匹配一切
        Assert.assertNull(extractor.patternFromTemplate("{全部内容}"));
    }

    @Test
    public void 模型把半角标点写成全角时仍能匹配原文() {
        // 实测翻车：原文是 "内容超过4000bytes, device:"（半角逗号），
        // 模型抽出的模版却是 "内容超过{大小}bytes，device:"（全角逗号），
        // 于是正则匹配不上原文，整个模版作废。模型的标点习惯约束不住，只能在匹配侧放宽
        String p = extractor.patternFromTemplate(
                "sendPushMessage内容超过{大小}bytes，device:{设备信息}");
        Pattern compiled = Pattern.compile(p);

        Assert.assertTrue("原文是半角逗号，同样要命中",
                compiled.matcher("sendPushMessage内容超过4000bytes, device:{\"a\":1}").find());
        Assert.assertTrue("全角逗号的写法也要命中",
                compiled.matcher("sendPushMessage内容超过8000bytes，device:xxx").find());
    }

    @Test
    public void 空格数量不影响匹配() {
        // 「失败 tenantDetail」被模型写成「失败，tenantDetail」也是同一类问题
        String p = extractor.patternFromTemplate("校验租户绑定状态失败 tenantDetail:{租户详情}");
        Pattern compiled = Pattern.compile(p);

        Assert.assertTrue(compiled.matcher("校验租户绑定状态失败 tenantDetail:TenantDetailResp(x=1)").find());
        Assert.assertTrue("多个空格",
                compiled.matcher("校验租户绑定状态失败   tenantDetail:TenantDetailResp(x=1)").find());
        Assert.assertTrue("没有空格",
                compiled.matcher("校验租户绑定状态失败tenantDetail:TenantDetailResp(x=1)").find());
    }

    @Test
    public void 同一模版不因前导符号裂成两个() {
        // 采集侧会给部分行加 "- " 前缀，同一条日志出现两种形态。
        // 实测抽出了「- CACHE_METRIC_ABNORMAL|{指标详情}」和
        // 「CACHE_METRIC_ABNORMAL|{指标详情}」两个模版，只差一个前缀
        String withDash = extractor.patternFromTemplate("CACHE_METRIC_ABNORMAL|{指标详情}");
        Pattern compiled = Pattern.compile(withDash);

        Assert.assertTrue("不带前缀的原文",
                compiled.matcher("CACHE_METRIC_ABNORMAL|app=xxx|hitRate=0.01").find());
        Assert.assertTrue("带前缀的原文同样要命中同一个模版",
                compiled.matcher("- CACHE_METRIC_ABNORMAL|app=xxx|hitRate=0.01").find());
    }

    @Test
    public void 占位符要装得下长JSON() {
        // 实测翻车：refreshCouponCodeByPlatform 的请求参数是几百字符的 JSON，
        // 占位符通配只有 .{0,80} 时装不下，这个模版永远命中不了 ——
        // 表现是「明明建了模版，采集时却一直未匹配」
        String p = extractor.patternFromTemplate(
                "refreshCouponCodeByPlatform fail code :{请求参数} {异常类}: 调用开放平台Api失败");
        Pattern compiled = Pattern.compile(p);

        StringBuilder json = new StringBuilder("{\"autoCreate\":1,\"tenantId\":1001");
        for (int i = 0; i < 12; i++) {
            json.append(",\"field").append(i).append("\":\"someLongValue").append(i).append("\"");
        }
        json.append("}");

        String real = "refreshCouponCodeByPlatform fail code :" + json
                + " com.example.common.exception.BusinessException: "
                + "调用开放平台Api失败:customer limit exhausted";
        Assert.assertTrue("请求参数长达 " + json.length() + " 字符，仍要能命中",
                compiled.matcher(real).find());
    }

    @Test
    public void 通配不跨行以免吞掉别的日志() {
        // 放宽通配长度的安全前提：'.' 不匹配换行，
        // 所以即使 300 字符也不会跨过行边界把下一条日志的内容吃进来
        String p = extractor.patternFromTemplate("前缀标识{变量}后缀标识");
        Pattern compiled = Pattern.compile(p);

        Assert.assertTrue(compiled.matcher("前缀标识某个值后缀标识").find());
        Assert.assertFalse("跨行不该匹配",
                compiled.matcher("前缀标识某个值\n后缀标识").find());
    }

    @Test
    public void 空模版不抛异常() {
        Assert.assertNull(extractor.patternFromTemplate(null));
        Assert.assertNull(extractor.patternFromTemplate("   "));
    }

    /**
     * 模版省掉了异常类的包名，也要能匹配带包名的原文。
     *
     * <p>实测两条都被判「匹配不上自身样例」丢弃：模型写的是 {@code BusinessException}，
     * 原文是 {@code partner.openapi.sdk.api.exception.BusinessException}。
     * 抽模版经常「产出 3 个、通过 0 个」就是这个原因。
     */
    @Test
    public void 模版省略包名也能匹配全限定类名() {
        String sdk = " 渠道orderService.setOrderPrepared异常\n"
                + "partner.openapi.sdk.api.exception.BusinessException: Directory of type RegistryDirectory already destroyed\n"
                + "\tat partner.openapi.sdk.utils.WebUtils.toException(WebUtils.java:390)";
        String p1 = extractor.patternFromTemplate("渠道orderService.setOrderPrepared异常 BusinessException: {错误详情}");
        assertMatchesBoth(p1, sdk);

        String lark = "CRITICAL 【飞书告警】发送失败，message:{\"tenantId\":1001}\n"
                + "com.example.common.exception.GlobalException: 不识别的物流异常类型：配送商取消配送\n"
                + "\tat com.example.delivery.listener.DeliveryWarnListener.parseText(DeliveryWarnListener.java:299)";
        String p2 = extractor.patternFromTemplate(
                "【飞书告警】发送失败，message:{告警消息} GlobalException: 不识别的物流异常类型：{类型}");
        assertMatchesBoth(p2, lark);
    }

    @Test
    public void 放宽包名不能放宽类名本身() {
        // 只允许前面多出包名，类名必须完全一致 —— 否则不同异常会被吞进同一个模版
        String p = extractor.patternFromTemplate("下单失败 BusinessException: {原因}");
        Assert.assertFalse(java.util.regex.Pattern.compile(p)
                .matcher("下单失败 com.x.MyBusinessException: 库存不足").find());
        Assert.assertFalse(java.util.regex.Pattern.compile(p)
                .matcher("下单失败 com.x.GlobalException: 库存不足").find());
        Assert.assertTrue(java.util.regex.Pattern.compile(p)
                .matcher("下单失败 BusinessException: 库存不足").find());
    }

    /** 抽取时校验用原始样例，线上匹配用「首行 + 异常行」，两种都要对得上 */
    private void assertMatchesBoth(String pattern, String raw) {
        Assert.assertNotNull(pattern);
        java.util.regex.Pattern c = java.util.regex.Pattern.compile(pattern);
        Assert.assertTrue("原始样例匹配不上：" + pattern, c.matcher(raw).find());
        String key = io.github.liufor02144.erroragent.service.template.LogKeyText.of(raw);
        Assert.assertTrue("首行 + 异常行匹配不上：" + key + " | " + pattern, c.matcher(key).find());
    }
}
