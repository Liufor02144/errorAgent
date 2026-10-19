package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.service.analyze.Redactor;
import org.junit.Assert;
import org.junit.Test;

public class RedactorTest {

    @Test
    public void 手机号身份证邮箱要抹掉() {
        String out = Redactor.redact("用户 13812345678 身份证 11010519491231002X 邮箱 alice@example.com 下单失败");
        Assert.assertFalse(out, out.contains("13812345678"));
        Assert.assertFalse(out, out.contains("11010519491231002X"));
        Assert.assertFalse(out, out.contains("alice@example.com"));
        Assert.assertTrue(out, out.contains("{PHONE}") && out.contains("{ID_CARD}") && out.contains("{EMAIL}"));
    }

    @Test
    public void 长流水号中间的片段不算手机号() {
        String id = "202610191388888888812345";
        Assert.assertEquals("流水号 " + id, Redactor.redact("流水号 " + id));
    }

    @Test
    public void 常见密钥格式要抹掉() {
        // 分段组装，避免仓库里出现看起来像真实凭据的完整字符串。
        String bearer = "abcdefghijkl" + "mnopqrstuvwxyz";
        String aliyunKey = "LT" + "AI5tAbCdEfGhIjKlMnOp";
        String openAiKey = "sk-" + "abcdefghijklmnopqrstuv";
        String out = Redactor.redact("password=hunter22 token: test "
                + "Authorization: Bearer " + bearer + " "
                + "ak=" + aliyunKey + " key " + openAiKey + " "
                + "jdbc://root:pa55word@db.example.com:3306/app");
        Assert.assertFalse(out, out.contains("hunter22"));
        Assert.assertFalse(out, out.contains("token: test"));
        Assert.assertFalse(out, out.contains(bearer));
        Assert.assertFalse(out, out.contains(aliyunKey));
        Assert.assertFalse(out, out.contains(openAiKey));
        Assert.assertFalse(out, out.contains("pa55word"));
    }

    @Test
    public void 堆栈和普通文本保持原样() {
        String raw = "java.lang.IllegalStateException: 订单状态不允许取消\n"
                + "\tat com.example.order.OrderService.cancel(OrderService.java:57)";
        Assert.assertEquals(raw, Redactor.redact(raw));
        Assert.assertNull(Redactor.redact(null));
    }
}
