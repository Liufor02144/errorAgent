package io.github.liufor02144.erroragent.mapper;

import io.github.liufor02144.erroragent.entity.InspectEvent;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 异常事件。
 */
public interface InspectEventMapper {

    /**
     * 查某指纹当前未处理的事件。
     *
     * <p>这是「持续发生不新建事件、而是累计升级」的实现基础：
     * 同一个 Redis 超时连报 3 小时应该是<b>一条持续 3 小时的事件</b>，
     * 不是 36 条互相独立的事件。
     */
    InspectEvent selectOpenByFpId(@Param("fpId") Long fpId);

    /**
     * 这个指纹最近归属的事件，不限是否已处理。
     *
     * <p>给飞书卡片的深链用：人要标注的是事件，卡片却只有 fpId。
     * 先按 fp_id 直接找，找不到再按该指纹的模版找 ——
     * 事件是按模版聚合的，代表指纹未必就是当前这个。
     */
    InspectEvent selectLatestByFpId(@Param("fpId") Long fpId);

    /** 按模版查未处理事件 —— 模版才是事件的聚合键 */
    InspectEvent selectOpenByTemplateId(@Param("templateId") Long templateId);

    /**
     * 按代码位置成事件的查找：模版 + 服务 + 代码位置。
     *
     * @param handled 0 查未处理的；1 查最近一次处理掉的（识别复发）
     */
    InspectEvent selectOpenByLocation(@Param("templateId") Long templateId,
                                      @Param("appId") String appId,
                                      @Param("frame") String frame,
                                      @Param("handled") int handled);

    /** 同一模版（无模版时按指纹）最近一次被关闭的事件，用于识别复发 */
    InspectEvent selectLastHandled(@Param("templateId") Long templateId,
                                   @Param("fpId") Long fpId);

    InspectEvent selectById(@Param("id") Long id);

    int insert(InspectEvent record);

    /** 事件仍在发生：累计条数、刷新最近发生时间，并更新严重度与判定依据 */
    /**
     * 事件仍在发生：直接<b>设定</b>累计量，不做加法。
     *
     * <p>采集窗口是「上一整点到现在」，同一小时每 5 分钟就会被重新采到一次，
     * 用加法累计会把一小时的量重复加十几遍 —— 实测 CACHE_METRIC 的累计
     * 虚高到真实值的 3 倍（60102 vs 21038）。总量只能从 stat 表重算。
     */
    int accumulate(@Param("id") Long id,
                   @Param("totalCount") long totalCount,
                   @Param("lastSeenTime") Date lastSeenTime,
                   @Param("severity") String severity,
                   @Param("triggerReason") String triggerReason,
                   @Param("appCount") Integer appCount,
                   @Param("appList") String appList,
                   @Param("frameList") String frameList);

    int markHandled(@Param("id") Long id,
                    @Param("note") String note,
                    @Param("handler") String handler);

    int markPushed(@Param("id") Long id,
                   @Param("messageId") String messageId,
                   @Param("pushTime") Date pushTime);

    /**
     * 待推送的事件：从未推过、或距上次推送已超过该事件当前档位的重推间隔。
     *
     * <p>间隔随推送次数递增（1h → 2h → 4h → 8h → 24h），
     * 由调用方把档位换算成小时数传进来。
     */
    List<InspectEvent> selectPushable(@Param("minCount") long minCount,
                                      @Param("severities") List<String> severities);

    /**
     * 待分析的事件：从没分析成功过的，或上次分析后有实质变化的。
     *
     * <p>按事件维度限流而不是按次数筛：能成为事件的都值得分析一次，
     * 但同一件事量级没变时，再问一遍模型只会得到同样的结论。
     *
     * @param growth 累计次数涨到上次分析时的几倍才算实质变化
     * @param surgeEnabled 突增判定总开关，关闭时不取突增事件
     */
    List<InspectEvent> selectPendingAnalysis(@Param("before") Date before,
                                             @Param("growth") int growth,
                                             @Param("limit") int limit,
                                             @Param("surgeEnabled") boolean surgeEnabled);

    /** 分析成功后记下当时的量级与服务数 */
    int markAnalyzedSnapshot(@Param("id") Long id, @Param("total") Long total,
                             @Param("appCount") Integer appCount);

    int markAnalyzed(@Param("id") Long id, @Param("analyzeTime") Date analyzeTime);

    /** 列表页 */
    List<InspectEvent> selectList(@Param("handled") Integer handled,
                                  @Param("eventType") String eventType,
                                  @Param("appId") String appId,
                                  @Param("severity") String severity,
                                  @Param("keyword") String keyword,
                                  @Param("since") Date since,
                                  @Param("offset") int offset,
                                  @Param("limit") int limit);

    int countList(@Param("handled") Integer handled,
                  @Param("eventType") String eventType,
                  @Param("appId") String appId,
                  @Param("severity") String severity,
                  @Param("keyword") String keyword,
                  @Param("since") Date since);

    /** 概览卡片：今日新增、未处理事件数 */
    Map<String, Object> summary(@Param("todayStart") Date todayStart);

    /** 服务下拉选项 */
    List<String> selectDistinctApps();

    /**
     * 关闭「事件没有模版、但其来源指纹已经归到模版」的错位事件。
     *
     * <p>事件的 template_id 在创建那一刻就定了。模版库是逐步建起来的，
     * 所以早期事件即使后来指纹匹配上了模版，自己仍挂着 NULL ——
     * 页面上表现为「明明建了模版，事件还显示无模版」，而且同一个模版
     * 会同时存在「无模版的老事件」和「有模版的新事件」，看起来很乱。
     *
     * <p>关掉它们是安全的：异常若仍在发生，下一轮采集会按正确的模版重新聚合。
     */
    int closeStaleWithoutTemplate();

    /**
     * 关闭某个模版下所有未处理的事件。
     *
     * <p>模版被标成业务预期时调用：既然承认「这本来就该发生」，
     * 那已经报出来的那几条就不再是待办了。
     */
    /**
     * 回写定级。
     *
     * <p>指纹侧不产出 severity，只有 AI 分析会给。推送前把它落到事件上，
     * 页面和卡片才有得显示，下一轮也不用再查一次分析表。
     */
    int updateSeverity(@Param("id") Long id, @Param("severity") String severity);

    int closeByTemplateId(@Param("templateId") Long templateId,
                          @Param("note") String note,
                          @Param("handler") String handler);

    /**
     * 兜底清扫：关闭所有「模版已标业务预期、事件却还开着」的事件。
     *
     * <p>标记入口有好几个（模版页、事件页、AI 复核、批量归零），
     * 漏掉任何一处都会留下这种自相矛盾的状态。每轮采集跑一次，自愈。
     */
    int closeByExpectedTemplate();

    /**
     * 自动收敛：连续静默超过阈值的未处理事件自动关闭。
     * 下次再出现时算作新事件，这样「持续时长」才不会无限膨胀失去意义。
     */
    int autoCloseQuiet(@Param("quietBefore") Date quietBefore);

    /** 已合并进另一张卡片：推进推送次数与时间，不记消息ID */
    int markMerged(@Param("id") Long id, @Param("pushTime") Date pushTime);

    /** 最近推过、仍未处理、since 之后还在发生的事件，最多 20 个 */
    List<InspectEvent> selectRecentlyPushedOpen(@Param("since") Date since);
}
