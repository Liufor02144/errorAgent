package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.entity.InspectSample;
import io.github.liufor02144.erroragent.service.analyze.InspectAnalyzeService;
import io.github.liufor02144.erroragent.service.collect.InspectSlsRepository;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 实例（payment-service 调支付渠道取消订单）：
 * 卡片对应的报错只是外层包装「订单取消失败，请稍后再试」，支付渠道真正的拒绝原因
 * 「该支付方式不支持关闭订单！」「订单当前状态不允许撤销」「该笔订单未到账」打在同一次请求的另外几条日志里。
 */
public class SiblingErrorsTest {

    private static final String TID = "TID:1700000000.1234.17000000000000001";

    private Map<String, String> log(String message) {
        Map<String, String> m = new HashMap<>();
        m.put("app_id", "payment-service");
        m.put("message", message);
        return m;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void 同一次请求里的拒绝原因要交给AI() {
        String own = " PAY cancelAndRefund Exception Do Refund,transactionId:20260101120000000000000000000042\n"
                + "com.example.common.exception.BusinessException: 订单取消失败，请稍后再试\n"
                + "\tat com.example.payment.channel.PayChannelClient.cancelAndRefund(PayChannelClient.java:381)";
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(log(" PAY cancel Exception Do cancelAndRefund,transactionId:20260101120000000000000000000042\n"
                + "com.example.common.exception.BusinessException: 该支付方式不支持关闭订单！\n"
                + "\tat com.example.payment.channel.PayChannelClient.cancel(PayChannelClient.java:337)"));
        rows.add(log(" cancel FAIL,transactionId:20260101120000000000000000000042,resp:{\"return_code\":\"02\",\"return_msg\":\"该支付方式不支持关闭订单！\"}"));
        rows.add(log(" PAY refund Exception,transactionId:20260101120000000000000000000042\n"
                + "com.example.common.exception.BusinessException: 该笔订单未到账,请确认支付成功后再重试退款操作\n"
                + "\tat com.example.payment.channel.PayChannelClient.refund(PayChannelClient.java:314)"));
        rows.add(log(" PAY cancelAndRefund Exception,transactionId:20260101120000000000000000000042\n"
                + "com.example.common.exception.BusinessException: 订单当前状态不允许撤销\n"
                + "\tat com.example.payment.channel.PayChannelClient.cancelAndRefund(PayChannelClient.java:378)"));
        rows.add(log(own));

        InspectSlsRepository sls = Mockito.mock(InspectSlsRepository.class);
        Mockito.when(sls.queryErrorsByTrace(ArgumentMatchers.eq(TID), ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyInt())).thenReturn(rows);
        InspectAnalyzeService service = new InspectAnalyzeService();
        ReflectionTestUtils.setField(service, "slsRepository", sls);

        InspectSample sample = new InspectSample();
        sample.setTraceId(TID);
        sample.setLogTime(new Date());
        sample.setRawMessage(own);

        List<String> siblings = (List<String>) ReflectionTestUtils.invokeMethod(service, "siblingErrors", sample);

        String all = String.join("\n", siblings);
        System.out.println(all);
        Assert.assertTrue(all, all.contains("该支付方式不支持关闭订单"));
        Assert.assertTrue(all, all.contains("订单当前状态不允许撤销"));
        Assert.assertTrue(all, all.contains("该笔订单未到账"));
        Assert.assertFalse("本条自己不重复列出", all.contains("订单取消失败"));
    }
}
