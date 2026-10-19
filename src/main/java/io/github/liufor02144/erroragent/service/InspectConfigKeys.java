package io.github.liufor02144.erroragent.service;

/**
 * 动态配置键常量。集中定义避免字符串散落各处拼错。
 *
 * <p>与 {@code ai_inspect_schema.sql} 中的初始化数据一一对应。
 */
public final class InspectConfigKeys {

    private InspectConfigKeys() {
    }

    // ---------- 采集 ----------
    /** 采集间隔(秒)。窗口本身不可配，固定为「上一整点小时 ~ 现在」 */
    public static final String COLLECT_SCAN_INTERVAL_SECONDS = "collect.scan.interval.seconds";

    // ---------- 判定 ----------
    public static final String JUDGE_BASELINE_DAYS = "judge.baseline.days";
    /** 突增判定总开关。关闭后已确认业务预期的异常一律不报，只报新增（未确认模版） */
    public static final String JUDGE_SURGE_ENABLED = "judge.surge.enabled";
    /** 异常库内条目超过基线多少倍算突增 */
    public static final String JUDGE_SURGE_MULTIPLE = "judge.surge.multiple";
    /** 判突增的最低次数（最近 10 分钟），低于它只算零星波动 */
    public static final String JUDGE_SURGE_MIN_COUNT = "judge.surge.min.count";


    // ---------- 异常模版 ----------
    /** 一次请求让大模型抽多少个模版。逐个抽在 RPM 个位数的限制下要跑几小时 */
    public static final String TEMPLATE_EXTRACT_BATCH_SIZE = "template.extract.batch.size";

    /** 单轮最多抽多少个模版，防止首轮把额度一次性打满 */
    public static final String TEMPLATE_EXTRACT_MAX_PER_ROUND = "template.extract.max.per.round";

    /** 每轮采集后自动 AI 复核多少条模版。0 表示关闭自动复核，只能手工逐条点 */
    public static final String TEMPLATE_REVIEW_MAX_PER_ROUND = "template.review.max.per.round";

    /** 模版正则的最小长度。太短的正则匹配面过宽，会把不同异常吞进同一模版 */
    public static final String TEMPLATE_MATCH_MIN_LENGTH = "template.match.min.length";

    // ---------- 异常事件 ----------
    /**
     * 同一事件多久才允许再送 AI 分析一次。
     *
     * <p>事件持续存在时每 5 分钟都会被扫到，不限流会对同一件事反复提问。
     * 防刷 token 的正确做法是「同一件事不重复问」，而不是「小事不问」——
     * 后者会让每小时 1 次、连续 5 小时的 Redis 超时彻底静默。
     */
    public static final String EVENT_AI_INTERVAL_HOURS = "event.ai.interval.hours";

    /** 事件累计次数涨到上次分析时的几倍才重新分析 */
    public static final String EVENT_AI_REANALYZE_GROWTH = "event.ai.reanalyze.growth";

    /** 抽取模版失败后多少小时内不再重试同一个异常 */
    public static final String TEMPLATE_EXTRACT_RETRY_HOURS = "template.extract.retry.hours";

    /** 巡检记录保留天数。每轮一条，明细含推送卡片原文 */
    public static final String RUN_RETAIN_DAYS = "run.retain.days";

    /** 未处理事件的重推间隔(小时)，递增。持续越久卡片越显眼 */
    public static final String EVENT_REPUSH_HOURS = "event.repush.hours";

    public static final String EVENT_REPUSH_ENABLED = "event.repush.enabled";

    /** 连续静默这么多小时则事件自动关闭，下次再出现算新事件 */
    public static final String EVENT_CLOSE_QUIET_HOURS = "event.close.quiet.hours";

    // ---------- LLM ----------
    public static final String LLM_DAILY_LIMIT = "llm.daily.limit";
    public static final String LLM_DEDUP_HOURS = "llm.dedup.hours";
    public static final String LLM_MAX_INPUT_TOKENS = "llm.max.input.tokens";
    public static final String LLM_TIMEOUT_SECONDS = "llm.timeout.seconds";
    public static final String LLM_STACK_MAX_FRAMES = "llm.stack.max.frames";
    /** 输入单价，单位「厘/千 tokens」。用整数避免小数配置，12 即 ￥0.012/1K */
    public static final String LLM_PRICE_INPUT = "llm.price.input.per1k.milli";
    /** 输出单价，单位「厘/千 tokens」 */
    public static final String LLM_PRICE_OUTPUT = "llm.price.output.per1k.milli";
    /** 采样温度，单位千分之一。1000 即 1.0。部分模型（如 kimi-k3）只接受 1 */
    public static final String LLM_TEMPERATURE = "llm.temperature.milli";
    /** 每分钟请求数上限。超出服务商配额会被 429，须本地先行限流 */
    public static final String LLM_RPM_LIMIT = "llm.rpm.limit";

    public static final String PUSH_SEVERITY_INCLUDE = "push.severity.include";

    /** 推送严重度默认值：P2（次要功能异常）同样要让人知道，只有 P3（无实质影响）不进群 */
    public static final String PUSH_SEVERITY_DEFAULT = "P0,P1,P2";
    public static final String PUSH_DAILY_LIMIT = "push.daily.limit";

    /**
     * 单轮推送上限。
     *
     * <p>日上限只管「新问题」，升级重推不计入 —— 但一批事件同时到达重推点时
     * 会集中涌出：实测开关刚打开那轮一次推了 22 张卡，全是重推。
     * 超出的不丢，顺延到下一轮。
     */
    public static final String PUSH_MAX_PER_ROUND = "push.max.per.round";

    /** 管理页面访问地址，用于卡片「查看详情」按钮；留空则不显示该按钮 */
    public static final String PUSH_DETAIL_BASE_URL = "push.detail.base.url";

    // ---------- 影响面 ----------



}
