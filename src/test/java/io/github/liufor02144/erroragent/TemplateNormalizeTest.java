package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.service.template.TemplateFallbackService;
import org.junit.Assert;
import org.junit.Test;

/**
 * 模版是<b>故障类型</b>，具体标识是<b>参数</b>。
 *
 * <p>反面例子（真实踩过）：模版写成
 * {@code Field 'tenant_id' doesn't have a default value}，
 * 那么下次换成 {@code user_id} 就要再建一条。同一个毛病散成几十条模版，
 * 「这类问题一共出了多少次」也就数不出来了。
 * 具体是哪个字段，在详情页的原始样例里看得到。
 *
 * <p>但有一条反向红线：<b>引号里的中文不能动。</b>
 * {@code BusinessException{code='500', desc='商品已下架'}} 里的「商品已下架」
 * 正是区分度本身，归一掉就回到了「doRefund 模版吞掉真正的退款故障」那个错误 ——
 * 业务拒绝和系统故障会被压成同一条，一旦放行，真故障永久静默。
 *
 * <p>判据：<b>ASCII 标识符是参数，人话是身份。</b>
 */
public class TemplateNormalizeTest {

    private static String norm(String s) {
        return TemplateFallbackService.normalizeValues(s);
    }

    @Test
    public void 字段名要变成占位符() {
        // 这条就是用户指出来的：不该给每个字段建一条模版
        Assert.assertEquals("Field '{标识}' doesn't have a default value",
                norm("Field 'tenant_id' doesn't have a default value"));
        Assert.assertEquals("换个字段必须落到同一条模版",
                norm("Field 'tenant_id' doesn't have a default value"),
                norm("Field 'user_id' doesn't have a default value"));
    }

    @Test
    public void 索引名也是参数() {
        String a = norm("Duplicate entry '900000000000000000000001-tx-4' for key 'idx_mult'");
        String b = norm("Duplicate entry '900000000000000000000002-tx-9' for key 'uk_order'");
        Assert.assertEquals("同为唯一键冲突，应落到同一条模版", a, b);
        Assert.assertTrue("要保留「Duplicate entry / for key」这个故障特征：" + a,
                a.contains("Duplicate entry") && a.contains("for key"));
        Assert.assertFalse("具体主键值不该留在模版里：" + a, a.contains("900000"));
    }

    @Test
    public void 引号里的中文绝不能归一() {
        // 红线：归一掉「商品已下架」，它就和真故障共用一条模版了
        String s = norm("BusinessException{code='500', desc='商品已下架'}");
        Assert.assertTrue("中文业务原因必须原样保留：" + s, s.contains("商品已下架"));
        Assert.assertNotEquals("两种业务原因不能被压成同一条",
                norm("BusinessException{code='500', desc='商品已下架'}"),
                norm("BusinessException{code='500', desc='券码不存在'}"));
    }

    @Test
    public void 带空格的人话不当成标识() {
        // 'not found' 是人话，归一掉会丢掉区分度
        String s = norm("Status='not found'");
        Assert.assertTrue("带空格的英文短语要保留：" + s, s.contains("not found"));
    }

    @Test
    public void 长数字与时间戳归一() {
        Assert.assertEquals("orderId:{NUM}", norm("orderId:900000000000000000000003"));
        Assert.assertTrue(norm("createTime=Tue Feb 03 16:11:31 CST 2026").contains("{TIME}"));
    }

    @Test
    public void 对象toString里的数字键值归一() {
        String s = norm("SkuAttrMappingRule(id=1234567, type=2, status=1)");
        Assert.assertEquals("SkuAttrMappingRule(id={NUM}, type={NUM}, status={NUM})", s);
        Assert.assertTrue("实体类名是故障特征，要留着：" + s,
                s.contains("SkuAttrMappingRule"));
    }

    @Test
    public void 错误码归一但故障文案保留() {
        String s = norm("OpenApiException{code='703', msg='签名验证错误'}");
        Assert.assertTrue("错误码是参数：" + s, s.contains("'{NUM}'"));
        Assert.assertTrue("故障文案是身份：" + s, s.contains("签名验证错误"));
    }

    @Test
    public void 短ID与IP也是参数() {
        // channelId:40 留着字面量的话，每个渠道都会生成一条模版
        Assert.assertEquals("NotifyChannelServiceImpl no notifyChannel channelId:{NUM}",
                norm("NotifyChannelServiceImpl no notifyChannel channelId:40"));
        Assert.assertTrue(norm("\"requestIp\":\"203.0.113.25\"").contains("{IP}"));
    }

    @Test
    public void 归一是幂等的() {
        // 同一条文本反复归一（重建模版时会发生）不该越变越短
        String once = norm("Field 'tenant_id' doesn't have a default value");
        Assert.assertEquals(once, norm(once));
    }

    @Test
    public void 空输入不崩() {
        Assert.assertNull(norm(null));
        Assert.assertEquals("", norm("   "));
    }
}
