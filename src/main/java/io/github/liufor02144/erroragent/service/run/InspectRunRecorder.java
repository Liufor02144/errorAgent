package io.github.liufor02144.erroragent.service.run;

import io.github.liufor02144.erroragent.dto.CollectResult;
import io.github.liufor02144.erroragent.entity.InspectAnalysis;
import io.github.liufor02144.erroragent.entity.InspectEvent;
import io.github.liufor02144.erroragent.entity.InspectRun;
import io.github.liufor02144.erroragent.entity.InspectRunItem;
import io.github.liufor02144.erroragent.mapper.InspectLlmUsageMapper;
import io.github.liufor02144.erroragent.mapper.InspectRunItemMapper;
import io.github.liufor02144.erroragent.mapper.InspectRunMapper;
import io.github.liufor02144.erroragent.service.InspectConfigKeys;
import io.github.liufor02144.erroragent.service.InspectConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;

/**
 * 巡检记录的落库。
 *
 * <p>每轮采集开一条记录；AI 分析和推送是独立的定时任务，它们的动作记成明细，
 * 归入<b>当前最近的一轮</b>。
 *
 * <p><b>这里的任何失败都不能影响巡检本身</b> —— 记录是给人回看的，
 * 记不上顶多少一行，而采集、分析、推送断了就是漏报。所以每个入口都吞异常。
 */
@Slf4j
@Service
public class InspectRunRecorder {

    @Resource
    private InspectRunMapper runMapper;

    @Resource
    private InspectRunItemMapper itemMapper;

    @Resource
    private InspectLlmUsageMapper usageMapper;

    @Resource
    private InspectConfigService configService;

    /** 与采集锁的僵死判定一致：超过这个时长还在进行中，说明进程中途退出了 */
    private static final long STALE_RUNNING_MS = 10 * 60 * 1000L;

    /** 本节点最近开的一轮。重启后为空，退回查库 */
    private volatile Long currentRunId;

    /** 开一轮。返回 null 表示没记上，调用方照常采集 */
    public Long start(String batchId, boolean manual) {
        try {
            int closed = runMapper.closeStaleRunning(new Date(System.currentTimeMillis() - STALE_RUNNING_MS));
            if (closed > 0) {
                log.warn("【巡检记录】{} 条记录停在进行中超过 10 分钟，判定进程中途退出，已标为失败", closed);
            }
        } catch (Exception e) {
            log.debug("收口僵死巡检记录失败：{}", e.getMessage());
        }
        try {
            InspectRun run = new InspectRun();
            run.setBatchId(batchId);
            run.setStatus(InspectRun.RUNNING);
            run.setTriggerType(manual ? "MANUAL" : "AUTO");
            run.setStartTime(new Date());
            runMapper.insert(run);
            currentRunId = run.getId();
            return run.getId();
        } catch (Exception e) {
            log.warn("写巡检记录失败，不影响本轮采集：{}", e.getMessage());
            return null;
        }
    }

    /** 采集结束，回填统计并顺带清理过期记录 */
    public void finish(Long runId, CollectResult r) {
        if (runId == null) {
            return;
        }
        try {
            InspectRun run = new InspectRun();
            run.setId(runId);
            run.setStatus(r.isSuccess() ? InspectRun.SUCCESS : InspectRun.FAILED);
            run.setWindowText(r.getWindow());
            run.setTotalLogs(r.getTotalLogs());
            run.setSlsRows(r.getSlsRows());
            run.setFingerprints(r.getFingerprints());
            run.setNewCount(r.getNewCount());
            run.setSurgeCount(r.getSurgeCount());
            run.setQuietCount(r.getQuietCount());
            run.setTemplateCount(r.getTemplateCount());
            run.setMatchedCount(r.getMatchedCount());
            run.setUnmatchedCount(r.getUnmatchedCount());
            run.setTemplateExtracted(r.getTemplateExtracted());
            run.setEventCount(r.getEventCount());
            run.setElapsedMs(r.getElapsedMs());
            run.setMessage(truncate(r.getMessage(), 1000));
            run.setEndTime(new Date());
            runMapper.updateFinish(run);
        } catch (Exception e) {
            log.warn("回填巡检记录失败 runId={}：{}", runId, e.getMessage());
        }
        cleanup();
    }

    /** 采集阶段：新建或累计了一条事件 */
    public void event(Long runId, InspectEvent event) {
        if (runId == null || event == null) {
            return;
        }
        InspectRunItem item = base(runId, InspectRunItem.STAGE_EVENT, event);
        item.setResult(event.isJustCreated() ? "NEW" : "ACCUMULATE");
        item.setDetail(truncate(event.getTriggerReason(), 1000));
        save(item);
    }

