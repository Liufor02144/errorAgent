package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.service.template.TemplateExtractor;
import io.github.liufor02144.erroragent.service.template.TemplateFallbackService;
import org.junit.Assert;
import org.junit.Test;

/**
 * 模版里不能留请求流水号：同一个「开放平台删除成员失败」按 rid 裂出了 4 个模版，
 * 每个只能匹配它自己，命中数全是 0。
 */
public class VolatileIdTemplateTest {

    private boolean hasId(String template) {
        return TemplateExtractor.VOLATILE_ID.matcher(template).find();
    }

    @Test
    public void 带流水号的模版要拒绝() {
        Assert.assertTrue(hasId("开放平台删除成员失败，请求信息： BusinessException: system error rid: 1a2b3c4d-5e6f7a8b-9c0d1e2f"));
        Assert.assertTrue(hasId("开放平台删除成员失败，请求信息： BusinessException: system error rid: 1a2b3c4d-9e8"));
        Assert.assertTrue(hasId("调用失败 requestId=550e8400-e29b-41d4-a716-446655440000"));
        Assert.assertTrue(hasId("签名校验失败 sign=9f86d081884c7d659a2feaa0c55ad015"));
    }

    @Test
    public void 正常模版不能误伤() {
        Assert.assertFalse(hasId("开放平台删除成员失败，请求信息：{参数} BusinessException: system error rid:{rid}"));
        Assert.assertFalse(hasId("Duplicate entry {键值} for key 'idx_tenant_user'"));
        Assert.assertFalse(hasId("商品已售罄（601）"));
        Assert.assertFalse(hasId("商品{ID}已下架 deadbeef-cafe"));
        Assert.assertFalse(hasId("调用开放平台Api失败:customer limit exhausted"));
    }

    @Test
    public void 兜底模版也要抹掉流水号和appId() {
        Assert.assertEquals("BusinessException: system error rid: {ID}",
                TemplateFallbackService.normalizeValues("BusinessException: system error rid: 1a2b3c4d-5e6f7a8b-9c0d1e2f"));
        Assert.assertEquals("微信发布代码失败，请求信息：{appId}，微信响应:",
                TemplateFallbackService.normalizeValues("微信发布代码失败，请求信息：wx1234567890abcdef，微信响应:"));
    }
}
