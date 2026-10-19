package io.github.liufor02144.erroragent.job;

import io.github.liufor02144.erroragent.config.InspectSwitch;
import io.github.liufor02144.erroragent.service.push.InspectEventPushService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 卡片推送任务。
 *
 * <p>跟在分析任务之后跑：没有 AI 分析结论就没有严重度，也就无从判断该不该推。
 *
 * <p>定时与手动巡检后的触发共用「执行中」标记，避免同一张卡片被推两次。
 */
@Slf4j
@Component
public class InspectPushJob {

    private final AtomicBoolean localRunning = new AtomicBoolean(false);

    @Resource
    private InspectSwitch inspectSwitch;

    @Resource
    private InspectEventPushService eventPushService;

    @Scheduled(fixedDelay = 120_000L, initialDelay = 150_000L)
    public void tick() {
        if (!inspectSwitch.pushAllowed()) {
            return;
        }
        if (!localRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            run();
        } catch (Exception e) {
            log.error("卡片推送任务异常", e);
        } finally {
            localRunning.set(false);
        }
    }

    private void run() {
        int pushed = eventPushService.scanAndPush();
        if (pushed > 0) {
            log.info("【巡检推送任务】本轮推送 {} 条异常事件（含升级重推）", pushed);
        }
    }
}
