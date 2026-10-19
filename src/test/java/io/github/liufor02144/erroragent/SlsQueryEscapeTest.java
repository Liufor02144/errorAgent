package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.config.InspectProperties;
import io.github.liufor02144.erroragent.service.collect.InspectSqlBuilder;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 拼进 SLS 查询的值必须转义，否则一个带引号的服务名、logger 或配置值就能改写整条查询。
 */
public class SlsQueryEscapeTest {

    @Test
    public void 检索短语里的双引号和反斜杠要去掉() {
        String sql = new InspectSqlBuilder().buildSampleQuery("order\" or \"1", "c.e.Foo\\", "Foo.java:1");
        Assert.assertTrue(sql, sql.contains("app_id: \"order or 1\""));
        Assert.assertTrue(sql, sql.contains("logger_class: \"c.e.Foo\""));
    }

    @Test
    public void traceId里的引号要去掉() {
        String sql = new InspectSqlBuilder().buildTraceErrorsSql("abc\" or \"x", 5);
        Assert.assertTrue(sql, sql.contains("trace_id: \"abc or x\""));
    }

    @Test
    public void 采集范围里的单引号要写两遍() {
        InspectSqlBuilder builder = new InspectSqlBuilder();
        InspectProperties props = new InspectProperties();
        props.getSls().setContainerPrefix("svc'-");
        props.getSls().setExtraAppIds("a'b");
        ReflectionTestUtils.setField(builder, "properties", props);

        String where = builder.buildScopeWhere();
        Assert.assertTrue(where, where.contains("like 'svc''-%'"));
        Assert.assertTrue(where, where.contains("app_id = 'a''b'"));
    }

    @Test
    public void 未配置业务包时栈帧一律视为NO_STACK() {
        InspectSqlBuilder builder = new InspectSqlBuilder();
        ReflectionTestUtils.setField(builder, "properties", new InspectProperties());
        Assert.assertTrue(builder.buildAggregateSql(10).contains("'NO_STACK' as biz_frame"));
    }

    @Test
    public void 配置业务包后按包前缀提取栈帧() {
        InspectSqlBuilder builder = new InspectSqlBuilder();
        InspectProperties props = new InspectProperties();
        props.getNaming().setAppPackages("com.example, org.acme");
        ReflectionTestUtils.setField(builder, "properties", props);
        String sql = builder.buildAggregateSql(10);
        Assert.assertTrue(sql, sql.contains("at ((?:com\\.example|org\\.acme)\\.[\\w.$]+)\\((\\w+\\.java:\\d+)\\)"));
    }
}
