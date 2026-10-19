package io.github.liufor02144.erroragent.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一轮采集的结果摘要。用于日志输出与手动触发接口返回。
 */
@Data
public class CollectResult {

    private String batchId;

    /** 采集窗口 */
    private String window;

    /** SLS 返回的聚合行数（指纹 × 小时） */
    private int slsRows;

    /** 去重后的指纹数 */
    private int fingerprints;

    /** 不在异常库，每次出现都要报 */
    private int newCount;

    /** 在异常库且量级正常，允许发生，不报 */
    private int quietCount;

    /** 在异常库但突增 */
    private int surgeCount;

    /** 命中异常库的指纹数 */
    private int inLibraryCount;

    /** 匹配到模版的指纹数 */
    private int matchedCount;

    /** 没匹配上模版、待抽取的指纹数 */
    private int unmatchedCount;

    /** 本轮新抽出的模版数 */
    private int templateExtracted;

    /** 本轮产生或累计的异常事件数 */
    private int eventCount;

    /** 过量级门槛、待送 AI 分析 */
    private int needAiCount;

    /** 写入的时序统计行数 */
    private int statRows;

    /** 拉取的样本数 */
    private int sampleCount;

    /** 本窗口内日志总条数（各指纹 cnt 之和） */
    private long totalLogs;

    private long elapsedMs;

    private boolean success = true;

    private String message;

    /** 命中的不同模版数 */
    private int templateCount;

    /** 所属巡检记录，事件明细据此归属。未落记录时为 null */
    private Long runId;

    /** 新增指纹的 fpKey，便于日志直接看出是什么 */
    private List<String> newFpKeys = new ArrayList<>();

    public String brief() {
        return String.format(
                "batch=%s window=%s SLS行=%d 指纹=%d(新增%d/突增%d/业务预期%d) "
                        + "模版匹配=%d/未匹配=%d/新抽=%d 事件=%d 待AI=%d stat=%d 样本=%d 日志=%d 耗时=%dms",
                batchId, window, slsRows, fingerprints, newCount, surgeCount, quietCount,
                matchedCount, unmatchedCount, templateExtracted,
                eventCount, needAiCount, statRows, sampleCount, totalLogs, elapsedMs);
    }
}
