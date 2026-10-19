package io.github.liufor02144.erroragent.service.judge;

import io.github.liufor02144.erroragent.entity.InspectFingerprint;
import io.github.liufor02144.erroragent.entity.InspectLibrary;
import io.github.liufor02144.erroragent.mapper.InspectLibraryMapper;
import io.github.liufor02144.erroragent.service.template.TechnicalFaultRule;
import io.github.liufor02144.erroragent.service.template.TemplateMatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 异常模版库的维护。
 *
 * <p>模版库收录<b>全部</b>异常模版，判定用的不是「在不在库里」，而是
 * 模版上的 {@code business_expected} 标记：
 * <ul>
 *   <li>已标记业务预期 → 正常抛出不报，<b>突增仍报</b></li>
 *   <li>未确认 → 每次出现都报</li>
 * </ul>
 *
 * <p>匹配逻辑在 {@link TemplateMatcher}，这里只管增删改查。
 */
@Slf4j
@Service
public class InspectLibraryService {

    @Resource
    private InspectLibraryMapper libraryMapper;

    @Resource
    private io.github.liufor02144.erroragent.mapper.InspectEventMapper eventMapper;

    @Resource
    private TemplateMatcher templateMatcher;

    @Resource
    private io.github.liufor02144.erroragent.service.template.TemplateExtractor templateExtractor;

    /**
     * 这个异常命中哪个模版。
     *
     * @param rawSample 原始日志样例，可为 null
     */
    public InspectLibrary match(InspectFingerprint fp, String rawSample) {
        return templateMatcher.match(fp, rawSample);
    }

    /** 兼容无样例的调用方 */
    public InspectLibrary match(InspectFingerprint fp) {
        return templateMatcher.match(fp, null);
    }

    public void refresh() {
        templateMatcher.refresh();
    }

