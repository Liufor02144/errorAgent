package io.github.liufor02144.erroragent.controller;

import io.github.liufor02144.erroragent.common.CurrentUser;
import io.github.liufor02144.erroragent.common.Response;
import io.github.liufor02144.erroragent.entity.InspectEvent;
import io.github.liufor02144.erroragent.entity.InspectFingerprint;
import io.github.liufor02144.erroragent.entity.InspectLibrary;
import io.github.liufor02144.erroragent.entity.InspectSample;
import io.github.liufor02144.erroragent.mapper.InspectFingerprintMapper;
import io.github.liufor02144.erroragent.mapper.InspectSampleMapper;
import io.github.liufor02144.erroragent.service.judge.InspectEventService;
import io.github.liufor02144.erroragent.service.judge.InspectLibraryService;
import io.github.liufor02144.erroragent.service.push.InspectEventPushService;
import io.github.liufor02144.erroragent.service.query.InspectEventQueryService;
import io.github.liufor02144.erroragent.service.template.TemplateExtractService;
import io.github.liufor02144.erroragent.service.template.TemplateMatcher;
import io.github.liufor02144.erroragent.service.template.TemplateReviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * 异常事件与异常库接口。
 *
 * <p>页面上只有两个动作：<b>标注</b>（这条我处理了）和<b>加入异常库</b>（这条允许发生）。
 * 其余的分类、状态流转都已去掉 —— 每多一个动作就多一次「这个该选哪个」的犹豫。
 */
@Slf4j
@RestController
@RequestMapping("/inspect/event")
public class InspectEventController {

    @Resource
    private InspectEventQueryService queryService;

    @Resource
    private InspectEventService eventService;

    @Resource
    private InspectLibraryService libraryService;

    @Resource
    private InspectFingerprintMapper fingerprintMapper;

    @Resource
    private InspectSampleMapper sampleMapper;

    @Resource
    private InspectEventPushService eventPushService;

    @Resource
    private TemplateExtractService templateExtractService;

    @Resource
    private TemplateMatcher templateMatcher;

    @Resource
    private TemplateReviewService templateReviewService;

    @GetMapping
    public Response<InspectEventQueryService.PageResult> page(
            @RequestParam(required = false) Integer handled,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer sinceDays,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Response.ok(queryService.page(handled, eventType, appId, severity,
                keyword, sinceDays, page, size));
    }

    @GetMapping("/{eventId:\\d+}")
    public Response<InspectEventQueryService.EventDetail> detail(
            @PathVariable Long eventId,
            @RequestParam(defaultValue = "7") int trendDays,
            @RequestParam(defaultValue = "5") int sampleLimit) {
        InspectEventQueryService.EventDetail detail =
                queryService.detail(eventId, trendDays, sampleLimit);
        return detail == null ? Response.error("事件不存在") : Response.ok(detail);
    }

    @GetMapping("/overview")
    public Response<Map<String, Object>> overview() {
        return Response.ok(queryService.overview());
    }

    @GetMapping("/apps")
    public Response<List<String>> apps() {
        return Response.ok(queryService.appOptions());
    }

    /**
     * 标注：这条我处理了。标注后停止升级重推，群里的卡片同步成已处理。
     *
     * <p>不需要填名字 —— 从登录态取。
     */
    @PostMapping("/{eventId:\\d+}/handle")
    public Response<Map<String, Object>> handle(@PathVariable Long eventId,
                                                @RequestParam String note) {
        InspectEvent event = eventService.getById(eventId);
        if (event == null) {
            return Response.error("事件不存在");
        }
        String operator = currentUser();
        boolean ok = eventService.markHandled(eventId, note, operator);

        Map<String, Object> result = new HashMap<>();
        result.put("handled", ok);
        result.put("operator", operator);
        // 群里那张卡片也要同步，否则别人还会以为没人管
        if (ok) {
            try {
                result.put("cardSynced", eventPushService.syncHandledCard(eventId, operator));
            } catch (Exception e) {
                log.warn("同步群卡片失败 eventId={}：{}", eventId, e.getMessage());
                result.put("cardSynced", false);
            }
        }
        return Response.ok(result);
    }

