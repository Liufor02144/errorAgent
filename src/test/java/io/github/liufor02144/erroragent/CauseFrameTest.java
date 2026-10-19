package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.dto.StackFrame;
import io.github.liufor02144.erroragent.service.code.StackFrameParser;
import org.junit.Assert;
import org.junit.Test;

/**
 * 实例：渠道服务调用第三方优惠券接口，对方返回 502。
 * 代码定位到了公共包的 LogFilter（外层 NestedServletException 栈里第一个业务包的帧），
 * 真正的调用路径在 Caused by 里。
 */
public class CauseFrameTest {

    private static final String RAW = "Uncaught exception thrown\n"
            + "org.springframework.web.util.NestedServletException: Request processing failed; "
            + "nested exception is org.springframework.web.client.HttpServerErrorException: 502 Bad Gateway\n"
            + "\tat org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:989)\n"
            + "\tat com.example.common.web.LogFilter.doFilter(LogFilter.java:41)\n"
            + "\tat com.example.common.web.TraceHeaderFilter.doFilter(TraceHeaderFilter.java:69)\n"
            + "\tat java.lang.Thread.run(Thread.java:823)\n"
            + "Caused by: org.springframework.web.client.HttpServerErrorException: 502 Bad Gateway\n"
            + "\tat org.springframework.web.client.RestTemplate.exchange(RestTemplate.java:564)\n"
            + "\tat com.example.common.client.CouponApiClient.sendJsonByPost(CouponApiClient.java:95)\n"
            + "\tat com.example.common.client.CouponApiClient.postSend(CouponApiClient.java:55)\n"
            + "\tat com.example.coupon.service.CouponRedeemService.redeem$original$Tj8WPsOi(CouponRedeemService.java:272)\n"
            + "\tat com.example.coupon.controller.CouponController.redeem$original$wkiLGdM4(CouponController.java:51)\n"
            + "\t... 76 common frames omitted";

    @Test
    public void 代码定位取最深一层Caused_by里的业务帧() {
        StackFrame f = StackFrameParser.forPackages("com.example").firstBizFrame(RAW);
        Assert.assertEquals("com.example.common.client.CouponApiClient", f.getClassName());
        Assert.assertEquals(95, f.getLineNumber());
    }

    @Test
    public void 没有Caused_by时仍取第一个业务帧() {
        StackFrame f = StackFrameParser.forPackages("com.example").firstBizFrame(
                "java.lang.NullPointerException: null\n\tat com.example.a.Foo.bar(Foo.java:12)\n\tat com.example.a.Baz.qux(Baz.java:3)");
        Assert.assertEquals("com.example.a.Foo", f.getClassName());
    }
}
