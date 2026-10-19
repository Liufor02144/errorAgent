package io.github.liufor02144.erroragent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 巡检急停开关。
 *
 * <p>为什么单独一个 bean 而不并入 {@link InspectProperties}：
 * 接入配置中心时，{@code @Value} 注入的字段通常可直接热更新，而 {@code @ConfigurationProperties}
 * 需要 RefreshScope 才能刷新。急停能力必须实时生效，不能等重启。
 *
 * <p>为什么不做成页面开关：DB 挂了页面就改不了，而急停能力不能依赖被急停的系统本身。
 *
 * <p>变更审计由配置文件/环境变量所在的发布流程承担，此处不重复记录。
 */
@Slf4j
@Component
public class InspectSwitch {

    /** 总开关：关闭后采集、分析、推送全部停止 */
    @Value("${erroragent.enabled:false}")
    private volatile boolean enabled;

    /** AI 分析开关：关闭后只采集建指纹库，不调 LLM */
    @Value("${erroragent.llm.enabled:false}")
    private volatile boolean llmEnabled;

    /** 飞书推送开关：关闭后结果只入库，不推卡片 */
    @Value("${erroragent.push.enabled:false}")
    private volatile boolean pushEnabled;

    /** 影响面分析开关（加分项）：关闭后不查只读库 */

    /** 采集是否允许运行 */
    public boolean collectAllowed() {
        return enabled;
    }

    /** AI 分析是否允许运行（受总开关约束） */
    public boolean llmAllowed() {
        return enabled && llmEnabled;
    }

    /** 飞书推送是否允许（受总开关约束） */
    public boolean pushAllowed() {
        return enabled && pushEnabled;
    }


    /**
     * 说明某项功能为何未启用。
     *
     * <p>子开关开着、总开关关着时，只说「推送开关未开启」会把人引到错误的地方 ——
     * 得指出真正拦住它的是哪一个。
     *
     * @return null 表示允许运行
     */
    public String whyBlocked(String feature) {
        if (!enabled) {
            return "巡检总开关 erroragent.enabled 未开启";
        }
        if ("LLM".equals(feature) && !llmEnabled) {
            return "AI 分析开关 erroragent.llm.enabled 未开启";
        }
        if ("PUSH".equals(feature) && !pushEnabled) {
            return "推送开关 erroragent.push.enabled 未开启";
        }
        return null;
    }

    /**
     * 当前实际生效的开关值。
     *
     * <p>用途是验证「配置改了是否真的生效」—— 环境变量没注入、key 拼错、
     * 配置没下发都会导致配置里看着是新值、应用里还是旧值。
     */
    public String describe() {
        return String.format("enabled=%s, llm=%s, push=%s",
                enabled, llmEnabled, pushEnabled);
    }
}