    /**
     * 批量登记模版。抽取任务产出的模版走这里入库。
     *
     * @return 实际新增条数
     */
    public int enroll(List<InspectLibrary> templates) {
        if (templates == null || templates.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        // 分批写，避免单条 SQL 过长
        for (int i = 0; i < templates.size(); i += 100) {
            List<InspectLibrary> slice =
                    templates.subList(i, Math.min(i + 100, templates.size()));
            try {
                inserted += libraryMapper.batchInsertIgnore(slice);
            } catch (Exception e) {
                log.warn("模版入库失败（第 {} 批）：{}", i / 100 + 1, e.getMessage());
            }
        }
        refresh();
        if (inserted > 0) {
            log.info("【模版库】新增 {} 个模版，默认按系统故障对待（每次出现都会报），待人工确认", inserted);
        }
        return inserted;
    }

    /**
     * 手工登记模版。
     *
     * <p>正则一律由 {@code patternFromTemplate} 从模版生成，<b>不接受手写正则</b> ——
     * 手写的正则和抽取链路生成的规则不一致时，同一条日志会时而匹配时而不匹配，
     * 这种问题极难排查。
     *
     * @return 错误信息；成功返回 null
     */
    public String createManual(String template, boolean businessExpected,
                               String reason, String sample, String operator) {
        if (template == null || template.trim().isEmpty()) {
            return "模版不能为空";
        }
        String text = template.trim();
        String pattern = templateExtractor.patternFromTemplate(text);
        if (pattern == null) {
            return "模版里没有足够的固定文字，生成的正则会匹配一切：" + text;
        }
        try {
            java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(pattern);
            if (sample != null && !sample.trim().isEmpty()
                    && !compiled.matcher(sample).find()) {
                return "生成的正则匹配不上给定样例，模版与样例对不上：" + text;
            }
        } catch (Exception e) {
            return "正则无法编译：" + e.getMessage();
        }

        InspectLibrary item = new InspectLibrary();
        item.setMatchType(InspectLibrary.TYPE_TEMPLATE);
        item.setMatchValue(text);
        item.setTemplate(text);
        item.setPattern(pattern);
        item.setHeadline(text);
        item.setBusinessExpected(businessExpected ? 1 : 0);
        item.setReason(reason);
        item.setSampleMessage(sample);
        item.setOperator(operator);
        if (libraryMapper.insertIgnore(item) == 0) {
            return "该模版已存在";
        }
        refresh();
        return null;
    }

    /**
     * 用当前规则重算所有模版的正则。
     *
     * <p>正则生成规则会随着踩坑演进（标点放宽、通配长度上调…），
     * 而已入库的模版还带着旧规则生成的正则 —— 表现是「明明建了模版却一直不命中」。
     * 这个入口把它们统一刷到最新规则。
     *
     * @return [更新数, 跳过数, 失败描述…]
     */
    public java.util.Map<String, Object> regeneratePatterns() {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        int updated = 0;
        int skipped = 0;
        List<String> failed = new ArrayList<>();

        for (InspectLibrary item : libraryMapper.selectList(null, null)) {
            if (!InspectLibrary.TYPE_TEMPLATE.equals(item.getMatchType())
                    || item.getTemplate() == null) {
                skipped++;
                continue;
            }
            String pattern = templateExtractor.patternFromTemplate(item.getTemplate());
            if (pattern == null) {
                failed.add(item.getTemplate() + " → 固定文字不足");
                continue;
            }
            if (pattern.equals(item.getPattern())) {
                skipped++;
                continue;
            }
            try {
                Pattern compiled = Pattern.compile(pattern);
                // 样例是原始多行日志，而模版抽自 LogKeyText 的单行形式，
                // 校验时必须走同一套提取，否则会把好正则误判成坏的
                String key = io.github.liufor02144.erroragent.service.template.LogKeyText
                        .of(item.getSampleMessage());
                if (!key.isEmpty() && !compiled.matcher(key).find()) {
                    failed.add(item.getTemplate() + " → 新正则匹配不上样例");
                    continue;
                }
            } catch (Exception e) {
                failed.add(item.getTemplate() + " → " + e.getMessage());
                continue;
            }
            libraryMapper.updatePattern(item.getId(), item.getTemplate(), pattern, "system");
            updated++;
        }
        refresh();
        result.put("updated", updated);
        result.put("skipped", skipped);
        result.put("failedCount", failed.size());
        result.put("failed", failed);
        log.info("【模版正则重算】更新 {} 个，跳过 {} 个，失败 {} 个", updated, skipped, failed.size());
        return result;
    }

    /**
     * 标记为业务预期内：承认这是业务逻辑正常走到的分支。
     *
     * <p>标记后正常抛出不再报，但突增仍会报 —— 否则它会变成看不见的盲区。
     */
    public boolean markBusinessExpected(Long id, String reason, String operator) {
        int n = libraryMapper.updateBusinessExpected(id, 1, reason, operator);
        refresh();
        if (n > 0) {
            InspectLibrary item = libraryMapper.selectById(id);
            // 标记只改变「以后怎么判」，不会动已经报出来的事件 ——
            // 于是页面上出现「模版已是业务预期，事件还挂在待处理」的自相矛盾状态。
            // 既然承认这本来就该发生，那几条就不再是待办了。
            int closed = closeOpenEvents(id, reason, operator);
            log.info("【业务预期】{} by {}，原因：{}；顺带关闭 {} 条已报事件",
                    item == null ? id : item.getTemplate(), operator, reason, closed);
        }
        return n > 0;
    }

    /** 关闭某模版下所有未处理事件 */
    private int closeOpenEvents(Long templateId, String reason, String operator) {
        try {
            return eventMapper.closeByTemplateId(templateId,
                    "所属模版已确认为业务预期：" + (reason == null ? "" : reason), operator);
        } catch (Exception e) {
            log.warn("关闭模版 {} 下的事件失败：{}", templateId, e.getMessage());
            return 0;
        }
    }

    /**
     * 批量把判为系统故障的模版转成业务预期 —— 系统平稳期的「基线归零」操作。
     *
     * <p>前提是「现在没有客诉、没有已知故障」，那么存量异常认作业务预期是合理的：
     * 之后只有<b>新出现的</b>和<b>突增的</b>才报，噪音一次性清掉。
     *
     * <p><b>技术故障不参与</b>（见 {@link TechnicalFaultRule}）：空指针、SQL 异常、
     * 各类超时这些一旦放行就永久静默，而它们恰恰是最该盯的 ——
     * 「今天只有 2 次」不代表明天不是 2 万次，量小只说明触发条件还没铺开。
     *
     * @param reason 标记原因，会写进每条模版的说明里
     * @return 结果明细：marked / 被保护的技术故障清单
     */
    public java.util.Map<String, Object> markAllExpected(String reason, String operator) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        List<InspectLibrary> pending = libraryMapper.selectList(0, null);
        int marked = 0;
        List<String> protectedFaults = new ArrayList<>();

        for (InspectLibrary item : pending) {
            if (TechnicalFaultRule.isFault(item.getTemplate(), item.getMatchValue(), item.getHeadline())) {
                String sign = TechnicalFaultRule.matchedSign(item.getTemplate(),
                        item.getMatchValue(), item.getHeadline());
                protectedFaults.add(item.getTemplate() + "（命中「" + sign + "」）");
                // 把「为什么没被放行」写进说明，避免下次又来问
                libraryMapper.updateReason(item.getId(),
                        "技术故障，不随批量放行（命中「" + sign + "」）：空指针 / SQL 异常 / 超时"
                                + "这类一旦标为业务预期就永久静默，必须逐条分析",
                        operator);
                continue;
            }
            libraryMapper.updateBusinessExpected(item.getId(), 1, reason, operator);
            closeOpenEvents(item.getId(), reason, operator);
            marked++;
        }
        refresh();

        result.put("candidates", pending.size());
        result.put("marked", marked);
        result.put("protectedCount", protectedFaults.size());
        result.put("protected", protectedFaults);
        log.info("【基线归零】{} 个系统故障模版：标记业务预期 {} 个，技术故障保护 {} 个 by {}",
                pending.size(), marked, protectedFaults.size(), operator);
        return result;
    }

