package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.service.template.LogKeyText;
import org.junit.Assert;
import org.junit.Test;

/**
 * 异常身份必须取<b>最深一层的根因</b>，不是最外层的包装。
 *
 * <p>这是「拿到的不是最有信息量那段文本」的第五次变体，每次换一层皮：
 * <ol>
 *   <li>第 1 次：SLS 侧 msg_tpl 只取首行</li>
 *   <li>第 2 次：模版抽取只取首行</li>
 *   <li>第 3 次：模版复核只取首行</li>
 *   <li>第 4 次：取到了异常行，但取的是<b>最外层</b>的那条 ——
 *       {@code NestedServletException: Request processing failed}</li>
 *   <li>第 5 次：会找根因了，但异常行正则写死了 {@code (?:Exception|Error)} 后缀 ——
 *       {@code com.mysql.jdbc.MysqlDataTruncation} 不以它们结尾，
 *       于是「Data too long for column 'push_content'」整条被跳过。
 *       <b>教训：不要对异常类名的形状做假设</b>，
 *       {@code ### Cause:} / {@code Caused by:} 这些标记本身就是信号，
 *       后面跟什么都算根因</li>
 * </ol>
 *
 * <p>第 4 次的危害和前三次一样：几十种完全不同的故障（少默认值、唯一键冲突、
 * 读超时）会共用同一条模版。这条模版既没有诊断价值，也无法被正确标注 ——
 * 它里面既有该放行的也有该报的。
 *
 * <p>判据：<b>能拿去改代码的那句话才是根因。</b>
 * 「Field 'tenant_id' doesn't have a default value」能定位到表和列；
 * 「Request processing failed」什么也定位不到。
 */
public class RootCauseTest {

    /** Spring MVC + MyBatis 的报文形状（仿照生产日志构造） */
    private static final String SPRING_MYBATIS =
            " Servlet.service() for servlet [dispatcherServlet] in context with path []"
                    + " threw exception [Request processing failed; nested exception is"
                    + " org.springframework.dao.DataIntegrityViolationException: \n"
                    + "### Error updating database.  Cause: java.sql.SQLException:"
                    + " Field 'tenant_id' doesn't have a default value\n"
                    + "### The error may involve com.example.product.mapper"
                    + ".ProductSyncTaskMapper.insertSelective-Inline\n"
                    + "### The error occurred while setting parameters\n"
                    + "### SQL: insert into product_sync_task      ( catalog_id,"
                    + "                       sync_rule,                       flag )\n"
                    + "### Cause: java.sql.SQLException: Field 'tenant_id'"
                    + " doesn't have a default value\n"
                    + "; Field 'tenant_id' doesn't have a default value; nested exception is"
                    + " java.sql.SQLException: Field 'tenant_id' doesn't have a default value]"
                    + " with root cause\n"
                    + "java.sql.SQLException: Field 'tenant_id' doesn't have a default value\n"
                    + "\tat com.mysql.jdbc.SQLError.createSQLException(SQLError.java:965)\n"
                    + "\tat com.mysql.jdbc.MysqlIO.checkErrorPacket(MysqlIO.java:3973)";

    /** MyBatis 唯一键冲突（仿照生产日志构造） */
    private static final String DUPLICATE_ENTRY =
            " splitPrint getInvoiceUrl Exception,orderId:900000000000000000000001\n"
                    + "org.springframework.dao.DuplicateKeyException: \n"
                    + "### Error updating database.  Cause:"
                    + " com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException:"
                    + " Duplicate entry '900000000000000000000001-tx-20260101120000-4'"
                    + " for key 'idx_mult'\n"
                    + "### The error may involve com.example.print.mapper"
                    + ".MsgLogMapper.insertSelective-Inline\n"
                    + "### SQL: insert into msg_log      ( order_id, third_order_id )\n"
                    + "### Cause: com.mysql.jdbc.exceptions.jdbc4"
                    + ".MySQLIntegrityConstraintViolationException:"
                    + " Duplicate entry '900000000000000000000001-tx-20260101120000-4'"
                    + " for key 'idx_mult'\n";

    /** 标准 Java 异常链 */
    private static final String CAUSED_BY_CHAIN =
            "handleOrder failed, orderId:123456\n"
                    + "com.foo.ServiceException: 下单失败\n"
                    + "\tat com.foo.OrderService.create(OrderService.java:88)\n"
                    + "Caused by: com.foo.DaoException: 写库失败\n"
                    + "\tat com.foo.OrderDao.insert(OrderDao.java:42)\n"
                    + "Caused by: java.sql.SQLException: Lock wait timeout exceeded\n"
                    + "\tat com.mysql.jdbc.MysqlIO.sendCommand(MysqlIO.java:2527)";

    /** 配送回调入库失败（仿照生产日志构造）。根因类名不以 Exception 结尾 */
    private static final String DATA_TRUNCATION =
            " 【配送回调】回调日志入库异常 :\n"
                    + "org.springframework.dao.DataIntegrityViolationException: \n"
                    + "### Error updating database.  Cause:"
                    + " com.mysql.jdbc.MysqlDataTruncation: Data truncation:"
                    + " Data too long for column 'push_content' at row 1\n"
                    + "### The error may exist in URL [jar:file:/opt/delivery-service.jar!"
                    + "/BOOT-INF/lib/delivery-dao.jar!/mapper/CallbackLogMapper.xml]\n"
                    + "### The error may involve com.example.delivery.dao"
                    + ".mapper.CallbackLogMapper.insert-Inline\n"
                    + "### The error occurred while setting parameters\n"
                    + "### SQL: insert into callback_log (order_id, order_status, push_content)\n";

