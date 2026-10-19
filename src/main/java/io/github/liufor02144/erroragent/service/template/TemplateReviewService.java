package io.github.liufor02144.erroragent.service.template;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.github.liufor02144.erroragent.config.InspectSwitch;
import io.github.liufor02144.erroragent.entity.InspectLibrary;
import io.github.liufor02144.erroragent.mapper.InspectLibraryMapper;
import io.github.liufor02144.erroragent.service.InspectConfigKeys;
import io.github.liufor02144.erroragent.service.InspectConfigService;
import io.github.liufor02144.erroragent.service.analyze.LlmClient;
import io.github.liufor02144.erroragent.service.judge.InspectLibraryService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 模版复核：让大模型先过一遍，能确定是业务预期的直接标好，省掉大量人工确认。
 *
 * <p><b>误判的两个方向后果完全不同</b>，所以自动标记是单向的：
 * <ul>
 *   <li>把真故障误标成「业务预期」→ 它从此只在突增时才报，
 *       一个持续的 Redis 超时会被永久静默。<b>这是事故</b></li>
 *   <li>把业务预期误判成「故障」→ 只是多报一条，人看到后手工标记即可</li>
 * </ul>
 * 因此只自动标记<b>既判定为业务预期、又给出高置信度</b>的那部分；
 * 判成故障的一律不动（保持「系统故障」＝照报），低置信度的留给人。
 */
@Slf4j
@Service
public class TemplateReviewService {

    private static final String SCENE = "TEMPLATE_REVIEW";

    /** 一批复核多少个。比抽取时略多——复核只需判断，输出短得多 */
    private static final int BATCH_SIZE = 20;

    private static final String CONFIDENCE_HIGH = "HIGH";

    @Resource
    private InspectSwitch inspectSwitch;

    @Resource
    private InspectConfigService configService;

    @Resource
    private InspectLibraryMapper libraryMapper;

    @Resource
    private InspectLibraryService libraryService;

    @Resource
    private LlmClient llmClient;

    @Data
    public static class ReviewSummary {
        private int candidates;
        private int reviewed;
        /** 模型有把握、已自动标记为业务预期 */
        private int autoMarked;
        /** 模型判为故障，保持「系统故障」（照报） */
        private int keptAsFault;
        /** 模型没把握，留给人工 */
        private int needHuman;
        private int batches;
        private String message;
    }

    @Data
    public static class ReviewItem {
        private int index;
        private boolean businessExpected;
        private String confidence;
        private String reason;
    }

    /**
     * 复核判为系统故障的模版。
     *
     * @param force 忽略 LLM 开关
     * @param max   本次最多复核多少个
     */
    public ReviewSummary reviewPending(boolean force, Integer max) {
        ReviewSummary summary = new ReviewSummary();
        if (!force && !inspectSwitch.llmAllowed()) {
            summary.setMessage("AI 分析开关未开启（erroragent.llm.enabled=false）");
            return summary;
        }

        int limit = max != null && max > 0 ? max
                : configService.getInt(InspectConfigKeys.TEMPLATE_EXTRACT_MAX_PER_ROUND, 60);
        List<InspectLibrary> pending = libraryMapper.selectPendingReview(limit);
        if (pending == null || pending.isEmpty()) {
            summary.setMessage("没有待复核的模版");
            return summary;
        }

        // 技术故障在本地就筛掉，不送给模型 —— 它们无论如何都不会被放行，
        // 送过去只是花 token 换一个注定不变的结论
        List<InspectLibrary> toReview = new ArrayList<>(pending.size());
        for (InspectLibrary item : pending) {
            if (TechnicalFaultRule.isFault(item.getTemplate(), item.getHeadline())) {
                markTechnicalFault(item);
                summary.setKeptAsFault(summary.getKeptAsFault() + 1);
            } else {
                toReview.add(item);
            }
        }
        pending = toReview;
        summary.setCandidates(pending.size());
        if (pending.isEmpty()) {
            libraryService.refresh();
            summary.setMessage("待复核的全是技术故障，已就地标注，未调用模型");
            return summary;
        }

        for (int i = 0; i < pending.size(); i += BATCH_SIZE) {
            List<InspectLibrary> batch =
                    pending.subList(i, Math.min(i + BATCH_SIZE, pending.size()));
            try {
                reviewBatch(batch, summary);
                summary.setBatches(summary.getBatches() + 1);
            } catch (Exception e) {
                log.warn("模版复核批次失败（第 {} 批）：{}", i / BATCH_SIZE + 1, e.getMessage());
            }
        }
        libraryService.refresh();
        log.info("【模版复核】候选 {} 个，复核 {} 个：自动标记业务预期 {} 个，"
                        + "判为故障保持照报 {} 个，没把握转人工 {} 个",
                summary.getCandidates(), summary.getReviewed(), summary.getAutoMarked(),
                summary.getKeptAsFault(), summary.getNeedHuman());
        return summary;
    }

