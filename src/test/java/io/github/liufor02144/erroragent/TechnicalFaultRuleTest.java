package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.service.template.TechnicalFaultRule;
import org.junit.Assert;
import org.junit.Test;

/**
 * 技术故障不得被批量放行。
 *
 * <p>背景：系统平稳期可以把存量异常一次性认作业务预期，这是合理的省事做法。
 * 但有一类绝不能被顺手带进去 —— 空指针、SQL 异常、各类超时。
 * 它们一旦被标成业务预期就<b>永久静默</b>，而「今天只有 2 次」不代表
 * 明天不是 2 万次：量小只说明触发条件还没铺开。
 *
 * <p>这条规则同时约束三个入口：批量标记、AI 复核自动标记、采集时的兜底模版。
 */
public class TechnicalFaultRuleTest {

    @Test
    public void 空指针必须判为技术故障() {
        Assert.assertTrue(TechnicalFaultRule.isFault("java.lang.NullPointerException: null"));
        Assert.assertTrue(TechnicalFaultRule.isFault("doRefund Exception,orderId:{NUM} NullPointerException"));
        // 只给异常类名也要认出来
        Assert.assertTrue(TechnicalFaultRule.isFault(null, "NullPointerException", null));
    }

    @Test
    public void SQL与数据层异常必须判为技术故障() {
        String[] cases = {
                "java.sql.SQLException: Duplicate entry",
                "BadSqlGrammarException: bad SQL grammar",
                "DataIntegrityViolationException: Field 'tenant_id' doesn't have a default value",
                "Communications link failure",
                "Deadlock found when trying to get lock",
                "Lock wait timeout exceeded",
                "UnexpectedRollbackException: Transaction rolled back"
        };
        for (String c : cases) {
            Assert.assertTrue("应判为技术故障：" + c, TechnicalFaultRule.isFault(c));
        }
    }

    @Test
    public void 各类超时必须判为技术故障() {
        String[] cases = {
                "java.net.SocketTimeoutException: Read timed out",
                "java.util.concurrent.TimeoutException",
                "feign.RetryableException: Read timed out executing GET",
                "connect timed out",
                "timeout of 10000ms exceeded",
                "RedisCommandTimeoutException",
                "org.apache.http.NoHttpResponseException"
        };
        for (String c : cases) {
            Assert.assertTrue("应判为技术故障：" + c, TechnicalFaultRule.isFault(c));
        }
    }

    @Test
    public void 业务文案里的超时两个字不算技术故障() {
        // 这条如果被误判，它会被永久挡在批量放行之外，每次出现都报
        Assert.assertFalse(
                TechnicalFaultRule.isFault("该优惠券已转赠但还未被领取，领取后或超时退回后再使用"));
        Assert.assertFalse(TechnicalFaultRule.isFault("超期订单不允许退款"));
        Assert.assertFalse(TechnicalFaultRule.isFault("订单超时未支付已自动取消"));
    }

    @Test
    public void 普通业务拒绝不算技术故障() {
        String[] cases = {
                "业务异常 BusinessException{code='{错误码}', desc='商品已下架'}",
                "券码不存在",
                "商品已售罄，请更换商品后重新下单（{错误码}）",
                "用户名或密码错误",
                "账号已绑定第三方账号，请刷新页面",
                "未查询到订单"
        };
        for (String c : cases) {
            Assert.assertFalse("不该判为技术故障：" + c, TechnicalFaultRule.isFault(c));
        }
    }

    @Test
    public void 任一字段命中即算() {
        // 模版文案看着像业务，但异常类是空指针 —— 必须以技术特征为准
        Assert.assertTrue(TechnicalFaultRule.isFault(
                "系统异常 原因：null uri:/base/info/detail",
                "NullPointerException",
                null));
    }

    /**
     * 堆栈帧不参与判定。
     *
     * <p>实测事故：「拉取渠道商品失败 BusinessException: 拉取渠道商品列表异常」
     * 这条纯业务异常被判成技术故障，理由是命中「ThreadPool」——
     * 而那来自样例里的 {@code at java.util.concurrent.ThreadPoolExecutor.runWorker}，
     * <b>几乎每个 Java 堆栈都有这一帧</b>，拿原始样例去比对等于见谁判谁。
     */
    @Test
    public void 堆栈帧里的类名不参与判定() {
        String withStack = "HIGH 拉取渠道商品失败 BusinessException: 拉取渠道商品列表异常\n"
                + "\tat com.example.channel.ChannelProductApi.listProducts(ChannelProductApi.java:336)\n"
                + "\tat java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1149)\n"
                + "\tat java.lang.Thread.run(Thread.java:748)";
        Assert.assertFalse("业务异常不该因为堆栈里的线程池帧被判成技术故障",
                TechnicalFaultRule.isFault(withStack));
    }

    @Test
    public void 堆栈里的SQLException帧也不算() {
        // 同理：连接池把 SQLException 出现在某一帧上，不代表这条异常是 SQL 故障
        String withStack = "下单失败 BusinessException: 库存不足\n"
                + "\tat com.mysql.jdbc.SQLError.createSQLException(SQLError.java:965)";
        Assert.assertFalse(TechnicalFaultRule.isFault(withStack));
    }

    @Test
    public void 异常行上的技术特征仍然要认出来() {
        // 剔堆栈不能把真正的根因一起剔掉 —— 根因在非 at 开头的行上
        String real = "【配送回调】回调日志入库异常\n"
                + "com.mysql.jdbc.MysqlDataTruncation: Data truncation: Data too long\n"
                + "\tat com.mysql.jdbc.MysqlIO.checkErrorPacket(MysqlIO.java:3973)";
        Assert.assertTrue("根因行上的 Data truncation 要认出来",
                TechnicalFaultRule.isFault(real, "MysqlDataTruncation"));
    }

    @Test
    public void 命中的特征要能说出来() {
        Assert.assertEquals("NullPointerException",
                TechnicalFaultRule.matchedSign("java.lang.NullPointerException: null"));
        Assert.assertNull(TechnicalFaultRule.matchedSign("商品已下架"));
    }

    @Test
    public void 空输入不崩且不误判() {
        Assert.assertFalse(TechnicalFaultRule.isFault());
        Assert.assertFalse(TechnicalFaultRule.isFault((String) null));
        Assert.assertFalse(TechnicalFaultRule.isFault("", "   ", null));
    }
}