    /**
     * 分析阶段：一次 AI 分析。
     *
     * @param since 分析开始时刻，据此从用量表取这次调用的 token
     */
    public void analyze(InspectEvent event, InspectAnalysis analysis, String error, Date since) {
        InspectRunItem item = base(currentRunId(), InspectRunItem.STAGE_ANALYZE, event);
        if (item.getRunId() == null) {
            return;
        }
        item.setResult(analysis == null ? "FAIL" : "OK");
        if (analysis != null) {
            item.setSeverity(analysis.getSeverity());
            item.setDetail(truncate(join(analysis.getCategory(), analysis.getRootCause()), 1000));
        } else {
            item.setDetail(truncate(error == null ? "未产出分析结果" : error, 1000));
        }
        fillTokens(item, event.getFpId(), since);
        save(item);
    }

    /** 推送阶段：卡片发出（或发送失败） */
    public void push(InspectEvent event, String cardJson, String messageId, String error) {
        InspectRunItem item = base(currentRunId(), InspectRunItem.STAGE_PUSH, event);
        if (item.getRunId() == null) {
            return;
        }
        boolean ok = error == null;
        item.setResult(ok ? "SENT" : "FAIL");
        int n = event.getPushCount() == null ? 0 : event.getPushCount();
        item.setDetail(ok ? (n == 0 ? "首次推送" : "第 " + (n + 1) + " 次提醒") : truncate(error, 1000));
        item.setMessageId(messageId);
        item.setCardJson(cardJson);
        save(item);
    }

    /**
     * 推送阶段：满足推送时机、但被闸门拦下（未定级、判为业务预期、日上限）。
     *
     * <p>推送任务每 2 分钟扫一遍，同一事件同一原因一轮只记一次，否则明细会被刷屏。
     * 「还没到重推时间」这种常态不记 —— 那不是被拦下，只是不需要推。
     */
    public void pushSkipped(InspectEvent event, String reason) {
        Long runId = currentRunId();
        if (runId == null || event == null) {
            return;
        }
        try {
            if (itemMapper.countSkip(runId, event.getId()) > 0) {
                return;
            }
        } catch (Exception e) {
            return;
        }
        InspectRunItem item = base(runId, InspectRunItem.STAGE_PUSH, event);
        item.setResult("SKIP");
        item.setDetail(truncate(reason, 1000));
        save(item);
    }

    private Long currentRunId() {
        Long id = currentRunId;
        if (id != null) {
            return id;
        }
        try {
            id = runMapper.selectLatestId();
            currentRunId = id;
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    private InspectRunItem base(Long runId, String stage, InspectEvent event) {
        InspectRunItem item = new InspectRunItem();
        item.setRunId(runId);
        item.setStage(stage);
        if (event != null) {
            item.setEventId(event.getId());
            item.setFpId(event.getFpId());
            item.setTitle(truncate(event.getHeadline(), 500));
            item.setSeverity(event.getSeverity());
        }
        return item;
    }

    private void fillTokens(InspectRunItem item, Long fpId, Date since) {
        if (fpId == null || since == null) {
            return;
        }
        try {
            Map<String, Object> m = usageMapper.sumByFpSince(fpId, since);
            if (m != null) {
                item.setInputTokens(toInt(m.get("inputTokens")));
                item.setOutputTokens(toInt(m.get("outputTokens")));
            }
        } catch (Exception e) {
            log.debug("取分析 token 失败 fpId={}：{}", fpId, e.getMessage());
        }
    }

    private void save(InspectRunItem item) {
        try {
            itemMapper.insert(item);
        } catch (Exception e) {
            log.warn("写巡检明细失败 stage={} eventId={}：{}", item.getStage(), item.getEventId(), e.getMessage());
        }
    }

    private void cleanup() {
        try {
            int days = Math.max(1, configService.getInt(InspectConfigKeys.RUN_RETAIN_DAYS, 7));
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, -days);
            itemMapper.deleteBefore(cal.getTime());
            runMapper.deleteBefore(cal.getTime());
        } catch (Exception e) {
            log.warn("清理过期巡检记录失败：{}", e.getMessage());
        }
    }

    private static int toInt(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    private static String join(String a, String b) {
        if (a == null || a.isEmpty()) {
            return b;
        }
        return b == null || b.isEmpty() ? a : a + "｜" + b;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
