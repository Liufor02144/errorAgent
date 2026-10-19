package io.github.liufor02144.erroragent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/**
 * 启动自检：打印巡检模块的开关与配置就绪状态。
 *
 * <p>存在的理由：这类系统最常见的困惑是「配了但没生效」——可能是环境变量没生效、
 * key 拼错、或开关忘开。启动日志里直接给出结论，比逐个去翻配置快。
 *
 * <p>只输出「是否已配置」，不打印凭据值。
 */
@Slf4j
@Component
public class InspectStartupReporter {

    @Resource
    private InspectProperties properties;

    @Resource
    private InspectSwitch inspectSwitch;

    @Resource
    private io.github.liufor02144.erroragent.service.code.GitCommandRunner gitRunner;

    @PostConstruct
    public void report() {
        log.info("========== AI 异常巡检 · 启动自检 ==========");
        log.info("开关: {}", inspectSwitch.describe());

        InspectProperties.Naming naming = properties.getNaming();
        log.info("业务包: {} | appPackages={}", mark(naming.ready()), naming.packageList());

        InspectProperties.Sls sls = properties.getSls();
        log.info("SLS : {} | endpoint={} project={} logstore={} 采集范围={}{}",
                mark(sls.ready()),
                nvl(sls.getEndpoint()), nvl(sls.getProject()), nvl(sls.getLogstore()),
                nvl(sls.getContainerPrefix()) + "*",
                sls.extraAppIdList().isEmpty() ? "" : " + " + sls.extraAppIdList());
        if (sls.ready() && sls.getEndpoint() != null && !sls.getEndpoint().contains("intranet")) {
            log.warn("SLS endpoint 未使用内网地址，读流量将按公网计费；若服务与 SLS 同 region，建议改为 *-intranet.log.aliyuncs.com");
        }

        InspectProperties.Git git = properties.getGit();
        // 配置齐了不等于能用：镜像里没装 git 时这里原先照样打「就绪」，
        // 实测测试环境连续 5 次部署都是这样，卡片上的代码片段全部消失才被发现
        boolean gitCmd = gitRunner.gitAvailable();
        log.info("Git : {} | baseUrl={} localPath={} depth={} cron={}",
                git.ready() && gitCmd ? "就绪" : (gitCmd ? "未配置" : "不可用（镜像里没有 git 命令，代码定位将全部失效）"),
                nvl(git.getBaseUrl()), nvl(git.getLocalPath()),
                git.getDepth(), nvl(git.getSyncCron()));

        log.info("LLM : {} | model={}", mark(properties.getLlm().ready()), nvl(properties.getLlm().getModel()));
        log.info("推送: {} | chatId={}", mark(properties.getPush().ready()), nvl(properties.getPush().getChatId()));

        warnIfSwitchOnButNotReady();
        log.info("==========================================");
    }

    /**
     * 开关开了但配置没就绪 —— 这是最危险的组合：任务会跑但每次都失败，
     * 而失败日志混在正常日志里很容易被忽略。启动时直接报出来。
     */
    private void warnIfSwitchOnButNotReady() {
        if (inspectSwitch.collectAllowed() && !properties.getSls().ready()) {
            log.error("巡检总开关已开启，但 SLS 配置不完整，采集将持续失败。请检查 erroragent.sls.* 配置");
        }
        if (inspectSwitch.collectAllowed() && !properties.getNaming().ready()) {
            log.warn("未配置 erroragent.naming.app-packages：识别不出业务栈帧，"
                    + "有堆栈的异常将按 logger 与消息模版聚合，代码定位只能靠 logger 名");
        }
        if (inspectSwitch.llmAllowed() && !properties.getLlm().ready()) {
            log.error("AI 分析开关已开启，但 LLM 配置不完整，分析将持续失败。请检查 erroragent.llm.* 配置");
        }
        if (inspectSwitch.pushAllowed() && !properties.getPush().ready()) {
            log.error("飞书推送开关已开启，但推送配置不完整，告警将无法送达。请检查 erroragent.push.* 配置");
        }
    }

    private String mark(boolean ready) {
        return ready ? "就绪" : "未配置";
    }

    private String nvl(String v) {
        return (v == null || v.trim().isEmpty()) ? "-" : v;
    }
}
