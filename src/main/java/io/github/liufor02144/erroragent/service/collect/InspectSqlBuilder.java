package io.github.liufor02144.erroragent.service.collect;

import io.github.liufor02144.erroragent.config.InspectProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * SLS 查询语句构造。所有 SQL 均已在真实日志库上验证通过。
 *
 * <p>设计要点见 {@link #MSG_TEMPLATE_EXPR} 与 {@link #bizFrameExpr()} 的注释 ——
 * 这些正则是踩过坑改出来的，改动前请先看注释。
 */
@Slf4j
@Component
public class InspectSqlBuilder {

    /**
     * 首个业务栈帧提取，业务包前缀来自 {@code erroragent.naming.app-packages}。
     *
     * <p>⚠️ 易错点：堆栈实际格式是 {@code at com.example.xxx.method(FileName.java:400)}，
     * 类名与 {@code .java:行号} 之间被方法名和括号隔开，**不是连续的**。
     * 写成 {@code com\.example\.[\w.$]+\.java:\d+} 会全部提取失败返回 null。
     * 必须匹配 {@code \((\w+\.java:\d+)\)} 并取第 2 个捕获组。
     *
     * <p>另外必须取【第一个】业务包栈帧而非栈顶 —— 栈顶大量是
     * java.io.UnixFileSystem、com.xxl.job.core 等 JDK/框架代码，用栈顶会把不同业务问题聚成一个指纹。
     *
     * <p>未配置业务包时一律视为无业务栈帧（{@code NO_STACK}），指纹退回按 logger 与消息模版聚合。
     */
    private String bizFrameExpr() {
        String regex = properties == null ? null : properties.getNaming().slsFrameRegex();
        if (regex == null) {
            return "'NO_STACK'";
        }
        return "coalesce(regexp_extract(message, '" + regex + "', 2), 'NO_STACK')";
    }

    /**
     * 异常类简名提取。
     *
     * <p>同一个异常在日志里有时是全名 {@code com.example...BusinessException}、
     * 有时是短名 {@code BusinessException}，不归一会算成两个指纹。取末段统一。
     */
    private static final String EXC_SIMPLE_EXPR =
            "coalesce(regexp_extract(regexp_extract(message, '([a-zA-Z0-9_.]+(?:Exception|Error))', 1), '([^.]+)$', 1), 'NONE')";

    /**
     * message 模板化。替换顺序不可调整，且每条规则都对应一个实测踩过的坑：
     *
     * <ol>
     *   <li>{@code ^[-\s]+} 前导归一 —— 采集侧前缀不统一，同一条日志会出现
     *       {@code " CACHE…"} / {@code "CACHE…"} / {@code "- CACHE…"} 三种形态，裂成三个指纹</li>
     *   <li>时间 / HASH / 科学计数法 / 小数 / 长整数 → 占位符</li>
     *   <li><b>砍尾 {@code [(|].*$}</b> —— 这一条比所有数字规则加起来都重要。
     *       解决「整个对象 toString 打进日志」：{@code AddressResp(longitude=null…)} 与
     *       {@code AddressResp(longitude=0E-10…)} 本是同一行代码，却因字段值不同裂成两个指纹；
     *       {@code CACHE_METRIC_ABNORMAL|instance=order-service-5f6d7c8b9-abcde} 则会每个 pod 一个指纹</li>
     * </ol>
     *
     * <p>数字门槛定在 6 位：再低会吃掉有意义的错误码（{@code code='D1202'} → {@code D{NUM}}），
     * 再高会漏掉 5 位以内 ID 导致撕裂。经纬度等小数由单独的小数规则覆盖。
     *
     * <p>不做 {@code {PHONE}} 单独标记：订单号中间常有 11 位数字段会被手机号正则误伤，
     * 而 SLS 用 RE2 引擎不支持 lookbehind，加不了边界断言。统一归为 {@code {NUM}}。
     */
    /**
     * 异常真实原因提取。
     *
     * <p>这是整条链路上<b>信息密度最高</b>的一段，实测 126 万条 ERROR 日志的
     * 真正原因藏在这里，而此前一条都没取到 —— 原因不在 message 首行：
     * <ul>
     *   <li>形态 A（126 万条）：原因在<b>第二行</b>的异常行冒号后，
     *       如 {@code BusinessException: 非法令牌 鉴权失败}。
     *       而 msg_tpl 只取首行，完全够不着</li>
     *   <li>形态 B（1.3 万条）：原因在首行的花括号里，
     *       如 {@code BusinessException&#123;code='500', desc='系统繁忙'&#125;}。
     *       它在首行，却被砍尾规则里的 &#123; 砍掉了</li>
     * </ul>
     *
     * <p>为什么非取不可：{@code BusinessException} 是业务主动抛出的通用基类，
     * 光看类名毫无意义 —— 同一个类名下实测有「物流单不存在」「账户未绑定」
     * 「退款出现异常」等十余种<b>完全不同</b>的问题。不提取 desc 就等于把它们
     * 全当成同一个异常。
     */
    private static final String EXC_CAUSE_RAW =
            "coalesce("
                    + "nullif(regexp_extract(message, 'desc=.([^'']{2,120})', 1), ''), "
                    + "nullif(regexp_extract(message, '[\\w.]+(?:Exception|Error): ([^\\n]{2,120})', 1), ''), "
                    + "'')";

    /**
     * 异常原因的模板化。
     *
     * <p>原因里同样带变量，必须和 msg_tpl 走一样的归一，否则指纹会炸开 ——
     * 实测 {@code 调用企业微信Api失败:api forbidden, hint: [1234567890123456789012345], from ip: x.x.x.x}
     * 这一个位置裂出 <b>1089 个指纹却只有 1481 条日志</b>，平均每个指纹 1.4 条，
     * 等于没有聚合。
     *
     * <p>处理的是：反引号内容（userid 等标识）、方括号内容（hint/traceId）、
     * IP、长数字、时间、哈希。实测加上反引号这一条后，同一位置从 5069 个指纹
     * 收敛到 21 个。
     * 砍尾只砍花括号与竖线 —— 冒号后面往往正是原因本身（「调用企业微信Api失败:xxx」），
     * 不能像 msg_tpl 那样连冒号一起砍。
     */
    private static final String EXC_CAUSE_EXPR =
            "regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace("
                    + "regexp_replace(regexp_replace(regexp_replace("
                    // 先剥掉首尾引号：原文有时是 Exception: '商品已售罄（601）'、
                    // 有时是 Exception: 商品已售罄（601），只差一对引号就会裂成两个指纹
                    + "regexp_replace(regexp_replace(" + EXC_CAUSE_RAW + ", '^''+', ''), '''+$', ''), "
                    + "'`[^`]*`', '`{ID}`'), "
                    + "'\\[[^\\]]*\\]', '[{ID}]'), "
                    + "'\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}', '{IP}'), "
                    + "'\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}[.\\d]*', '{TIME}'), "
                    + "'[0-9a-f]{32}', '{HASH}'), "
                    // 小数必须归一，而且要放在整数规则之前：金额是纯变量，
                    // 实测「优惠券订单已支付…总额21.00元，抵扣20.00元，实付1.00元」
                    // 这一句因金额不同裂成 3 个指纹，而它们是同一件事
                    + "'-?\\d+\\.\\d+', '{NUM}'), "
                    // 整数阈值刻意维持 5 位，不跟 msg_tpl 的 3 位对齐：
                    // exc_cause 里的 3 位数往往是错误码（「商品已售罄（601）」「（603）」），
                    // 那是有区分意义的分类，抹掉会把不同原因混成一条
                    + "'\\d{5,}', '{NUM}'), "
                    // 砍尾只砍花括号，<b>不砍竖线</b>：
                    // 业务异常常用「错误码|描述」格式，如「-1|计费接口返回为空,请重试」，
                    // 砍掉 | 后面只剩一个毫无意义的 -1，页面上看着莫名其妙。
                    // 竖线在这里是分隔符，不是 JSON 起始
                    + "'\\{.*$', '')";

    /**
     * message 模板化。顺序不可调整，每一步都对应一个实测踩过的坑：
     *
     * <ol>
     *   <li><b>取首行，空则顺延第二行</b> —— 部分日志（微信支付 SDK）首行只有一个空格，
     *       内容在第二行「结果业务代码异常，返回结果：…」</li>
     *   <li><b>前导归一</b> —— 采集侧前缀不统一，同一条日志会出现
     *       {@code " CACHE…"} / {@code "CACHE…"} / {@code "- CACHE…"} 三种形态</li>
     *   <li><b>先砍尾，后替换数字</b> —— 顺序是关键。砍掉 {@code ( [ { |} 之后的内容，
     *       解决「整个对象/JSON 打进日志」：{@code AddressResp(longitude=null…)}、
     *       {@code 返回结果：{nonce_str=a1b2c3d4…}} 会因字段值不同裂成上百条。
     *       <br>必须先砍再替换：反过来的话 {@code 商品:{NUM}、库存校验不通过} 里的
     *       {@code &#123;NUM&#125;} 会被当成 JSON 起始，把后面的有效描述一起砍掉，
     *       只剩「商品:」</li>
     *   <li><b>数字归一</b> —— 3 位起替换。租户 ID 只有 4~5 位，
     *       用 6 位阈值时每个租户裂成一条指纹</li>
     *   <li><b>截断 80 字</b> —— 同一条「同步评价回复到第三方平台失败」会因后面附带的
     *       消息文案不同裂成几十条，而那段文案对定位毫无价值</li>
     * </ol>
     */
    /**
     * 指标型日志的识别。
     *
     * <p>有一类日志不是异常，而是<b>用竖线拼起来的指标上报</b>，典型是公共缓存组件的
     * 本地缓存指标：
     * <pre>
     * CACHE_METRIC_ABNORMAL|app=xxx|namespace=ProductServiceSupport:productDetail|mode=L1
     *   |hitRate=0.7571|usageRate=47.52|loadFailureCount=0|...
     * </pre>
     *
     * <p>这类日志按<b>指标名整体聚成一条</b>，不按 namespace 拆分 ——
     * 一个服务动辄几十个缓存 namespace，逐个成条会把列表刷满，
     * 而它们本质是同一件事：本地缓存指标异常。
     * 具体是哪个缓存、命中率多少，展开原始日志就能看到。
     */
    private static final String METRIC_NAME_EXPR =
            "case when regexp_like(message, '\\bnamespace=[^|\\s]+')"
                    + "      and regexp_like(message, '^[\\s]*[A-Z][A-Z_]{4,}\\|')"
                    + "     then regexp_extract(message, '([A-Z][A-Z_]{4,})', 1)"
                    + "     else '' end";

    private static final String MSG_TEMPLATE_EXPR =
            "case when " + METRIC_NAME_EXPR + " <> '' then " + METRIC_NAME_EXPR + " "
                    + "else substr("
                    + "regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace("
                    + "regexp_replace(regexp_replace("
                    + "case when length(trim(split_part(message, chr(10), 1))) > 1"
                    + "     then split_part(message, chr(10), 1)"
                    + "     else split_part(message, chr(10), 2) end, "
                    + "'^[-\\s]+', ''), "
                    + "'[(\\[{|].*$', ''), "
                    + "'\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}[.\\d]*', '{TIME}'), "
                    + "'[0-9a-f]{32}', '{HASH}'), "
                    + "'-?\\d+\\.?\\d*E[-+]?\\d+', '{NUM}'), "
                    + "'-?\\d+\\.\\d+', '{NUM}'), "
                    + "'\\d{3,}', '{NUM}'), "
                    + "1, 80) end";

    @Resource
    private InspectProperties properties;

    /**
     * 索引层过滤：只按 log_level 收窄，<b>容器名前缀不在这里做</b>。
     *
     * <p>原先写作 {@code _container_name_: mycompany-*}，实测会<b>静默漏采</b>：
     * SLS 的通配符属于模糊查询，只展开有限个匹配词，超出部分被直接丢弃且不报错。
     * 生产实测（19.4 小时窗口）——
     * <ul>
     *   <li>{@code mycompany-order-service} 真实 30,992 条，通配只匹配到 <b>132 条</b></li>
     *   <li>{@code mycompany-product-task} 真实 39,730 条，通配只匹配到 <b>23 条</b></li>
     *   <li>{@code mycompany-api-gateway} 真实 13,554 条，通配只匹配到 <b>102 条</b></li>
     * </ul>
     * 三者的容器名都确实以 {@code mycompany-} 开头（各自只有一个容器名），
     * 纯粹是被词数上限截掉的。丢哪些服务不确定、也不稳定，比全漏更危险。
     *
     * <p>所以前缀判断改到 SQL 层用 LIKE 做（见 {@link #buildScopeWhere()}）：
     * LIKE 在计算层逐行求值，不受词数限制。代价是索引层多扫约 27% 的行
     * （5 分钟窗口约 5.4 万条），聚合查询完全扛得住。
     */
    public String buildScopeFilter() {
        return "log_level: ERROR";
    }

    /**
     * 计算层的采集范围条件。容器名前缀（{@code erroragent.sls.container-prefix}）圈定采集的服务；
     * 前缀外的服务要纳入时，配在 {@code erroragent.sls.extra-app-ids}（逗号分隔，默认为空）。
     */
    public String buildScopeWhere() {
        StringBuilder sb = new StringBuilder();
        sb.append("(\"_container_name_\" like '")
                .append(sqlLiteral(properties.getSls().getContainerPrefix()))
                .append("%'");
        for (String appId : properties.getSls().extraAppIdList()) {
            sb.append(" or app_id = '").append(sqlLiteral(appId)).append("'");
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * 指纹聚合查询。在 SLS 侧完成聚合，只返回聚合行（实测 1 小时约 1068 行），不拉原始日志。
     *
     * <p>fp_key 按有无堆栈分流：有堆栈用「异常简名 + 业务栈帧」，
     * logger_class 与 message 均不参与 —— 因为 logger_class 是【捕获点】不是抛出点，
     * 全局异常处理器 WebExceptionHandler 在单个服务下有 486 种不同 message，
     * 按 logger 聚合会全部压成 1 条。
     *
     * <p>同时按小时分组（{@code hour_sec}），一次查询既满足统计归属又不漏发现：
     * <ul>
     *   <li>用 {@code __time__ - __time__ % 3600} 做整小时截断，<b>刻意不用 date_trunc/from_unixtime</b> ——
     *       后者的时区取决于 SLS 会话设置，解析偏移会让按小时对齐的基线全部错位</li>
     *   <li>返回秒级时间戳，由 Java 侧转 Date，时区语义由 JVM 统一决定</li>
     *   <li>采集窗口必须【从上一整点小时开始】而非固定滑动窗口 —— 因为 stat 表
     *       唯一键冲突时是覆盖语义，只有每轮都看到该小时的完整累计值，覆盖才不会丢数</li>
     * </ul>
     */
    public String buildAggregateSql(int limit) {
        return buildScopeFilter()
                + " | select"
                // 指标型日志跨服务合并成一条：本地缓存指标异常是同一件事，
                // 29 个服务各占一条只会把列表刷满。具体哪个服务、哪个缓存，
                // 展开原始日志能看到。
                //
                // 这里用的是子查询投影出来的 metric_name，而不是再算一遍 METRIC_NAME_EXPR ——
                // 那个表达式引用 message，而 message 只在子查询内部可见，
                // 写在外层会直接报 "Column 'message' cannot be resolved"
                + " case when metric_name <> '' then metric_name"
                + "      when biz_frame = 'NO_STACK'"
                + "   then concat(app_id, ' | ', logger_class, ' | ', msg_tpl)"
                // 异常原因参与指纹：BusinessException 是业务主动抛出的通用基类，
                // 同一类名下实测有「物流单不存在」「账户未绑定」「退款出现异常」等
                // 十余种完全不同的问题，不带上 cause 就会全部聚成一个，毫无信息量
                + "   else concat(app_id, ' | ', exc_simple, ' | ', biz_frame,"
                + "               case when exc_cause = '' then '' else concat(' | ', exc_cause) end)"
                + "   end as fp_key,"
                + " hour_sec,"
                + " count(*) as cnt,"
                + " max(log_ts) as last_ts,"
                // 跨服务合并的指纹，app_id 取任意一个会误导。
                // 用 count(distinct) 判断是不是多服务，展示时标注出来
                + " arbitrary(app_id) as app_id,"
                + " count(distinct app_id) as app_count,"
                + " arbitrary(logger_class) as logger_class,"
                + " arbitrary(exc_simple) as exc_simple,"
                + " arbitrary(biz_frame) as biz_frame,"
                + " arbitrary(msg_tpl) as msg_tpl,"
                + " arbitrary(exc_cause) as exc_cause"
                + " from ("
                + "   select app_id, logger_class,"
                + "     __time__ as log_ts,"
                + "     (__time__ - __time__ % " + BUCKET_SECONDS + ") as hour_sec,"
                + "     " + EXC_SIMPLE_EXPR + " as exc_simple,"
                + "     " + EXC_CAUSE_EXPR + " as exc_cause,"
                + "     " + bizFrameExpr() + " as biz_frame,"
                + "     " + METRIC_NAME_EXPR + " as metric_name,"
                + "     " + MSG_TEMPLATE_EXPR + " as msg_tpl"
                + "   from log"
                + "   where " + buildScopeWhere()
                + " )"
                + " group by 1, 2 order by cnt desc limit " + limit;
    }

    /**
     * 时间分桶粒度（秒）。
     *
     * <p><b>桶的粒度必须匹配扫描节奏</b>，这是采集窗口能做小的前提：
     * stat 表按 (fp_id, 桶) 覆盖写入，一个桶只有被窗口<b>完整包含</b>过，
     * 写进去的才是终值。桶 = 5 分钟、窗口 = 10 分钟时，
     * 每个桶都会在下一轮被完整覆盖一次，天然收敛。
     *
     * <p>原本是 3600（整小时），于是窗口必须一路回退到整点才能保证桶完整 ——
     * 实测窗口跨度 60~120 分钟、单轮 258 秒，逼近 300 秒的扫描间隔。
     * 根因不是窗口选大了，是桶选大了。
     */
    private static final int BUCKET_SECONDS = 300;

    /**
     * 单个指纹的原始日志样本查询。仅对判定为 NEW / SURGE 的指纹调用，
     * 因此每轮只有几个到几十次，不构成压力。
     *
     * @param appId       服务名
     * @param loggerClass 缩写 logger，可为空
     * @param bizFrame    业务栈帧（File.java:line），NO_STACK 时为空
     */
    public String buildSampleQuery(String appId, String loggerClass, String bizFrame) {
        return buildSampleQuery(appId, loggerClass, bizFrame, null, null);
    }

    /**
     * 单指纹样本，带上指纹的身份条件（见 {@link #fpCondition}）。
     */
    public String buildSampleQuery(String appId, String loggerClass, String bizFrame,
                                   String msgTpl, String excCause) {
        StringBuilder sb = new StringBuilder();
        sb.append("log_level: ERROR and app_id: \"").append(phrase(appId)).append("\"");
        if (notBlank(loggerClass)) {
            sb.append(" and logger_class: \"").append(phrase(loggerClass)).append("\"");
        }
        // 有堆栈时用栈帧文本进一步收窄，避免同 logger 下取到别的异常样本
        if (notBlank(bizFrame) && !"NO_STACK".equals(bizFrame)) {
            sb.append(" and \"").append(phrase(bizFrame)).append("\"");
        }
        // __time__ 必须起别名：它是 SLS 内置元数据字段，直接 select 会与自带的同名列
        // 冲突而被重命名成 __time___0，按 __time__ 取值只会拿到 null ——
        // 表现是样本时间全部落在 1970-01-01
        sb.append(" | select message, trace_id, _pod_name_, _image_name_, __time__ as log_ts from log");
        String cond = fpCondition(bizFrame, msgTpl, excCause);
        if (!cond.isEmpty()) {
            sb.append(" where ").append(cond);
        }
        sb.append(" order by __time__ desc limit 1");
        return sb.toString();
    }

    /**
     * 日志侧影响面探查。
     *
     * <p><b>影响面首先应该从日志里算，而不是查生产库。</b>理由有三：
     * <ol>
     *   <li><b>日志里的是实际发生的，查库算的是推断的。</b>
     *       「这 37 笔订单确实报了错」比「库里有 37 笔处于该状态」更接近真相</li>
     *   <li><b>失败请求往往根本没落库</b> —— 写入被拒、鉴权失败、超时中断，
     *       查库只会得到一个显著偏小的数字，比不查更有害：它让人以为影响面很小</li>
     *   <li><b>生产只读库是要省着用的</b>，而日志本来就在采</li>
     * </ol>
     *
     * <p>这条查询把日志里出现的业务标识抽出来去重计数 ——
     * 涉及多少笔订单、多少个租户、多少个用户、多少条链路。
     * 这些数字是<b>系统算出来的事实</b>，直接喂给模型，它就不必再去猜要查哪张表。
     *
     * @param appId       服务名
     * @param loggerClass 缩写 logger，可为空
     * @param bizFrame    业务栈帧，可为空
     */
    /**
     * 示例 traceId：同一筛选条件下最近出现的链路，按链路去重，条数由 limit 决定。
     */
    public String buildTraceSampleSql(String appId, String loggerClass, String bizFrame, int limit) {
        return buildTraceSampleSql(appId, loggerClass, bizFrame, null, null, limit);
    }

    public String buildTraceSampleSql(String appId, String loggerClass, String bizFrame,
                                      String msgTpl, String excCause, int limit) {
        String cond = fpCondition(bizFrame, msgTpl, excCause);
        return fingerprintFilter(appId, loggerClass, bizFrame)
                + " | select trace_id, max(__time__) as t from log"
                // 没接链路追踪的服务会打出占位值，那不是 traceId
                + " where trace_id is not null and trace_id <> ''"
                + (cond.isEmpty() ? "" : " and " + cond)
                + " and trace_id not in ('N/A', 'n/a', '-', 'null', 'NULL', 'unknown', 'TID:N/A', 'TID: N/A')"
                + " group by trace_id order by t desc limit " + limit;
    }

    private String fingerprintFilter(String appId, String loggerClass, String bizFrame) {
        StringBuilder sb = new StringBuilder();
        sb.append("log_level: ERROR and app_id: \"").append(phrase(appId)).append("\"");
        if (notBlank(loggerClass)) {
            sb.append(" and logger_class: \"").append(phrase(loggerClass)).append("\"");
        }
        if (notBlank(bizFrame) && !"NO_STACK".equals(bizFrame)) {
            sb.append(" and \"").append(phrase(bizFrame)).append("\"");
        }
        return sb.toString();
    }

    /**
     * 指纹身份条件：用与聚合完全相同的表达式重算，要求等于这个指纹的值。
     *
     * <p>只按「服务 + logger + 栈帧」过滤是不够的。无堆栈的指纹身份是「服务 + logger + 消息模版」，
     * 漏掉消息模版，全局异常处理器（WebExceptionHandler）下的所有报错都会算进来 ——
     * 实测一条只出现 1 次的「InventoryFeign 超时」，卡片上显示 24 小时 432 次、
     * 示例 traceId 是一次毫不相干的心跳请求。
     * 有堆栈的同理：同一个栈帧可能抛出多种不同原因，要再按原因区分。
     *
     * <p>库里的 exc_cause 最长 200 字（超长会被截断），超长时按前缀比，不然永远对不上。
     *
     * @return SQL 条件；没有可用的身份字段时返回空串
     */
    String fpCondition(String bizFrame, String msgTpl, String excCause) {
        boolean noStack = !notBlank(bizFrame) || "NO_STACK".equals(bizFrame);
        if (noStack) {
            return notBlank(msgTpl) ? MSG_TEMPLATE_EXPR + " = " + sqlString(msgTpl) : "";
        }
        if (!io.github.liufor02144.erroragent.service.InspectText.notBlank(excCause)) {
            return "";
        }
        if (excCause.length() >= 200) {
            return "substr(" + EXC_CAUSE_EXPR + ", 1, " + excCause.length() + ") = " + sqlString(excCause);
        }
        return EXC_CAUSE_EXPR + " = " + sqlString(excCause);
    }

    private static String sqlString(String v) {
        return "'" + sqlLiteral(v) + "'";
    }

    /** 拼进 SQL 单引号字符串内部的值：单引号写两遍 */
    static String sqlLiteral(String v) {
        return v == null ? "" : v.replace("'", "''");
    }

    /** 拼进检索语句双引号短语内部的值：去掉双引号和反斜杠，避免提前闭合短语 */
    static String phrase(String v) {
        return v == null ? "" : v.replace("\"", "").replace("\\", "");
    }

    /**
     * 同一条链路（同一次请求）里各服务打的 ERROR 日志。
     *
     * <p>真正的原因常常不在报错的那一条里：调第三方支付取消订单时，被拦截的原因
     * 「该支付类型不支持关闭订单！」「订单状态为其他，不许撤销」打在前面几条日志里，
     * 最后抛出来的只是外层包装「订单取消失败，请稍后再试」。
     */
    public String buildTraceErrorsSql(String traceId, int limit) {
        return "log_level: ERROR and trace_id: \"" + phrase(traceId) + "\""
                + " | select app_id, message, __time__ as log_ts from log order by log_ts limit " + limit;
    }

    private static boolean notBlank(String v) {
        return v != null && !v.trim().isEmpty();
    }
}
