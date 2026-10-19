package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.service.analyze.LlmClient;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * OpenAI 兼容端点地址归一化。
 *
 * <p>实测踩过的坑：配置里 base-url 只填到域名（{@code https://api.moonshot.cn}），
 * 而协议路径是 {@code {baseUrl}/chat/completions}，缺 /v1 直接 404，
 * 且服务端只回「没找到对象」，很难联想到是配置少了一段。
 */
public class LlmBaseUrlTest {

    private String normalize(String url) {
        return ReflectionTestUtils.invokeMethod(new LlmClient(), "normalizeBaseUrl", url);
    }

    @Test
    public void 缺少版本段时应自动补v1() {
        Assert.assertEquals("https://api.moonshot.cn/v1", normalize("https://api.moonshot.cn"));
        Assert.assertEquals("https://api.moonshot.cn/v1", normalize("https://api.moonshot.cn/"));
        Assert.assertEquals("https://api.moonshot.cn/v1", normalize("  https://api.moonshot.cn//  "));
    }

    @Test
    public void 已有版本段时应保持不变() {
        Assert.assertEquals("https://api.moonshot.cn/v1", normalize("https://api.moonshot.cn/v1"));
        Assert.assertEquals("https://api.moonshot.cn/v1", normalize("https://api.moonshot.cn/v1/"));
        // 未来可能出现 v2、v3，不应被再拼一层
        Assert.assertEquals("http://llm-gateway.internal.example.com/v2", normalize("http://llm-gateway.internal.example.com/v2"));
    }

    @Test
    public void 内部网关与其他兼容端点同样适用() {
        Assert.assertEquals("http://llm-gateway.internal.example.com/v1", normalize("http://llm-gateway.internal.example.com"));
        Assert.assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1",
                normalize("https://dashscope.aliyuncs.com/compatible-mode/v1"));
    }

    @Test
    public void 空值不应抛异常() {
        Assert.assertNull(normalize(null));
    }
}
