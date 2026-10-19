package io.github.liufor02144.erroragent.job;

import io.github.liufor02144.erroragent.config.InspectSwitch;
import io.github.liufor02144.erroragent.service.InspectConfigKeys;
import io.github.liufor02144.erroragent.service.InspectConfigService;
import io.github.liufor02144.erroragent.service.collect.InspectCollectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 采集定时任务。
 *
 * <p>并发控制目标：<b>每个采集周期至多执行一次；若上一轮尚未结束，本轮直接放弃、等下一个周期。</b>
 * 只支持单实例部署，用内存中的「执行中」标记和「上次启动时刻」实现。
 */
@Slf4j
@Component
public class InspectCollectJob {

    /** 本实例是否有采集在跑（定时与手动共用） */
    private final AtomicBoolean localRunning = new AtomicBoolean(false);

    /** 上一轮启动时刻，用于控制采集周期；手动巡检结束时重置 */
    private volatile long lastStartMs = 0L;

    @Resource
    private InspectSwitch inspectSwitch;

    @Resource
    private InspectConfigService configService;

    @Resource
    private InspectCollectService collectService;

    /**
     * 每分钟探测一次，实际执行周期由配置决定。
     *
     * <p>不用 {@code fixedDelayString} 直接设成 5 分钟，是因为周期需要页面可调、即时生效；
     * 而 {@code @Scheduled} 的表达式只在启动时解析一次。
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void tick() {
        if (!inspectSwitch.collectAllowed()) {
            return;
        }
        if (localRunning.get()) {
            log.debug("上一轮采集尚未结束，跳过本次");
            return;
        }
        int intervalSeconds = configService.getInt(InspectConfigKeys.COLLECT_SCAN_INTERVAL_SECONDS, 300);
        if (System.currentTimeMillis() - lastStartMs < Math.max(intervalSeconds, 60) * 1000L) {
            return;
        }
        if (!localRunning.compareAndSet(false, true)) {
            return;
        }
        lastStartMs = System.currentTimeMillis();
        try {
            collectService.collectOnce(false);
        } catch (Exception e) {
            log.error("巡检采集任务异常", e);
        } finally {
            localRunning.set(false);
        }
    }

    @Resource
    private InspectAnalyzeJob analyzeJob;

    @Resource
    private InspectPushJob pushJob;

    /** 手动巡检在后台跑：一轮要几十秒，同步等会让页面请求超时 */
    private final java.util.concurrent.ExecutorService manualExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "inspect-manual");
                t.setDaemon(true);
                return t;
            });

    /**
     * 立即巡检：采集一轮，接着分析、推送各跑一次。
     *
     * <p>和定时采集共用同一个「执行中」标记，不会两轮并发；跑完后重置间隔计时，
     * 下一次定时采集从现在起再等一个周期，避免手动刚跑完、定时又紧跟着跑一轮。
     * 不看总开关 —— 手动触发就是要验证的；分析与推送仍各自受 AI、推送开关管控。
     *
     * @return null 表示已开始；否则是不能开始的原因
     */
    public String triggerNow() {
        if (!localRunning.compareAndSet(false, true)) {
            return "正在巡检中，请等这一轮结束后再试";
        }
        manualExecutor.submit(() -> {
            try {
                collectService.collectOnce(true);
            } catch (Exception e) {
                log.error("手动巡检采集异常", e);
            } finally {
                localRunning.set(false);
                lastStartMs = System.currentTimeMillis();
            }
            // 采集产出的事件不必等下一个 2 分钟周期
            try {
                analyzeJob.tick();
                pushJob.tick();
            } catch (Exception e) {
                log.warn("手动巡检后的分析或推送异常：{}", e.getMessage());
            }
        });
        log.info("【手动巡检】已开始");
        return null;
    }
}