    /**
     * 批量标记业务预期（页面勾选）。
     *
     * <p>和基线归零一样，<b>技术故障不随批量放行</b>：一次勾几十条时没人会逐条看，
     * 夹在里面的空指针 / SQL 异常 / 超时被顺手带进去就永久静默了。
     * 这类要标只能点开单条标 —— 那是看过之后的决定。
     *
     * @return marked / protected（被保护的技术故障）/ missing（已不存在）
     */
    public java.util.Map<String, Object> markExpectedBatch(java.util.Collection<Long> ids,
                                                           String reason, String operator) {
        int marked = 0;
        int already = 0;
        List<String> protectedFaults = new ArrayList<>();
        List<Long> missing = new ArrayList<>();
        for (Long id : ids) {
            InspectLibrary item = libraryMapper.selectById(id);
            if (item == null) {
                missing.add(id);
                continue;
            }
            if (item.expected()) {
                already++;
                continue;
            }
            if (TechnicalFaultRule.isFault(item.getTemplate(), item.getMatchValue(), item.getHeadline())) {
                protectedFaults.add(item.getTemplate() + "（命中「" + TechnicalFaultRule.matchedSign(
                        item.getTemplate(), item.getMatchValue(), item.getHeadline()) + "」）");
                continue;
            }
            if (libraryMapper.updateBusinessExpected(id, 1, reason, operator) > 0) {
                closeOpenEvents(id, reason, operator);
                marked++;
            }
        }
        refresh();

        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("marked", marked);
        result.put("already", already);
        result.put("protectedCount", protectedFaults.size());
        result.put("protected", protectedFaults);
        result.put("missing", missing.size());
        log.info("【批量业务预期】勾选 {} 个：标记 {}，本已是 {}，技术故障保护 {}，不存在 {} by {}",
                ids.size(), marked, already, protectedFaults.size(), missing.size(), operator);
        return result;
    }

    /** 批量取消业务预期 */
    public int unmarkBatch(java.util.Collection<Long> ids, String operator) {
        int n = 0;
        for (Long id : ids) {
            n += libraryMapper.updateBusinessExpected(id, 0, null, operator);
        }
        refresh();
        return n;
    }

    /** 批量删除模版 */
    public int removeBatch(java.util.Collection<Long> ids) {
        int n = 0;
        for (Long id : ids) {
            n += libraryMapper.deleteById(id);
        }
        refresh();
        return n;
    }

