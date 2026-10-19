package io.github.liufor02144.erroragent.service.collect;

import com.aliyun.openservices.log.Client;
import com.aliyun.openservices.log.common.LogContent;
import com.aliyun.openservices.log.common.QueriedLog;
import com.aliyun.openservices.log.response.GetLogsResponse;
import io.github.liufor02144.erroragent.config.InspectProperties;
import io.github.liufor02144.erroragent.dto.FingerprintRow;
import io.github.liufor02144.erroragent.dto.SampleRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SLS 查询，统一走 SDK 的 {@code executeLogstoreSql}。
 *
 * <p>标准 SQL 分析免费，故 powerSql 固定为 false。若将来查询量涨到需要独享 SQL
 * （￥0.6/CU/小时），再提为配置项。
 */
@Slf4j
@Repository
public class InspectSlsRepository {

    /** 标准 SQL（免费）。true 表示独享 SQL，需额外开通且按 CU 计费 */
    private static final boolean POWER_SQL = false;

    /** 结果未扫完时的重试次数 */
    private static final int INCOMPLETE_RETRIES = 2;

    @Resource
    private InspectProperties properties;

    @Resource
    private InspectSqlBuilder sqlBuilder;

    private volatile Client client;

    /**
     * 懒加载 SLS 客户端。配置为重启生效，故创建一次即可复用。
     */
    private Client client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    InspectProperties.Sls sls = properties.getSls();
                    if (!sls.ready()) {
                        throw new IllegalStateException("SLS 配置不完整，请检查 erroragent.sls.* 配置");
                    }
                    client = new Client(sls.getEndpoint(), sls.getAccessKeyId(), sls.getAccessKeySecret());
                    log.info("SLS 客户端初始化完成 endpoint={} project={} logstore={}",
                            sls.getEndpoint(), sls.getProject(), sls.getLogstore());
                }
            }
        }
        return client;
    }

    /**
     * 指纹聚合查询。
     *
     * @param startMs 窗口起（毫秒）
     * @param endMs   窗口止（毫秒）
     * @param limit   返回行数上限
     */
    /**
     * 诊断用：执行任意查询语句并返回原始行。
     *
     * <p>加这个口子是因为排查 SLS 问题时，「同一条 SQL 在控制台能跑、在应用里报错」
     * 这类差异只能靠逐条探测定位 —— 错误信息本身会被 {@link #explain} 归一成
     * 一句人话，反而丢掉了原始 cause。
     *
     * <p>只读：SLS 侧没有写接口，且调用方需带登录态。
     */
    public List<Map<String, String>> probe(String sql, long startMs, long endMs) {
        return query(sql, startMs, endMs);
    }

    public List<FingerprintRow> queryFingerprints(long startMs, long endMs, int limit) {
        String sql = sqlBuilder.buildAggregateSql(limit);
        List<Map<String, String>> rows = query(sql, startMs, endMs);
        List<FingerprintRow> result = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            FingerprintRow fr = new FingerprintRow();
            fr.setFpKey(row.get("fp_key"));
            fr.setCnt(toLong(row.get("cnt")));
            fr.setHourSec(toLong(row.get("hour_sec")));
            fr.setLastTsSec(toLong(row.get("last_ts")));
            fr.setAppId(row.get("app_id"));
            fr.setLoggerClass(row.get("logger_class"));
            fr.setExcSimple(row.get("exc_simple"));
            fr.setBizFrame(row.get("biz_frame"));
            fr.setMsgTpl(row.get("msg_tpl"));
            fr.setExcCause(row.get("exc_cause"));
            fr.setAppCount(parseIntSafe(row.get("app_count")));
            if (fr.getFpKey() != null && !fr.getFpKey().trim().isEmpty()) {
                result.add(fr);
            }
        }
        return result;
    }

    /** 单指纹原始日志样本 */
    public SampleRow querySample(String appId, String loggerClass, String bizFrame,
                                 long startMs, long endMs) {
        return querySample(appId, loggerClass, bizFrame, null, null, startMs, endMs);
    }

    /**
     * 单指纹原始日志样本，按指纹身份精确过滤（无堆栈比消息模版，有堆栈比异常原因）。
     * 不带身份条件时，同一 logger 下取到的可能是别的异常的样本。
     */
    public SampleRow querySample(String appId, String loggerClass, String bizFrame,
                                 String msgTpl, String excCause, long startMs, long endMs) {
        String sql = sqlBuilder.buildSampleQuery(appId, loggerClass, bizFrame, msgTpl, excCause);
        List<Map<String, String>> rows = query(sql, startMs, endMs);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, String> row = rows.get(0);
        SampleRow sr = new SampleRow();
        sr.setMessage(row.get("message"));
        sr.setTraceId(row.get("trace_id"));
        sr.setPodName(row.get("_pod_name_"));
        sr.setImageName(row.get("_image_name_"));
        sr.setLogTimeSec(toLong(row.get("log_ts")));
        return sr;
    }

    /**
     * 同一条链路里各服务的 ERROR 日志，按时间先后。查不到或失败返回空列表，不影响分析。
     */
    public List<Map<String, String>> queryErrorsByTrace(String traceId, long startMs, long endMs, int limit) {
        if (traceId == null || traceId.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return query(sqlBuilder.buildTraceErrorsSql(traceId.trim(), limit), startMs, endMs);
        } catch (Exception e) {
            log.warn("查同链路报错失败 traceId={}：{}", traceId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 这个指纹出现过的请求（traceId），最近的在前 */
    public List<String> queryTraceIds(String appId, String loggerClass, String bizFrame,
                                      String msgTpl, String excCause, long startMs, long endMs, int limit) {
        List<String> ids = new ArrayList<>();
        try {
            for (Map<String, String> row : query(sqlBuilder.buildTraceSampleSql(
                    appId, loggerClass, bizFrame, msgTpl, excCause, limit), startMs, endMs)) {
                String id = row.get("trace_id");
                if (id != null && !id.trim().isEmpty()) {
                    ids.add(id.trim());
                }
            }
        } catch (Exception e) {
            log.warn("查指纹的请求列表失败 app={}：{}", appId, e.getMessage());
        }
        return ids;
    }

    /**
     * 执行查询。
     *
     * <p>时间参数需转成【秒】—— SDK 的 executeLogstoreSql 接收 int 秒级时间戳，
     * 传毫秒会得到一个 1970 年附近的窗口从而查不到任何数据。
     */
    private List<Map<String, String>> query(String sql, long startMs, long endMs) {
        return query(properties.getSls().getProject(), properties.getSls().getLogstore(), sql, startMs, endMs);
    }

    private List<Map<String, String>> query(String project, String logstore, String sql,
                                            long startMs, long endMs) {
        int from = (int) (startMs / 1000L);
        int to = (int) ((endMs + 999L) / 1000L);
        long began = System.currentTimeMillis();
        try {
            GetLogsResponse response = client().executeLogstoreSql(
                    project, logstore, from, to, sql, POWER_SQL);
            // SLS 数据量大时会先返回「未扫完」的结果 —— count 偏小、长尾指纹缺失，
            // 而返回里不带任何错误。不看这个标志，漏数是完全静默的。
            // 同一查询重试时服务端会续扫，通常一两次就完整
            for (int retry = 0; !response.IsCompleted() && retry < INCOMPLETE_RETRIES; retry++) {
                log.warn("SLS 结果未扫描完整（已扫 {} 行），第 {} 次重试", response.getProcessedRow(), retry + 1);
                response = client().executeLogstoreSql(
                        project, logstore, from, to, sql, POWER_SQL);
            }
            if (!response.IsCompleted()) {
                log.error("SLS 结果重试 {} 次仍不完整（已扫 {} 行），本轮计数会偏小 | sql={}",
                        INCOMPLETE_RETRIES, response.getProcessedRow(), abbreviate(sql));
            }
            List<Map<String, String>> rows = new ArrayList<>();
            if (response.GetLogs() != null) {
                for (QueriedLog queried : response.GetLogs()) {
                    rows.add(toRowMap(queried));
                }
            }
            log.debug("SLS 查询完成 rows={} 耗时={}ms", rows.size(), System.currentTimeMillis() - began);
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException(explain(e) + " | sql=" + abbreviate(sql), e);
        }
    }

    /**
     * 把 SLS 常见异常翻译成可执行的排查方向。
     *
     * <p>401 最容易被误判为「配置写错」，实际上 AK 白名单是按 AK 独立配置的，
     * 新服务上线时出口 IP 往往还没加进白名单。
     */
    private String explain(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("401") || msg.contains("Unauthorized") || msg.contains("SignatureNotMatch")) {
            return "SLS 鉴权失败(401)：请先确认【本服务的出口 IP 是否已加入该 AK 的白名单】"
                    + "（AK 白名单按 AK 独立配置），再检查 AK/SK 是否正确";
        }
        if (msg.contains("ParameterInvalid") && msg.contains("not config as key value config")) {
            return "SLS 字段未建索引：注意级别字段是 log_level 而不是 level";
        }
        if (msg.contains("timeout") || msg.contains("Timeout")) {
            return "SLS 查询超时：检查网络与 endpoint（同 region 应使用 *-intranet 内网地址）";
        }
        return "SLS 查询失败: " + msg;
    }

    /**
     * key 统一转小写。SDK 返回的字段名大小写不保证一致，
     * 而聚合 SQL 的别名全是小写，转小写后取值才稳定。
     */
    private Map<String, String> toRowMap(QueriedLog queriedLog) {
        Map<String, String> row = new HashMap<>();
        if (queriedLog == null || queriedLog.GetLogItem() == null) {
            return row;
        }
        for (LogContent content : queriedLog.GetLogItem().GetLogContents()) {
            row.put(content.mKey == null ? "" : content.mKey.toLowerCase(Locale.ROOT), content.mValue);
        }
        return row;
    }

    /** 跨服务合并时用来判断是不是多服务，取不到就当 null（未知） */
    private Integer parseIntSafe(String v) {
        if (v == null || v.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long toLong(String v) {
        if (v == null || v.trim().isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String abbreviate(String sql) {
        return sql.length() > 200 ? sql.substring(0, 200) + "..." : sql;
    }
}
