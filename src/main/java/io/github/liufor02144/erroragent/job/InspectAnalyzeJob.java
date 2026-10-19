package io.github.liufor02144.erroragent.job;

import io.github.liufor02144.erroragent.config.InspectSwitch;
import io.github.liufor02144.erroragent.entity.InspectEvent;
import io.github.liufor02144.erroragent.mapper.InspectEventMapper;
import io.github.liufor02144.erroragent.service.InspectConfigKeys;
import io.github.liufor02144.erroragent.service.InspectConfigService;
import io.github.liufor02144.erroragent.service.analyze.InspectAnalyzeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 分析任务。
 *
 * <p>与采集拆成两个任务而不是串在一起：采集要在秒级完成好让下一轮准时开始，
 * 而单次 LLM 调用就要数秒，几十个候选串行下来是分钟级。
 * 拆开后慢的那部分不会拖住快的那部分。
 */
@Slf4j
@Component
public class InspectAnalyzeJob {

    /** 单轮最多处理的候选数，避免一次占满当日额度 */
    private static final int BATCH_LIMIT = 20;

    private final AtomicBoolean localRunning = new AtomicBoolean(false);

    @Resource
    private InspectSwitch inspectSwitch;

    @Resource
    private InspectConfigService configService;

    @Resource
    private InspectEventMapper eventMapper;

    @Resource
    private InspectAnalyzeService analyzeService;

    @Resource
    private io.github.liufor02144.erroragent.service.run.InspectRunRecorder runRecorder;

    @Scheduled(fixedDelay = 120_000L, initialDelay = 90_000L)
    public void tick() {
        if (!inspectSwitch.llmAllowed()) {
            return;
        }
        if (!localRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            run();
        } catch (Exception e) {
            log.error("AI 分析任务异常", e);
        } finally {
            localRunning.set(false);
        }
    }

    private void run() {
        // 按事件维度限流：同一件事持续存在时，不该每 5 分钟问一遍模型。
        // 这里刻意不再按次数筛 —— 异常库已经是第一道过滤器，
        // 能成为事件的都值得分析一次，「每小时 1 次连续 5 小时」的 Redis 超时同样要拿到严重度
        int intervalHours = configService.getInt(InspectConfigKeys.EVENT_AI_INTERVAL_HOURS, 1);
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR_OF_DAY, -intervalHours);

        // 间隔到了也只挑有实质变化的：量级涨到上次的 N 倍，或扩散到新服务。
        // 实测每小时无条件重分析时，56% 的调用是在把同样的结论再生成一遍
        int growth = Math.max(2, configService.getInt(InspectConfigKeys.EVENT_AI_REANALYZE_GROWTH, 3));
        List<InspectEvent> pending =
                eventMapper.selectPendingAnalysis(cal.getTime(), growth, BATCH_LIMIT,
                        configService.getBool(InspectConfigKeys.JUDGE_SURGE_ENABLED, false));
        if (pending == null || pending.isEmpty()) {
            return;
        }

        int analyzed = 0;
        int failed = 0;
        for (InspectEvent event : pending) {
            // 先打限流标记再分析：分析失败也要占住这一小时，
            // 否则一个稳定失败的事件会每轮重试，把额度耗光
            eventMapper.markAnalyzed(event.getId(), new Date());
            Date since = new Date();
            try {
                // force=true 跳过指纹级的 24 小时幂等（事件级限流已经管住了节奏），
                // 但日上限仍然生效 —— 那道闸门在 analyze 内部独立于 force 判断
                // 突增事件要把「突增」和判定依据带给 AI：从库里重建只能拿到指纹最近一次的状态，
                // 突增一回落就成了「业务预期内」，AI 会按正常业务拒绝去定级
                io.github.liufor02144.erroragent.dto.JudgeResult judge = null;
                if (InspectEvent.TYPE_SURGE.equals(event.getEventType())) {
                    judge = new io.github.liufor02144.erroragent.dto.JudgeResult();
                    judge.setFpId(event.getFpId());
                    judge.setJudgeState(io.github.liufor02144.erroragent.dto.JudgeResult.SURGE);
                    judge.setReason(event.getTriggerReason());
                }
                io.github.liufor02144.erroragent.entity.InspectAnalysis result =
                        analyzeService.analyze(event.getFpId(), judge, true);
                if (result != null) {
                    analyzed++;
                    // 只在成功时打快照：失败的保持「没分析成功过」，按间隔重试，
                    // 否则它永远拿不到定级、永远推不出去
                    eventMapper.markAnalyzedSnapshot(event.getId(),
                            event.getTotalCount(), event.getAppCount());
                } else {
                    failed++;
                }
                runRecorder.analyze(event, result, null, since);
            } catch (Exception e) {
                failed++;
                log.warn("事件分析失败 eventId={} fpId={}：{}",
                        event.getId(), event.getFpId(), e.getMessage());
                runRecorder.analyze(event, null, e.getMessage(), since);
            }
        }
        if (analyzed > 0 || failed > 0) {
            log.info("【AI 分析任务】候选事件={} 已分析={} 未产出={}"
                            + "（同一事件至少间隔 {} 小时，且量级涨到 {} 倍或扩散到新服务才重新分析）",
                    pending.size(), analyzed, failed, intervalHours, growth);
        }
    }
}
