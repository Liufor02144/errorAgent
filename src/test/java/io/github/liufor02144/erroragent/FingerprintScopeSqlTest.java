package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.service.collect.InspectSqlBuilder;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 影响面、示例链路、样本三类查询都要带上指纹身份，不能只按 logger 过滤。
 *
 * <p>实例：admin-web 的「InventoryFeign 超时」只出现过 1 次，
 * 但它没有业务栈帧，查询只按 app + WebExceptionHandler 过滤，
 * 结果把全局异常处理器下的所有报错都算了进来：卡片显示 24 小时 432 次、432 笔请求，
 * 示例 traceId 是一次毫不相干的设备心跳（/api/device/heartbeat）。
 */
public class FingerprintScopeSqlTest {

    private final InspectSqlBuilder builder = new InspectSqlBuilder();

    private static final String APP = "admin-web";
    private static final String LOGGER = "c.e.a.WebExceptionHandler";
    private static final String MSG = "系统异常 原因：java.util.concurrent.TimeoutException,堆栈：InventoryFeign#listStock";

    @Test
    public void 无堆栈的指纹按消息模版过滤() throws Exception {
        String trace = builder.buildTraceSampleSql(APP, LOGGER, null, MSG, null, 1);
        String sample = builder.buildSampleQuery(APP, LOGGER, null, MSG, null);
        for (String sql : new String[]{trace, sample}) {
            Assert.assertTrue(sql, sql.contains("= '" + MSG + "'"));
        }
        // 留一份给人工到 SLS 上核对
        String dump = System.getProperty("dump.sql");
        if (dump != null) {
            Files.write(Paths.get(dump), (trace + "\n\n" + sample + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void 有堆栈的指纹按异常原因过滤() {
        String sql = builder.buildTraceSampleSql("product-service", "c.e.p.Foo", "Foo.java:12",
                null, "商品已下架", 1);
        Assert.assertTrue(sql, sql.contains("= '商品已下架'"));
        Assert.assertTrue(sql, sql.contains("\"Foo.java:12\""));
    }

    @Test
    public void 超长原因按前缀比() {
        StringBuilder cause = new StringBuilder();
        while (cause.length() < 200) {
            cause.append("很长的异常原因");
        }
        String sql = builder.buildTraceSampleSql("a", "l", "Foo.java:1", null, cause.toString(), 1);
        Assert.assertTrue(sql, sql.contains("substr("));
    }

    @Test
    public void 单引号要转义() {
        String sql = builder.buildTraceSampleSql("a", "l", null, "field 'tenant_id' missing", null, 1);
        Assert.assertTrue(sql, sql.contains("'field ''tenant_id'' missing'"));
    }

    @Test
    public void 没有身份字段时不加条件() {
        String sql = builder.buildTraceSampleSql("a", "l", "Foo.java:1", null, "null", 1);
        Assert.assertFalse(sql, sql.contains(" = '"));
    }
}