    /**
     * 异常类名不以 Exception / Error 结尾时也必须认出来。
     *
     * <p>{@code com.mysql.jdbc.MysqlDataTruncation} 就是这样 ——
     * 原来的异常行正则写死了 {@code (?:Exception|Error)} 后缀，
     * 于是这一整条根因被跳过，模版退化成最外层那个
     * 连 message 都没有的 {@code DataIntegrityViolationException:}，
     * 停在一个悬空的冒号上。
     */
    @Test
    public void 类名不以Exception结尾的根因也要取到() {
        String key = LogKeyText.of(DATA_TRUNCATION);
        Assert.assertTrue("必须取到 Data too long，实际：" + key,
                key.contains("Data too long"));
        Assert.assertTrue("要保留列名，那是唯一能定位的线索：" + key,
                key.contains("push_content"));
    }

    @Test
    public void 不能停在没有message的包装异常上() {
        String key = LogKeyText.of(DATA_TRUNCATION);
        // DataIntegrityViolationException 后面什么都没有，取到它等于什么都没取到
        Assert.assertFalse("不该停在空壳包装异常上：" + key,
                key.trim().endsWith("DataIntegrityViolationException:"));
    }

    @Test
    public void URL里的冒号不会被误当成异常行() {
        // 放宽正则去认全限定类名后，要确保没把 https://api.partner.example.com:443 也认进去
        String raw = "调用渠道接口失败\n"
                + "url: https://api.partner.example.com:443/open/order\n"
                + "Caused by: java.net.SocketTimeoutException: Read timed out\n";
        String key = LogKeyText.of(raw);
        Assert.assertTrue("应取到真正的根因：" + key, key.contains("Read timed out"));
        Assert.assertFalse("不该把 URL 当成异常行：" + key, key.contains("api.partner.example.com:443"));
    }

    @Test
    public void 取到能定位到表和列的那句话() {
        String key = LogKeyText.of(SPRING_MYBATIS);
        Assert.assertTrue("必须取到根因，实际：" + key,
                key.contains("Field 'tenant_id' doesn't have a default value"));
    }

    @Test
    public void 不能把通用包装文案当成身份() {
        String key = LogKeyText.of(SPRING_MYBATIS);
        // 这句话几十种故障都会打，留着它模版就没有区分度
        Assert.assertFalse("不该保留「Request processing failed」，实际：" + key,
                key.contains("Request processing failed"));
        Assert.assertFalse("不该保留「nested exception is」，实际：" + key,
                key.contains("nested exception is"));
    }

    @Test
    public void 唯一键冲突要能看出冲突在哪个索引() {
        String key = LogKeyText.of(DUPLICATE_ENTRY);
        Assert.assertTrue("要保留索引名 idx_mult，实际：" + key, key.contains("idx_mult"));
        Assert.assertTrue("要保留 Duplicate entry，实际：" + key,
                key.contains("Duplicate entry"));
    }

    @Test
    public void 异常链取最后一个CausedBy() {
        String key = LogKeyText.of(CAUSED_BY_CHAIN);
        Assert.assertTrue("最深层是 Lock wait timeout，实际：" + key,
                key.contains("Lock wait timeout exceeded"));
        // 中间层是转译，不该成为身份
        Assert.assertFalse("不该停在中间层 DaoException，实际：" + key,
                key.contains("写库失败"));
    }

    @Test
    public void 首行的上下文要保留() {
        // 根因说「为什么」，首行说「在做什么」—— 两个都需要
        String key = LogKeyText.of(DUPLICATE_ENTRY);
        Assert.assertTrue("要保留首行上下文，实际：" + key,
                key.contains("splitPrint getInvoiceUrl"));
    }

    @Test
    public void 没有异常链时行为不变() {
        String simple = "doRefund Exception,orderId:123\n"
                + "java.lang.RuntimeException: 商户未开通微信支付，请联系商家";
        String key = LogKeyText.of(simple);
        Assert.assertTrue(key.contains("doRefund Exception"));
        Assert.assertTrue(key.contains("商户未开通微信支付"));
    }

    @Test
    public void rootCauseOf单独可用() {
        Assert.assertTrue(LogKeyText.rootCauseOf(SPRING_MYBATIS)
                .contains("Field 'tenant_id' doesn't have a default value"));
        Assert.assertTrue(LogKeyText.rootCauseOf(CAUSED_BY_CHAIN)
                .contains("Lock wait timeout exceeded"));
        Assert.assertEquals("", LogKeyText.rootCauseOf(null));
        Assert.assertEquals("", LogKeyText.rootCauseOf("   "));
    }

    @Test
    public void 结果必须是单行() {
        // 生成的正则用 . 做通配，而 Java 正则的 . 不跨换行
        for (String raw : new String[]{SPRING_MYBATIS, DUPLICATE_ENTRY, CAUSED_BY_CHAIN}) {
            String key = LogKeyText.of(raw);
            Assert.assertFalse("不能含换行：" + key, key.contains("\n"));
            Assert.assertFalse("不能含回车：" + key, key.contains("\r"));
        }
    }

    @Test
    public void 两条不同根因不会被压成同一身份() {
        // 这是整条规则的目的：少默认值 和 唯一键冲突 必须是两个不同的身份
        String a = LogKeyText.of(SPRING_MYBATIS);
        String b = LogKeyText.of(DUPLICATE_ENTRY);
        Assert.assertNotEquals("两种完全不同的故障被压成了同一身份", a, b);
    }
}
