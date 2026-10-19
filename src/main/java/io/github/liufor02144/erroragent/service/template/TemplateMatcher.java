package io.github.liufor02144.erroragent.service.template;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.liufor02144.erroragent.entity.InspectFingerprint;
import io.github.liufor02144.erroragent.entity.InspectLibrary;
import io.github.liufor02144.erroragent.mapper.InspectLibraryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 模版匹配：把粗指纹归到已有模版上。
 *
 * <p>这一层<b>完全不调模型</b>，每轮采集都要跑，必须免费且快。
 * 只有匹配不上的才进待抽取队列（见 {@link TemplateExtractor}）。
 *
 * <p>匹配顺序刻意<b>按正则长度降序</b>：长正则更具体。
 * 若让宽正则先命中，「券码不存在」会被「券码.*」这种模版抢走，
 * 而后者本该只匹配更宽泛的场景 —— 具体优先是避免误吞的关键。
 */
@Slf4j
@Service
public class TemplateMatcher {

    private static final String ALL = "all";

    @Resource
    private InspectLibraryMapper libraryMapper;

    /** 编译后的正则随条目一起缓存 —— 每轮几百个指纹 × 几百个模版，重复编译开销不可忽略 */
    private static class CompiledTemplate {
        final InspectLibrary item;
        final Pattern pattern;

        CompiledTemplate(InspectLibrary item, Pattern pattern) {
            this.item = item;
            this.pattern = pattern;
        }
    }

    /** 已提示过的包装模版：缓存每 30 秒重载一次，不去重会刷屏 */
    private final java.util.Set<Long> warnedWrapper = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final LoadingCache<String, List<CompiledTemplate>> cache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build(k -> load());

    private List<CompiledTemplate> load() {
        List<CompiledTemplate> out = new ArrayList<>();
        try {
            List<InspectLibrary> items = libraryMapper.selectEnabled();
            if (items == null) {
                return out;
            }
            for (InspectLibrary item : items) {
                if (item.getPattern() == null || item.getPattern().trim().isEmpty()) {
                    // 没有正则的老条目（LOGGER/EXCEPTION/APP 维度）走 fallback 匹配
                    out.add(new CompiledTemplate(item, null));
                    continue;
                }
                if (LogKeyText.isWrapperOnly(item.getTemplate(), 6)) {
                    // 早先入库的「只剩框架包装」模版：不再参与匹配，它下面的指纹下一轮重新抽模版
                    if (warnedWrapper.add(item.getId())) {
                        log.warn("模版只剩框架包装套话，已跳过匹配 id={} template={}", item.getId(), item.getTemplate());
                    }
                    continue;
                }
                try {
                    out.add(new CompiledTemplate(item, Pattern.compile(item.getPattern().trim())));
                } catch (Exception e) {
                    // 人工改坏的正则不该让整轮匹配失败，跳过并留痕
                    log.warn("模版正则无法编译，已跳过 id={} pattern={}：{}",
                            item.getId(), item.getPattern(), e.getMessage());
                }
            }
            // 具体优先：正则越长约束越强
            out.sort(Comparator.comparingInt(
                    (CompiledTemplate c) -> c.pattern == null ? 0 : c.pattern.pattern().length()).reversed());
        } catch (Exception e) {
            // 读失败时返回空表：全部算「未匹配」，宁可多抽几个模版，不能让采集挂掉
            log.error("加载模版库失败，本轮全部按未匹配处理", e);
        }
        return out;
    }

    public void refresh() {
        cache.invalidate(ALL);
    }

    /**
     * 匹配单个指纹。
     *
     * @param rawSample 原始日志样例，优先用它匹配 —— 归一后的 msg_tpl 已被正则破坏过
     * @return 命中的模版，未命中返回 null
     */
    public InspectLibrary match(InspectFingerprint fp, String rawSample) {
        String text = matchText(fp, rawSample);
        if (text.isEmpty()) {
            return null;
        }
        for (CompiledTemplate c : cache.get(ALL)) {
            if (c.pattern != null) {
                if (c.pattern.matcher(text).find()) {
                    return c.item;
                }
            } else if (legacyHit(c.item, fp)) {
                return c.item;
            }
        }
        return null;
    }