    /**
     * 复核单个模版。
     *
     * <p>批量复核适合初始化，但日常用不上 —— 人是在看某一条模版拿不准时才想听听 AI 的判断，
     * 而不是一次把几十条丢给模型（慢、贵、还刷屏）。
     *
     * <p>和批量走同一套单向标记规则：只有「是业务预期 + HIGH 置信度」才自动落标记，
     * 判成故障的一律不动。
     *
     * @return 复核结果；模版不存在时返回带 message 的空结果
     */
    public ReviewSummary reviewOne(Long templateId, boolean force) {
        ReviewSummary summary = new ReviewSummary();
        if (!force && !inspectSwitch.llmAllowed()) {
            summary.setMessage("AI 分析开关未开启（erroragent.llm.enabled=false）");
            return summary;
        }
        InspectLibrary tpl = libraryMapper.selectById(templateId);
        if (tpl == null) {
            summary.setMessage("模版不存在");
            return summary;
        }
        summary.setCandidates(1);
        try {
            reviewBatch(java.util.Collections.singletonList(tpl), summary);
            summary.setBatches(1);
        } catch (Exception e) {
            log.warn("单条模版复核失败 id={}：{}", templateId, e.getMessage());
            summary.setMessage("复核失败：" + e.getMessage());
            return summary;
        }
        libraryService.refresh();
        if (summary.getReviewed() == 0) {
            summary.setMessage("模型没有返回可用结论，请稍后再试");
        }
        return summary;
    }

    private void reviewBatch(List<InspectLibrary> batch, ReviewSummary summary) {
        LlmClient.LlmResponse resp =
                llmClient.chat(SCENE, null, systemPrompt(), buildUserPrompt(batch));
        if (!resp.isSuccess()) {
            log.warn("模版复核调用失败：{}", resp.getErrorMsg());
            return;
        }
        for (ReviewItem item : parse(resp.getContent(), batch.size())) {
            InspectLibrary tpl = batch.get(item.getIndex());
            summary.setReviewed(summary.getReviewed() + 1);

            // 单向标记：只有「是业务预期」且「有把握」才自动落，
            // 判成故障的保持「系统故障」（照报），这是更安全的一侧
            // 技术故障一律不许自动放行，哪怕模型很有把握 ——
            // 模型看到「系统异常 原因：null」会觉得是业务文案，但它背后是空指针
            if (TechnicalFaultRule.isFault(tpl.getTemplate(), tpl.getHeadline())) {
                markTechnicalFault(tpl);
                summary.setKeptAsFault(summary.getKeptAsFault() + 1);
                continue;
            }

            if (item.isBusinessExpected() && CONFIDENCE_HIGH.equalsIgnoreCase(item.getConfidence())) {
                // 走 service 而不是 mapper：它会连带关闭该模版下所有未处理事件。
                // 直接调 mapper 的话，模版已经是「业务预期」了，事件却还开着 ——
                // 分析任务每小时还会把它送一次模型，纯属烧 token。
                // 虽然下一轮采集的兜底清扫会补上，但那要等到 5 分钟后
                libraryService.markBusinessExpected(tpl.getId(),
                        "AI 复核自动标记：" + nv(item.getReason()), "ai");
                summary.setAutoMarked(summary.getAutoMarked() + 1);
                log.info("【AI 复核】自动标记业务预期：{} —— {}", tpl.getTemplate(), item.getReason());
            } else if (!item.isBusinessExpected()) {
                // 判为故障：不动标记，但把结论写进说明，人看列表时就知道 AI 怎么看
                libraryMapper.updateReason(tpl.getId(),
                        "AI 复核：疑似故障，保持照报（" + nv(item.getReason()) + "）", "ai");
                summary.setKeptAsFault(summary.getKeptAsFault() + 1);
            } else {
                libraryMapper.updateReason(tpl.getId(),
                        "AI 复核：可能是业务预期但没把握（" + nv(item.getReason()) + "），请人工确认", "ai");
                summary.setNeedHuman(summary.getNeedHuman() + 1);
            }
        }
    }

    /** 就地标成技术故障，并写明是哪条特征命中的 */
    private void markTechnicalFault(InspectLibrary tpl) {
        String sign = TechnicalFaultRule.matchedSign(tpl.getTemplate(), tpl.getHeadline());
        libraryMapper.updateReason(tpl.getId(),
                "技术故障，不可标为业务预期（命中「" + sign + "」），需要人工分析", "ai");
    }

