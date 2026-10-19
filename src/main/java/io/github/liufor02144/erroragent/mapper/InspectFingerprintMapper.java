package io.github.liufor02144.erroragent.mapper;

import io.github.liufor02144.erroragent.entity.InspectFingerprint;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 指纹库数据访问。
 *
 * <p>采集一轮会产生上千个指纹（1 小时窗口实测 1068 个），因此：
 * 先用 {@link #selectByHashes} 一条 SQL 查出已存在的（据此判定 NEW），
 * 再用 {@link #batchUpsert} 一条 SQL 完成插入与累加，避免 N+1。
 */
public interface InspectFingerprintMapper {

    /** 按指纹哈希批量查询，用于判定哪些是新增 */
    List<InspectFingerprint> selectByHashes(@Param("hashes") List<String> hashes);

    /** 任取一条指纹的 id，库为空时返回 null。用于判断是否首次部署（冷启动） */
    Long selectAnyId();

    InspectFingerprint selectByHash(@Param("fpHash") String fpHash);

    InspectFingerprint selectById(@Param("id") Long id);

    /**
     * 批量插入或累加。
     * first_seen_time 不在 ON DUPLICATE KEY UPDATE 列表内，因此已存在的指纹保留原首现时间。
     */
    int batchUpsert(@Param("list") List<InspectFingerprint> list);

    /**
     * 从 stat 表汇总回填真实累计次数。
     * 采集窗口重叠使得「累加」不可用，只能按 stat 的覆盖值重新汇总。
     */
    int syncTotalCount(@Param("ids") List<Long> ids);

    /** 更新判定状态 */
    int updateJudgeState(@Param("id") Long id, @Param("judgeState") String judgeState);

    /**
     * 批量修正判定状态。upsert 阶段只能写出粗略的 NEW/KNOWN，
     * SURGE 需要 id 与基线才能判出，故判定完成后再回写一次。
     */
    /** 某模版下的指纹：模版跨服务聚合，详情页要能看到它覆盖了哪些服务与代码位置 */
    List<InspectFingerprint> selectByTemplateId(@Param("templateId") Long templateId,
                                                @Param("limit") int limit);

    /** 还没匹配到模版的指纹，量级大的优先 —— 模版库要先覆盖占日志量最多的那批 */
    List<InspectFingerprint> selectWithoutTemplate(@Param("limit") int limit);

    /** 回写指纹归属的模版，页面上要能从指纹看到它归到了哪个模版 */
    int batchUpdateTemplateId(@Param("items") List<Map<String, Object>> items);

    int batchUpdateJudgeState(@Param("items") List<Map<String, Object>> items);

    /** 批量置状态（抑制 / 归档 / 激活），仅更新与目标状态不同的行 */
    int batchUpdateStatus(@Param("ids") List<Long> ids, @Param("status") String status);

    /** 更新 AI 分析结论 */
    int updateAnalysis(@Param("id") Long id, @Param("category") String category,
                       @Param("severity") String severity);

    /** 更新状态（抑制 / 归档 / 激活） */
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /** 标记已人工处理 */
    int markHandled(@Param("id") Long id);

    /** 记录推送 */
    int markPushed(@Param("id") Long id, @Param("pushedTime") Date pushedTime,
                   @Param("messageId") String messageId);

    /**
     * 待 AI 分析的候选：判定为新增/突增/复发、未被抑制、且指定时间内尚无分析记录。
     *
     * <p>量级门槛不在 SQL 里判断 —— 它依赖 stat 表的 24 小时汇总，
     * 放进来会让这条查询变成大范围聚合。候选数本就不多，由调用方逐个核对更划算。
     */
    List<InspectFingerprint> selectPendingAnalysis(@Param("since") Date since,
                                                   @Param("limit") int limit);

    /**
     * 待推送：已完成 AI 分析、尚未人工标注、且在幂等窗口外的活跃指纹。
     *
     * <p>严重度门槛不在 SQL 里判，放在服务层——核心链路要放宽一档，
     * 而「核心链路」是可配的，写进 SQL 会让规则散落两处。
     */
    List<InspectFingerprint> selectPendingPush(@Param("dedupBefore") Date dedupBefore,
                                               @Param("limit") int limit);

    /**
     * 历史首次出现在指定时间之后的指纹数，即「新增」。
     * 与按 last_seen_time 过滤不同 —— 那查的是「这段时间出现过的」，
     * 一个存在半年的老异常今天又报一次也会被算进去。
     */
    long countNewSince(@Param("since") Date since);

    /** 实际采集到的服务列表，供页面下拉选择（避免手打服务名） */
    List<Map<String, Object>> selectAppOptions();

    /** 当日已推送张数，用于日上限熔断 */
    long countPushedSince(@Param("since") Date since);

    /**
     * 列表页查询。
     *
     * @param orderBy severity(默认，P0 优先) / recent(最近出现) / count(累计次数)
     */
    List<InspectFingerprint> selectByCondition(@Param("appId") String appId,
                                               @Param("status") String status,
                                               @Param("judgeStates") List<String> judgeStates,
                                               @Param("category") String category,
                                               @Param("handled") Integer handled,
                                               @Param("keyword") String keyword,
                                               @Param("pushedOnly") Boolean pushedOnly,
                                               @Param("startTime") Date startTime,
                                               @Param("endTime") Date endTime,
                                               @Param("orderBy") String orderBy,
                                               @Param("offset") int offset,
                                               @Param("limit") int limit);

    long countByCondition(@Param("appId") String appId,
                          @Param("status") String status,
                          @Param("judgeStates") List<String> judgeStates,
                          @Param("category") String category,
                          @Param("handled") Integer handled,
                          @Param("keyword") String keyword,
                          @Param("pushedOnly") Boolean pushedOnly,
                          @Param("startTime") Date startTime,
                          @Param("endTime") Date endTime);
}