    /** 取消业务预期标记。取消后该异常每次出现都会重新产生事件 */
    public boolean unmarkBusinessExpected(Long id, String operator) {
        int n = libraryMapper.updateBusinessExpected(id, 0, null, operator);
        refresh();
        return n > 0;
    }

    /**
     * 人工修正模版与正则。
     *
     * <p>模型生成的正则可能写宽了（吞掉不同异常）或写窄了（永远命中不了），
     * 这里做最基本的防线：能编译、且能匹配自己的样例。
     *
     * @return 错误信息；成功返回 null
     */
    public String updatePattern(Long id, String template, String pattern, String operator) {
        if (template == null || template.trim().isEmpty()) {
            return "模版不能为空";
        }
        if (pattern == null || pattern.trim().isEmpty()) {
            return "匹配正则不能为空";
        }
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern.trim());
        } catch (Exception e) {
            return "正则无法编译：" + e.getMessage();
        }
        InspectLibrary item = libraryMapper.selectById(id);
        if (item == null) {
            return "模版不存在";
        }
        // 改完匹配不上自己的样例，说明这个正则以后永远不会命中，等于把模版废掉
        if (item.getSampleMessage() != null && !item.getSampleMessage().trim().isEmpty()
                && !compiled.matcher(item.getSampleMessage()).find()) {
            return "这个正则匹配不上该模版的原始样例，改了之后它将永远不会命中";
        }
        libraryMapper.updatePattern(id, template.trim(), pattern.trim(), operator);
        refresh();
        log.info("【模版修正】id={} pattern={} by {}", id, pattern.trim(), operator);
        return null;
    }

    public void recordHits(List<Long> ids, long delta) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        try {
            libraryMapper.incrHit(new ArrayList<>(ids), delta, new Date());
        } catch (Exception e) {
            log.warn("更新模版命中次数失败: {}", e.getMessage());
        }
    }

    /** 页面分页列表 */
    public java.util.Map<String, Object> page(Integer businessExpected, String keyword, int page, int size) {
        int s = Math.max(1, Math.min(size, 100));
        int p = Math.max(1, page);
        java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("total", libraryMapper.countList(businessExpected, keyword));
        r.put("rows", libraryMapper.selectPage(businessExpected, keyword, (p - 1) * s, s));
        return r;
    }

    public List<InspectLibrary> list(Integer businessExpected, String keyword) {
        return libraryMapper.selectList(businessExpected, keyword);
    }

    public InspectLibrary getById(Long id) {
        return libraryMapper.selectById(id);
    }

    public int countAll() {
        return libraryMapper.countAll();
    }

    /** 已确认为业务预期内的模版数 */
    public int countBusinessExpected() {
        return libraryMapper.countBusinessExpected();
    }

    /** 未确认的模版数 —— 这些每次出现都会报 */
    public int countUnconfirmed() {
        return libraryMapper.countUnconfirmed();
    }

    public void updateReason(Long id, String reason, String operator) {
        libraryMapper.updateReason(id, reason, operator);
        refresh();
    }

    public void setEnabled(Long id, boolean enabled) {
        libraryMapper.updateEnabled(id, enabled ? 1 : 0);
        refresh();
    }

    /** 删除模版。删除后该异常会被当成没见过，下轮重新抽取 */
    public void remove(Long id) {
        libraryMapper.deleteById(id);
        refresh();
    }

    /** 标题取信息量最大的那句：异常原因 > 消息内容 > 异常类名 */
    public static String headlineOf(InspectFingerprint fp) {
        if (notBlank(fp.getExcCause())) {
            return fp.getExcCause();
        }
        if (notBlank(fp.getMsgTpl())) {
            return fp.getMsgTpl();
        }
        if (notBlank(fp.getExcSimple())) {
            return fp.getExcSimple();
        }
        return fp.getFpKey() == null ? "（无可用描述）" : fp.getFpKey();
    }

    /** 判空统一走 {@link io.github.liufor02144.erroragent.service.InspectText} */
    private static boolean notBlank(String s) {
        return io.github.liufor02144.erroragent.service.InspectText.notBlank(s);
    }
}