    /**
     * 标记为业务预期：承认这是业务逻辑正常走到的分支，以后正常抛出不报。
     *
     * <p><b>突增仍然会报</b> —— 「商品已下架」平时 50 次是正常的，
     * 突然 5000 次就不是了，标记不等于屏蔽。
     *
     * <p>顺带把当前事件标注掉：既然已确认它属于业务预期，这条事件就没有待办价值了。
     */
    @PostMapping("/{eventId:\\d+}/expected")
    public Response<Map<String, Object>> markExpected(@PathVariable Long eventId,
                                                      @RequestParam String reason) {
        InspectEvent event = eventService.getById(eventId);
        if (event == null) {
            return Response.error("事件不存在");
        }
        Long templateId = event.getTemplateId();
        if (templateId == null) {
            return Response.error("这条事件还没有关联异常模版，无法标记。"
                    + "模版通常在下一轮采集时抽出，稍后再试");
        }

        String operator = currentUser();
        boolean marked = libraryService.markBusinessExpected(templateId, reason, operator);
        eventService.markHandled(eventId, "已标记为业务预期：" + reason, operator);

        Map<String, Object> result = new HashMap<>();
        result.put("marked", marked);
        result.put("templateId", templateId);
        result.put("operator", operator);
        return Response.ok(result);
    }

    // ---------------- 批量操作 ----------------

    /** 单次批量上限：每条都要同步一次飞书卡片，太多会把请求拖到超时 */
    private static final int BATCH_MAX = 200;

    /** 批量请求体。事件用 note，模版用 reason */
    @lombok.Data
    public static class BatchRequest {
        private List<Long> ids;
        private String note;
        private String reason;
    }

