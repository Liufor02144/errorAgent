package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.dto.StackFrame;
import io.github.liufor02144.erroragent.service.code.StackFrameParser;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * 堆栈解析的验证。
 *
 * <p>重点是被 APM agent 改写的方法名：接了 SkyWalking 的服务，ERROR 日志里
 * 大量堆栈带 {@code $original$} 后缀。不还原的话，
 * 拿改写后的名字去源码里找方法必然落空，定位会悄悄降级到 LOW，
 * 还会给出「该方法已不存在，可能已重构」这种与事实相反的结论。
 */
public class StackFrameParserTest {

    private StackFrameParser parser;

    @Before
    public void setUp() {
        parser = StackFrameParser.forPackages("com.example");
    }

    @Test
    public void 应还原SkyWalking改写的方法名() {
        // 样本形状：方法名后面跟着 $original$随机后缀
        StackFrame f = parser.firstBizFrame(
                "java.lang.RuntimeException: 轮询推送结果失败\n"
              + "\tat com.example.notify.service.helper.MessagePushHelper"
              + ".getPushResult$original$Qjdmutl1(MessagePushHelper.java:152)");

        Assert.assertNotNull(f);
        Assert.assertEquals("getPushResult", f.getMethodName());
        Assert.assertEquals(152, f.getLineNumber());
        Assert.assertEquals("MessagePushHelper.java", f.getFileName());
    }

    @Test
    public void 普通方法名不应被改动() {
        StackFrame f = parser.firstBizFrame(
                "com.example.BusinessException: x\n"
              + "\tat com.example.delivery.service.impl.OrderServiceImpl"
              + ".validateDeliveryOrder(OrderServiceImpl.java:757)");

        Assert.assertEquals("validateDeliveryOrder", f.getMethodName());
        Assert.assertEquals(757, f.getLineNumber());
    }

    @Test
    public void 应取第一个业务栈帧而不是栈顶() {
        // 栈顶常是 JDK / 框架代码，用它会把不同业务问题聚成同一个指纹
        StackFrame f = parser.firstBizFrame(
                "java.io.IOException: No such file\n"
              + "\tat java.io.UnixFileSystem.createFileExclusively(Native Method)\n"
              + "\tat java.io.File.createNewFile(File.java:1012)\n"
              + "\tat com.xxl.job.core.log.XxlJobFileAppender.appendLog(XxlJobFileAppender.java:88)\n"
              + "\tat com.example.order.util.JobLogUtil.log(JobLogUtil.java:17)");

        Assert.assertNotNull(f);
        Assert.assertEquals("JobLogUtil.java", f.getFileName());
        Assert.assertEquals(17, f.getLineNumber());
    }

    @Test
    public void 无业务栈帧时应返回null() {
        Assert.assertNull(parser.firstBizFrame("java.io.IOException: x\n\tat java.io.File.f(File.java:1)"));
        Assert.assertNull(parser.firstBizFrame("没有堆栈的一行日志"));
        Assert.assertNull(parser.firstBizFrame(null));
    }
}