    /**
     * 参与匹配的文本：原始首行 + 异常原因 + 归一消息全都拼上。
     *
     * <p>三者都拼是因为模版可能抽自任一处 —— 有堆栈的异常语义在 cause 里，
     * 无堆栈的在 message 首行，而正则只需在其中一处命中。
     */
    private String matchText(InspectFingerprint fp, String rawSample) {
        StringBuilder sb = new StringBuilder();
        // 与抽取侧共用 LogKeyText：模版抽自「首行 + 异常行」，
        // 匹配时若只拿首行去撞，带原因的模版永远命中不了
        if (notBlank(rawSample)) {
            sb.append(LogKeyText.of(rawSample)).append('\n');
        }
        if (notBlank(fp.getExcCause())) {
            // SLS 侧的 exc_cause 常是最外层包装（Request processing failed; nested exception is ...），
            // 原样拼进来，只写了包装套话的模版就会把各种不相干的故障都吞掉
            sb.append(LogKeyText.unwrap(fp.getExcCause())).append('\n');
        }
        if (notBlank(fp.getMsgTpl())) {
            sb.append(fp.getMsgTpl());
        }
        return sb.toString();
    }

    /** 兼容无正则的老条目：logger / 异常类 / 服务维度 */
    private boolean legacyHit(InspectLibrary item, InspectFingerprint fp) {
        String value = item.getMatchValue();
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        value = value.trim();
        switch (item.getMatchType() == null ? "" : item.getMatchType()) {
            case InspectLibrary.TYPE_LOGGER:
                return value.equals(fp.getLoggerClass());
            case InspectLibrary.TYPE_EXCEPTION:
                return value.equals(fp.getExcSimple());
            case InspectLibrary.TYPE_APP:
                return value.equals(fp.getAppId());
            default:
                return false;
        }
    }

    /**
     * 批量匹配。
     *
     * @return fpId → 命中的模版；未命中的不出现在结果里
     */
    public Map<Long, InspectLibrary> matchAll(List<InspectFingerprint> fingerprints,
                                              Map<Long, String> rawSamples) {
        Map<Long, InspectLibrary> result = new HashMap<>();
        if (fingerprints == null || fingerprints.isEmpty()) {
            return result;
        }
        for (InspectFingerprint fp : fingerprints) {
            if (fp.getId() == null) {
                continue;
            }
            InspectLibrary hit = match(fp,
                    rawSamples == null ? null : rawSamples.get(fp.getId()));
            if (hit != null) {
                result.put(fp.getId(), hit);
            }
        }
        return result;
    }

    /** 当前生效的模版数，页面与日志用 */
    public int size() {
        return cache.get(ALL).size();
    }

    private boolean notBlank(String s) {
        // 统一口径：字面量 "null" 不算内容（空指针的 exc_cause 就是它）
        return io.github.liufor02144.erroragent.service.InspectText.notBlank(s);
    }

    /** 测试与诊断用：给定文本看命中哪个模版 */
    public InspectLibrary probe(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        for (CompiledTemplate c : cache.get(ALL)) {
            if (c.pattern != null && c.pattern.matcher(text).find()) {
                return c.item;
            }
        }
        return null;
    }

    /** 给定文本列出所有命中的模版，用于排查「为什么被归到了这个模版」 */
    public List<InspectLibrary> probeAll(String text) {
        List<InspectLibrary> hits = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return hits;
        }
        for (CompiledTemplate c : cache.get(ALL)) {
            if (c.pattern != null && c.pattern.matcher(text).find()) {
                hits.add(c.item);
            }
        }
        return Collections.unmodifiableList(hits);
    }
}
