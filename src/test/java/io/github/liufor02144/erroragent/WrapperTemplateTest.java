package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.service.template.LogKeyText;
import org.junit.Assert;
import org.junit.Test;

/**
 * 框架包装层不能当成异常身份。
 *
 * <p>实例：模版「Request processing failed; nested exception is &#123;nested&#125;」底下
 * 归了 9 个指纹 —— 唯一键冲突、渠道接口 I/O 超时、Feign 读超时、MyBatis 多结果、另一家渠道无响应……
 * 全是不相干的故障。原因是 AI 抽模版时把包装层当成了身份，匹配时 exc_cause 又正好是这层包装。
 */
public class WrapperTemplateTest {

    private static final String DUPLICATE_KEY = "- Uncaught exception thrown\n"
            + "org.springframework.web.util.NestedServletException: Request processing failed; "
            + "nested exception is org.springframework.dao.DuplicateKeyException: \n"
            + "### Error updating database.  Cause: com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException: "
            + "Duplicate entry '100001-200000001' for key 'idx_tenant_user'\n"
            + "### The error may involve com.example.mapper.PointNewUserMapper.insertSelective-Inline\n"
            + "### The error occurred while setting parameters\n"
            + "### SQL: insert into point_new_user ( tenant_id, activity_id, user_id, new_flag ) values ( ?, ?, ?, ? )\n"
            + "### Cause: com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException: "
            + "Duplicate entry '100001-200000001' for key 'idx_tenant_user'\n"
            + "\tat org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:989)";

    private static final String PARTNER_IO = "Uncaught exception thrown\n"
            + "org.springframework.web.util.NestedServletException: Request processing failed; "
            + "nested exception is org.springframework.web.client.ResourceAccessException: I/O error on POST request for "
            + "\"https://api.partner.example.com/open/coupon/queryCouponDetail\": api.partner.example.com:443 failed to respond; "
            + "nested exception is org.apache.http.NoHttpResponseException: api.partner.example.com:443 failed to respond\n"
            + "\tat org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:989)";

    private static final String MYBATIS_TOO_MANY = "Uncaught exception thrown\n"
            + "org.springframework.web.util.NestedServletException: Request processing failed; "
            + "nested exception is org.mybatis.spring.MyBatisSystemException: nested exception is "
            + "org.apache.ibatis.exceptions.TooManyResultsException: Expected one result (or null) to be returned by selectOne(), but found: 2\n"
            + "\tat org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:989)";

    @Test
    public void 唯一键冲突取到具体的键() {
        String key = LogKeyText.of(DUPLICATE_KEY);
        Assert.assertTrue(key, key.contains("idx_tenant_user"));
        Assert.assertFalse(key, key.contains("Request processing failed"));
    }

    @Test
    public void 剥掉包装但保留带请求地址的那一层() {
        String key = LogKeyText.of(PARTNER_IO);
        Assert.assertFalse(key, key.contains("Request processing failed"));
        Assert.assertTrue(key, key.contains("ResourceAccessException: I/O error on POST request"));
        Assert.assertTrue(key, key.contains("queryCouponDetail"));
    }

    @Test
    public void 多层包装一路剥到底() {
        String key = LogKeyText.of(MYBATIS_TOO_MANY);
        Assert.assertTrue(key, key.contains("TooManyResultsException: Expected one result"));
        Assert.assertFalse(key, key.contains("MyBatisSystemException"));
    }

    @Test
    public void exc_cause单独剥包装() {
        Assert.assertEquals("org.springframework.dao.DuplicateKeyException:",
                LogKeyText.unwrap("Request processing failed; nested exception is org.springframework.dao.DuplicateKeyException:"));
        // 本身不是包装的原样返回
        Assert.assertEquals("java.net.SocketTimeoutException: Read timed out",
                LogKeyText.unwrap("java.net.SocketTimeoutException: Read timed out"));
    }

    @Test
    public void 只剩包装套话的模版要拒绝() {
        Assert.assertTrue(LogKeyText.isWrapperOnly("Request processing failed; nested exception is {nested}", 6));
        Assert.assertTrue(LogKeyText.isWrapperOnly(
                "Uncaught exception thrown NestedServletException: Request processing failed; nested exception is {异常}", 6));
        Assert.assertFalse(LogKeyText.isWrapperOnly(
                "Uncaught exception thrown MySQLIntegrityConstraintViolationException: Duplicate entry {值} for key 'idx_tenant_user'", 6));
        // 不含包装套话的短模版（空指针按代码位置区分）不归这里管
        Assert.assertFalse(LogKeyText.isWrapperOnly("java.lang.NullPointerException", 6));
    }
}
