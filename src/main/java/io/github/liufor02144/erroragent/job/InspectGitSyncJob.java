package io.github.liufor02144.erroragent.job;

import io.github.liufor02144.erroragent.config.InspectProperties;
import io.github.liufor02144.erroragent.entity.InspectProject;
import io.github.liufor02144.erroragent.service.code.GitCommandRunner;
import io.github.liufor02144.erroragent.service.code.GitSyncService;
import io.github.liufor02144.erroragent.service.code.InspectProjectService;
import io.github.liufor02144.erroragent.service.code.JavaSourceIndex;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * 代码仓库同步任务。
 */
@Slf4j
@Component
public class InspectGitSyncJob {

    @Resource
    private InspectProperties properties;

    @Resource
    private InspectProjectService projectService;

    @Resource
    private GitSyncService gitSyncService;

    @Resource
    private JavaSourceIndex sourceIndex;

    @Resource
    private GitCommandRunner gitRunner;

    /**
     * 启动后异步补齐代码仓库。
     *
     * <p>两个注解缺一不可，且必须<b>同时挂在这个方法上</b>：
     * <ul>
     *   <li>{@code @EventListener} —— 由 Spring 容器调用，走代理。
     *       若像原先那样在 {@code @PostConstruct} 方法里写 {@code this.asyncInitClone()}，
     *       同类内部调用不经过代理，{@code @Async} 会<b>静默失效</b>变成同步执行</li>
     *   <li>{@code @Async} —— 克隆二十来个仓库要几分钟。同步执行会阻塞启动，
     *       容器环境下启动探针先超时、Pod 被判失败重启，然后重新开始克隆，陷入循环</li>
     * </ul>
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void asyncInitClone() {
        if (!gitRunner.gitAvailable()) {
            log.error("系统未安装 git，代码定位将不可用。请在镜像中安装 git 或改用其他实现");
            return;
        }
        if (!properties.getGit().ready()) {
            log.warn("Git 配置不完整，跳过代码仓库初始化");
            return;
        }
        try {
            List<InspectProject> projects = projectService.listEnabled();
            if (projects.isEmpty()) {
                log.warn("未登记任何代码仓库，代码定位不可用。可通过 POST /inspect/project 登记");
                return;
            }
            int missing = 0;
            for (InspectProject p : projects) {
                if (!gitSyncService.cloned(p)) {
                    missing++;
                }
            }
            if (missing == 0) {
                log.info("代码仓库已就绪 {} 个", projects.size());
                return;
            }
            log.info("检测到 {} 个仓库尚未克隆，开始初始化（约需数分钟）", missing);
            syncAllInternal();
        } catch (Exception e) {
            log.error("代码仓库初始化失败", e);
        }
    }

    /** 每日同步。cron 由配置文件/环境变量（erroragent.git.sync-cron）指定，默认凌晨 3 点 */
    @Scheduled(cron = "${erroragent.git.sync-cron:0 0 3 * * ?}")
    public void dailySync() {
        if (!properties.getGit().ready()) {
            return;
        }
        syncAllInternal();
    }

    private void syncAllInternal() {
        List<InspectProject> projects = projectService.listEnabled();
        int ok = 0;
        for (InspectProject p : projects) {
            if (gitSyncService.sync(p)) {
                ok++;
                // 代码已更新，索引必须失效，否则定位会停留在旧文件列表
                sourceIndex.invalidate(gitSyncService.repoDir(p));
            }
        }
        log.info("代码仓库同步完成 成功 {}/{}", ok, projects.size());
    }
}