    /**
     * 每轮采集后自动复核新产生的模版。
     *
     * <p>不做这件事，兜底模版会一直堆在「系统故障」里等人一条条点 ——
     * 实测两天就堆到 36 条，其中 20 条从没被看过。而这批里有相当比例
     * （「没有领券记录」「订单状态已变更」「当前退单已不允许该操作」）
     * 一眼就是业务预期。
     *
     * <p>受 {@code llm.enabled} 管控，且每轮有上限：自动复核是常态化开销，
     * 不能让它在模版突然暴增时把当天额度打空。
     */
    public ReviewSummary reviewNewlyCreated() {
        int max = configService.getInt(InspectConfigKeys.TEMPLATE_REVIEW_MAX_PER_ROUND, 10);
        if (max <= 0) {
            ReviewSummary s = new ReviewSummary();
            s.setMessage("自动复核已关闭（template.review.max.per.round=0）");
            return s;
        }
        return reviewPending(false, max);
    }

    private String systemPrompt() {
        return "你是一个 Java 生产异常的分类助手。给你若干条异常模版，"
                + "判断每一条是否属于「业务预期内」。\n\n"
                + "业务预期内 = 业务规则正常拒绝，系统本身是健康的。例如：\n"
                + "  库存不足、账号未设置、优惠券已使用、券码不存在、商品已售罄、\n"
                + "  未查询到符合条件的数据、参数校验不通过、权限不足\n\n"
                + "不属于业务预期 = 系统或依赖出了问题。例如：\n"
                + "  连接超时、连接拒绝、空指针、数组越界、SQL 异常、磁盘/IO 错误、\n"
                + "  第三方服务不可用、序列化失败、线程池耗尽、缓存指标异常、\n"
                + "  消息发送失败、类型转换异常\n\n"
                + "判断不了就老实说没把握，不要硬猜。\n\n"
                + "confidence 只能是 HIGH / MEDIUM / LOW：\n"
                + "  HIGH   —— 从文案就能确定，没有歧义\n"
                + "  MEDIUM —— 比较像，但不排除其他可能\n"
                + "  LOW    —— 信息不足，看不出来\n\n"
                + "特别注意：判成「业务预期」会让这个异常以后不再告警，"
                + "只有突增时才报。所以<b>拿不准时一律给 LOW 或判为非业务预期</b>，"
                + "漏标只是多报一条，错标会让真故障被永久静默。\n\n"
                + "只输出 JSON，不要解释：\n"
                + "{\"items\":[{\"index\":0,\"businessExpected\":true,"
                + "\"confidence\":\"HIGH\",\"reason\":\"一句话理由\"}]}";
    }

    private String buildUserPrompt(List<InspectLibrary> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("请判断以下 ").append(batch.size()).append(" 条异常模版：\n\n");
        for (int i = 0; i < batch.size(); i++) {
            InspectLibrary t = batch.get(i);
            sb.append("[").append(i).append("] 模版：").append(nv(t.getTemplate())).append('\n');
            // 原文走 LogKeyText：它带上异常行，而异常行才说明这到底是
            // 「支付渠道未开通退款权限」还是「第三方接口超时」——
            // 只给首行的话模型是在信息缺失的前提下判断
            if (notBlank(t.getSampleMessage())) {
                sb.append("    原文：")
                        .append(truncate(LogKeyText.of(t.getSampleMessage()), 280)).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private List<ReviewItem> parse(String raw, int size) {
        List<ReviewItem> out = new ArrayList<>();
        int start = raw == null ? -1 : raw.indexOf('{');
        int end = raw == null ? -1 : raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("模版复核输出中未找到 JSON：{}", truncate(raw, 200));
            return out;
        }
        try {
            JSONArray arr = JSON.parseObject(raw.substring(start, end + 1)).getJSONArray("items");
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ReviewItem item = new ReviewItem();
                Integer idx = o.getInteger("index");
                item.setIndex(idx == null ? i : idx);
                if (item.getIndex() < 0 || item.getIndex() >= size) {
                    continue;
                }
                item.setBusinessExpected(Boolean.TRUE.equals(o.getBoolean("businessExpected")));
                item.setConfidence(o.getString("confidence"));
                item.setReason(o.getString("reason"));
                out.add(item);
            }
        } catch (Exception e) {
            log.warn("解析模版复核结果失败：{} | 原文：{}", e.getMessage(), truncate(raw, 200));
        }
        return out;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private boolean notBlank(String s) {
        // 统一口径：字面量 "null" 不算内容（空指针的 exc_cause 就是它）
        return io.github.liufor02144.erroragent.service.InspectText.notBlank(s);
    }

    private String nv(String s) {
        return s == null ? "" : s;
    }
}