    private static String batchError(BatchRequest req) {
        if (req == null || req.getIds() == null || req.getIds().isEmpty()) {
            return "请先勾选";
        }
        if (req.getIds().size() > BATCH_MAX) {
            return "一次最多操作 " + BATCH_MAX + " 条";
        }
        return null;
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 批量标注已处理。已处理过的跳过，群里的卡片逐条同步。
     */
    @PostMapping("/batch/handle")
    public Response<Map<String, Object>> batchHandle(@RequestBody BatchRequest req) {
        String err = batchError(req);
        if (err == null && blank(req.getNote())) {
            err = "请填写处理说明";
        }
        if (err != null) {
            return Response.error(err);
        }
        String operator = currentUser();
        int handled = 0;
        int skipped = 0;
        int cardSynced = 0;
        for (Long id : new LinkedHashSet<>(req.getIds())) {
            InspectEvent event = eventService.getById(id);
            if (event == null || event.isHandled()) {
                skipped++;
                continue;
            }
            if (!eventService.markHandled(id, req.getNote().trim(), operator)) {
                skipped++;
                continue;
            }
            handled++;
            try {
                if (eventPushService.syncHandledCard(id, operator)) {
                    cardSynced++;
                }
            } catch (Exception e) {
                log.warn("同步群卡片失败 eventId={}：{}", id, e.getMessage());
            }
        }
        log.info("【批量已处理】勾选 {} 条：处理 {}，跳过 {}，卡片同步 {} by {}",
                req.getIds().size(), handled, skipped, cardSynced, operator);

        Map<String, Object> result = new HashMap<>();
        result.put("handled", handled);
        result.put("skipped", skipped);
        result.put("cardSynced", cardSynced);
        return Response.ok(result);
    }

    /**
     * 批量整类标业务预期：按勾选事件所属的模版去重后标记。
     *
     * <p>几条事件属于同一模版时只标一次；模版一旦标记，其下所有未处理事件一并关闭。
     * 技术故障模版不随批量放行，没有模版的事件无法标记，都在返回里列出。
     */
    @PostMapping("/batch/expected")
    public Response<Map<String, Object>> batchMarkExpected(@RequestBody BatchRequest req) {
        String err = batchError(req);
        if (err == null && blank(req.getReason())) {
            err = "请填写原因";
        }
        if (err != null) {
            return Response.error(err);
        }
        Set<Long> templateIds = new LinkedHashSet<>();
        int noTemplate = 0;
        for (Long id : new LinkedHashSet<>(req.getIds())) {
            InspectEvent event = eventService.getById(id);
            if (event == null) {
                continue;
            }
            if (event.getTemplateId() == null) {
                noTemplate++;
            } else {
                templateIds.add(event.getTemplateId());
            }
        }
        Map<String, Object> result = templateIds.isEmpty()
                ? new HashMap<>()
                : libraryService.markExpectedBatch(templateIds, req.getReason().trim(), currentUser());
        result.put("templateCount", templateIds.size());
        result.put("noTemplate", noTemplate);
        return Response.ok(result);
    }

    /** 批量标记模版为业务预期。技术故障不随批量放行 */
    @PostMapping("/library/batch/expected")
    public Response<Map<String, Object>> batchMarkTemplateExpected(@RequestBody BatchRequest req) {
        String err = batchError(req);
        if (err == null && blank(req.getReason())) {
            err = "请填写原因";
        }
        if (err != null) {
            return Response.error(err);
        }
        return Response.ok(libraryService.markExpectedBatch(
                new LinkedHashSet<>(req.getIds()), req.getReason().trim(), currentUser()));
    }

    /** 批量取消业务预期 */
    @PostMapping("/library/batch/unexpected")
    public Response<Integer> batchUnmarkTemplate(@RequestBody BatchRequest req) {
        String err = batchError(req);
        if (err != null) {
            return Response.error(err);
        }
        String operator = currentUser();
        int n = libraryService.unmarkBatch(new LinkedHashSet<>(req.getIds()), operator);
        log.info("【批量取消业务预期】{} 个 by {}", n, operator);
        return Response.ok(n);
    }

    /** 批量删除模版 */
    @PostMapping("/library/batch/remove")
    public Response<Integer> batchRemoveTemplate(@RequestBody BatchRequest req) {
        String err = batchError(req);
        if (err != null) {
            return Response.error(err);
        }
        int n = libraryService.removeBatch(new LinkedHashSet<>(req.getIds()));
        log.info("【批量删除模版】ids={} 删除 {} 个 by {}", req.getIds(), n, currentUser());
        return Response.ok(n);
    }

    // ---------------- 异常库 ----------------

    /**
     * 模版列表。
     *
     * @param businessExpected 1-只看业务预期 / 0-只看系统故障 / 不传-全部
     */
    @GetMapping("/library")
    public Response<Map<String, Object>> library(
            @RequestParam(required = false) Integer businessExpected,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Response.ok(libraryService.page(businessExpected, keyword, page, size));
    }

    /**
     * 模版详情：除模版本身，还要回答「它覆盖了哪些服务、哪些代码位置」。
     *
     * <p>模版是跨服务聚合的 —— 一条样例只能说明其中一个来源，
     * 看不出这个模版实际管着多大范围。
     */
    @GetMapping("/library/{id:\\d+}")
    public Response<Map<String, Object>> libraryDetail(@PathVariable Long id) {
        InspectLibrary item = libraryService.getById(id);
        if (item == null) {
            return Response.error("模版不存在");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("template", item);

        List<InspectFingerprint> fps = fingerprintMapper.selectByTemplateId(id, 50);
        List<Map<String, Object>> sources = new ArrayList<>();
        Set<String> apps = new LinkedHashSet<>();
        long total = 0L;
        for (InspectFingerprint fp : fps) {
            if (fp.getAppId() != null) {
                apps.add(fp.getAppId());
            }
            total += fp.getTotalCount() == null ? 0L : fp.getTotalCount();

            Map<String, Object> one = new HashMap<>();
            one.put("appId", fp.getAppId());
            one.put("bizFrame", "NO_STACK".equals(fp.getBizFrame()) ? null : fp.getBizFrame());
            one.put("loggerClass", fp.getLoggerClass());
            one.put("excSimple", fp.getExcSimple());
            one.put("totalCount", fp.getTotalCount());
            one.put("lastSeenTime", fp.getLastSeenTime());
            InspectSample sample = sampleMapper.selectLatestByFpId(fp.getId());
            one.put("rawMessage", sample == null ? null : sample.getRawMessage());
            one.put("traceId", sample == null ? null : sample.getTraceId());
            one.put("podName", sample == null ? null : sample.getPodName());
            sources.add(one);
        }
        result.put("sources", sources);
        result.put("appCount", apps.size());
        result.put("appList", new ArrayList<>(apps));
        result.put("fpCount", fps.size());
        result.put("totalCount", total);
        return Response.ok(result);
    }

    /** 标记模版为业务预期 */
    @PostMapping("/library/{id:\\d+}/expected")
    public Response<Boolean> markTemplateExpected(@PathVariable Long id,
                                                  @RequestParam String reason) {
        return Response.ok(libraryService.markBusinessExpected(id, reason, currentUser()));
    }

    /** 取消业务预期。取消后该异常每次出现都会重新产生事件 */
    @PostMapping("/library/{id:\\d+}/unexpected")
    public Response<Boolean> unmarkTemplateExpected(@PathVariable Long id) {
        return Response.ok(libraryService.unmarkBusinessExpected(id, currentUser()));
    }

    /**
     * 人工修正模版与匹配正则。
     *
     * <p>模型生成的正则可能写宽了（把不同异常吞进同一模版）或写窄了（永远命中不了），
     * 这个入口是必需的兜底。
     */
    @PostMapping("/library/{id:\\d+}/pattern")
    public Response<Boolean> updatePattern(@PathVariable Long id,
                                           @RequestParam String template,
                                           @RequestParam String pattern) {
        String err = libraryService.updatePattern(id, template, pattern, currentUser());
        return err == null ? Response.ok(true) : Response.error(err);
    }

    /** 删除模版。删除后该异常会被当成没见过，下轮重新抽取 */
    @PostMapping("/library/{id:\\d+}/remove")
    public Response<Boolean> removeFromLibrary(@PathVariable Long id) {
        libraryService.remove(id);
        log.info("【删除模版】id={} by {}", id, currentUser());
        return Response.ok(true);
    }

    @PostMapping("/library/{id:\\d+}/reason")
    public Response<Boolean> updateLibraryReason(@PathVariable Long id,
                                                 @RequestParam String reason) {
        libraryService.updateReason(id, reason, currentUser());
        return Response.ok(true);
    }

    @PostMapping("/library/{id:\\d+}/enabled")
    public Response<Boolean> setLibraryEnabled(@PathVariable Long id,
                                               @RequestParam boolean enabled) {
        libraryService.setEnabled(id, enabled);
        return Response.ok(true);
    }

    /**
     * 手动触发模版抽取。
     *
     * @param force 忽略 LLM 开关，用于开关关闭状态下的验证与补抽
     * @param max   本次最多处理多少个指纹
     */
    @PostMapping("/library/extract")
    public Response<TemplateExtractService.ExtractSummary> extractTemplates(
            @RequestParam(defaultValue = "false") boolean force,
            @RequestParam(required = false) Integer max) {
        return Response.ok(templateExtractService.extractPending(force, max));
    }

    /**
     * 批量手工登记模版。
     *
     * <p>正则由代码从模版生成，不接受手写 —— 手写正则与抽取链路的规则不一致时，
     * 同一条日志会时而匹配时而不匹配，这种问题极难排查。
     *
     * @param body [{template, businessExpected, reason, sample}]
     */
    @PostMapping("/library/manual")
    public Response<Map<String, Object>> createManual(
            @RequestBody List<Map<String, Object>> body) {
        int ok = 0;
        List<String> failed = new ArrayList<>();
        String operator = currentUser();
        for (Map<String, Object> it : body == null ? new ArrayList<Map<String, Object>>() : body) {
            String err = libraryService.createManual(
                    str(it.get("template")),
                    Boolean.TRUE.equals(it.get("businessExpected")),
                    str(it.get("reason")),
                    str(it.get("sample")),
                    operator);
            if (err == null) {
                ok++;
            } else {
                failed.add(err);
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("created", ok);
        result.put("failedCount", failed.size());
        result.put("failed", failed);
        log.info("【手工登记模版】成功 {} 个，失败 {} 个 by {}", ok, failed.size(), operator);
        return Response.ok(result);
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 按模版重建事件：关闭模版库建立前产生的错位事件。
     *
     * <p>事件的模版归属在创建时就定了，而模版库是逐步建起来的，
     * 于是同一个模版会同时存在「无模版的老事件」和「有模版的新事件」。
     * 关掉老的，下一轮采集会按正确模版重新聚合。
     */
    @PostMapping("/rebuild")
    public Response<Map<String, Object>> rebuildEvents() {
        int closed = eventService.closeStaleWithoutTemplate();
        Map<String, Object> result = new HashMap<>();
        result.put("closed", closed);
        result.put("expectedClosed", eventService.closeExpected());
        result.put("note", "异常若仍在发生，下一轮采集会按模版重新聚合");
        return Response.ok(result);
    }

    /**
     * 删除采集时自动登记的兜底模版（operator=auto）。
     *
     * <p>兜底模版的生成规则会演进 —— 早期版本把原始日志里的具体 orderId
     * 焊进了模版，这种模版永远匹配不到第二次。规则改好后用它清一遍重来。
     */
    @PostMapping("/library/drop-auto")
    public Response<Map<String, Object>> dropAutoTemplates() {
        int dropped = 0;
        for (InspectLibrary item : libraryService.list(null, null)) {
            if ("auto".equals(item.getOperator())) {
                libraryService.remove(item.getId());
                dropped++;
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("dropped", dropped);
        return Response.ok(result);
    }

    /**
     * 把模版库导出成可执行的 SQL。
     *
     * <p>模版库是人工梳理出来的成果，只存在当前环境的数据库里。要上 test/stage/prod
     * 就得能搬过去 —— 这不是一次性需求：正则生成规则每演进一次、人工每标注一批，
     * 都需要重新同步一次。
     *
     * <p>产出用 {@code INSERT ... ON DUPLICATE KEY UPDATE}，按
     * {@code (match_type, match_value)} 幂等：<b>可以反复执行</b>，
     * 目标环境已有的同名模版会被更新成这边的判定与说明，不会重复插入。
     *
     * <p>不导出 id / 命中次数 / 最近命中时间 —— 那些是各环境自己的运行数据。
     */
    // 统一返回 JSON：全局响应包装会把 text/plain 的返回拦成 406，
    // 不跟框架较劲，SQL 作为字符串字段带出去
    @GetMapping("/library/export-sql")
    public Response<Map<String, Object>> exportLibrarySql() {
        List<InspectLibrary> all = libraryService.list(null, null);
        StringBuilder sb = new StringBuilder();
        sb.append("-- 巡检异常模版库导出\n")
                .append("-- 共 ").append(all.size()).append(" 条")
                .append("（业务预期 ").append(libraryService.countBusinessExpected())
                .append(" / 系统故障 ").append(libraryService.countUnconfirmed()).append("）\n")
                .append("-- 幂等：按 (match_type, match_value) 去重，可反复执行\n")
                .append("-- 不含 id 与命中统计，那些是各环境自己的运行数据\n\n");

        for (InspectLibrary it : all) {
            sb.append("INSERT INTO `ai_inspect_library`\n")
                    .append("  (`match_type`, `match_value`, `template`, `pattern`,\n")
                    .append("   `business_expected`, `sample_message`, `headline`, `reason`, `operator`)\n")
                    .append("VALUES (")
                    .append(q(it.getMatchType())).append(", ")
                    .append(q(it.getMatchValue())).append(", ")
                    .append(q(it.getTemplate())).append(", ")
                    .append(q(it.getPattern())).append(", ")
                    .append(it.getBusinessExpected() == null ? 0 : it.getBusinessExpected()).append(", ")
                    .append(q(it.getSampleMessage())).append(", ")
                    .append(q(it.getHeadline())).append(", ")
                    .append(q(it.getReason())).append(", ")
                    .append(q(it.getOperator())).append(")\n")
                    .append("ON DUPLICATE KEY UPDATE\n")
                    .append("  `template` = VALUES(`template`),\n")
                    .append("  `pattern` = VALUES(`pattern`),\n")
                    .append("  `business_expected` = VALUES(`business_expected`),\n")
                    .append("  `sample_message` = VALUES(`sample_message`),\n")
                    .append("  `headline` = VALUES(`headline`),\n")
                    .append("  `reason` = VALUES(`reason`),\n")
                    .append("  `operator` = VALUES(`operator`),\n")
                    .append("  `deleted` = 0;\n\n");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("count", all.size());
        result.put("businessExpected", libraryService.countBusinessExpected());
        result.put("systemFault", libraryService.countUnconfirmed());
        result.put("sql", sb.toString());
        return Response.ok(result);
    }

    /** SQL 字符串字面量。反斜杠必须先转义 —— 模版里全是正则，满是 \Q \E \s */
    private static String q(String v) {
        if (v == null) {
            return "NULL";
        }
        String e = v.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "'" + e + "'";
    }

    /**
     * 基线归零：把所有判为系统故障的模版转成业务预期。
     *
     * <p>用在「当前系统平稳、无客诉」的时刻 —— 存量异常一次性认作业务预期，
     * 之后只有新出现和突增的才报。
     *
     * <p><b>空指针 / SQL 异常 / 各类超时不参与</b>，它们会被保护下来并在返回里列出。
     * 这类异常量小不代表不重要，放行等于永久静默。
     */
    @PostMapping("/library/mark-all-expected")
    public Response<Map<String, Object>> markAllExpected(
            @RequestParam(required = false) String reason) {
        String text = reason == null || reason.trim().isEmpty()
                ? "系统平稳期基线归零：存量异常统一认作业务预期，此后只报新增与突增"
                : reason.trim();
        return Response.ok(libraryService.markAllExpected(text, currentUser()));
    }

    /** 用当前规则重算所有模版的正则。正则生成规则演进后用它统一刷新 */
    @PostMapping("/library/regenerate")
    public Response<Map<String, Object>> regeneratePatterns() {
        return Response.ok(libraryService.regeneratePatterns());
    }

    /**
     * 把现有模版套到还没有模版归属的指纹上（只回绑，不调 AI 抽取）。
     *
     * <p>手工新建一批模版后调一次：本轮没再出现的指纹不会被采集流程触达，
     * 它们的 {@code template_id} 会一直是空，看起来像「模版没生效」。
     */
    @PostMapping("/library/rebind")
    public Response<Map<String, Object>> rebindTemplates(
            @RequestParam(required = false) Integer max) {
        int bound = templateExtractService.rebindAll(max == null ? 2000 : max);
        Map<String, Object> result = new HashMap<>();
        result.put("bound", bound);
        return Response.ok(result);
    }

    /**
     * AI 复核<b>单个</b>模版 —— 页面上的常规入口。
     *
     * <p>人是在看某一条模版拿不准时才想听听 AI 的判断，不是一次把几十条丢给模型。
     * 和批量复核走同一套单向标记规则。
     */
    @PostMapping("/library/{id:\\d+}/review")
    public Response<TemplateReviewService.ReviewSummary> reviewOne(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean force) {
        return Response.ok(templateReviewService.reviewOne(id, force));
    }

    /**
     * AI 复核判为系统故障的模版，能确定其实是业务预期的直接标记。
     *
     * <p>只自动标记「判为业务预期且高置信度」的那部分 —— 判成故障的一律不动，
     * 因为误标成业务预期会让真故障永久静默，而漏标只是多报一条。
     *
     * <p>批量入口只用于初始化，页面上不暴露 —— 几十条一起复核慢且刷屏，
     * 日常用 {@code /library/{id}/review} 逐条来。
     */
    @PostMapping("/library/review")
    public Response<TemplateReviewService.ReviewSummary> reviewTemplates(
            @RequestParam(defaultValue = "false") boolean force,
            @RequestParam(required = false) Integer max) {
        return Response.ok(templateReviewService.reviewPending(force, max));
    }

    /** 诊断：给一段文本看它会命中哪个模版，用于排查「为什么被归到了这个模版」 */
    @GetMapping("/library/probe")
    public Response<List<InspectLibrary>> probe(@RequestParam String text) {
        return Response.ok(templateMatcher.probeAll(text));
    }

    /** 当前登录用户。取不到时返回 unknown，不阻断操作 */
    private String currentUser() {
        return CurrentUser.name();
    }
}
