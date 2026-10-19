package io.github.liufor02144.erroragent.service.template;

import io.github.liufor02144.erroragent.config.InspectSwitch;
import io.github.liufor02144.erroragent.dto.SampleRow;
import io.github.liufor02144.erroragent.entity.InspectFingerprint;
import io.github.liufor02144.erroragent.entity.InspectLibrary;
import io.github.liufor02144.erroragent.entity.InspectSample;
import io.github.liufor02144.erroragent.mapper.InspectFingerprintMapper;
import io.github.liufor02144.erroragent.mapper.InspectSampleMapper;
import io.github.liufor02144.erroragent.service.InspectConfigKeys;
import io.github.liufor02144.erroragent.service.InspectConfigService;
import io.github.liufor02144.erroragent.service.collect.InspectSlsRepository;
import io.github.liufor02144.erroragent.service.judge.InspectLibraryService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模版抽取的调度：挑出还没有模版的指纹，成批送给大模型。
 *
 * <p>独立于采集轮次，因为这件事需要能被单独触发 ——
 * 首轮清库后要一次性补齐几百个模版，采集里那条路径受
 * {@code erroragent.llm.enabled} 管控，而补抽是运维动作。
 */
@Slf4j
@Service
public class TemplateExtractService {

    /** 抓样例的回溯窗口。模版只要一条有代表性的原文，不必追很久 */
    private static final long SAMPLE_LOOKBACK_MS = 2 * 60 * 60 * 1000L;

    @Resource
    private InspectSwitch inspectSwitch;

    @Resource
    private InspectConfigService configService;

    @Resource
    private InspectFingerprintMapper fingerprintMapper;

    @Resource
    private InspectSampleMapper sampleMapper;

    @Resource
    private InspectSlsRepository slsRepository;

    @Resource
    private TemplateExtractor templateExtractor;

    @Resource
    private InspectLibraryService libraryService;

    @Resource
    private TemplateMatcher templateMatcher;

    @Data
    public static class ExtractSummary {
        private int candidates;
        private int processed;
        private int extracted;
        private int batches;
        private int sampleFetched;
        /** 回写了模版归属的指纹数 */
        private int bound;
        private String message;
    }

    /**
     * 对还没匹配到模版的指纹抽模版。
     *
     * @param force 忽略 LLM 开关。用于开关关闭状态下的验证与补抽
     * @param max   本次最多处理多少个，null 则取配置
     */
    public ExtractSummary extractPending(boolean force, Integer max) {
        ExtractSummary summary = new ExtractSummary();
        if (!force && !inspectSwitch.llmAllowed()) {
            summary.setMessage("AI 分析开关未开启（erroragent.llm.enabled=false）");
            return summary;
        }

        int limit = max != null && max > 0 ? max
                : configService.getInt(InspectConfigKeys.TEMPLATE_EXTRACT_MAX_PER_ROUND, 60);
        int batchSize = configService.getInt(InspectConfigKeys.TEMPLATE_EXTRACT_BATCH_SIZE, 15);
        int minLength = configService.getInt(InspectConfigKeys.TEMPLATE_MATCH_MIN_LENGTH, 6);

        // 量级大的先抽：模版库要优先覆盖占日志量最多的那批
        List<InspectFingerprint> pending = fingerprintMapper.selectWithoutTemplate(limit);
        if (pending == null || pending.isEmpty()) {
            summary.setMessage("没有待抽取的指纹");
            return summary;
        }
        summary.setCandidates(pending.size());

        for (int i = 0; i < pending.size(); i += batchSize) {
            List<InspectFingerprint> batch =
                    pending.subList(i, Math.min(i + batchSize, pending.size()));
            try {
                summary.setExtracted(summary.getExtracted() + extractBatch(batch, summary, minLength));
                summary.setBatches(summary.getBatches() + 1);
                summary.setProcessed(summary.getProcessed() + batch.size());
            } catch (Exception e) {
                log.warn("模版抽取批次失败（第 {} 批）：{}", i / batchSize + 1, e.getMessage());
            }
        }

        // 抽完立即回写归属：否则这批指纹下次仍被当成「没有模版」，
        // 会对同一个异常反复抽取，抽出一堆只有占位符名字不同的重复模版
        summary.setBound(bindTemplates(pending));
        log.info("【模版抽取】候选 {} 个，处理 {} 个，{} 批，新增模版 {} 个，补抓样例 {} 条",
                summary.getCandidates(), summary.getProcessed(), summary.getBatches(),
                summary.getExtracted(), summary.getSampleFetched());
        return summary;
    }

    private int extractBatch(List<InspectFingerprint> batch, ExtractSummary summary, int minLength) {
        List<String> samples = new ArrayList<>(batch.size());
        for (InspectFingerprint fp : batch) {
            samples.add(ensureSample(fp, summary));
        }

        TemplateExtractor.ExtractResult result =
                templateExtractor.extract(batch, samples, minLength);
        if (!result.isSuccess() || result.getTemplates().isEmpty()) {
            return 0;
        }
        List<InspectLibrary> toEnroll = new ArrayList<>();
        for (TemplateExtractor.ExtractedTemplate t : result.getTemplates()) {
            int idx = t.getIndex();
            if (idx < 0 || idx >= batch.size()) {
                continue;
            }
            toEnroll.add(templateExtractor.toLibrary(t, batch.get(idx), samples.get(idx)));
        }
        return libraryService.enroll(toEnroll);
    }

    /**
     * 把刚抽出的模版匹配回这批指纹，回写 template_id。
     *
     * <p>不做这一步，{@code selectWithoutTemplate} 下次还会捞到它们 ——
     * 实测同一条「校验账户绑定状态失败」被抽了两次，
     * 产出 {@code &#123;账户详情对象&#125;} 和 {@code &#123;账户详情&#125;} 两个模版，
     * 占位符名字不同导致唯一键也拦不住。
     */
    private int bindTemplates(List<InspectFingerprint> fingerprints) {
        templateMatcher.refresh();
        List<Map<String, Object>> items = new ArrayList<>();
        for (InspectFingerprint fp : fingerprints) {
            if (fp.getId() == null) {
                continue;
            }
            String raw = null;
            try {
                InspectSample sample = sampleMapper.selectLatestByFpId(fp.getId());
                raw = sample == null ? null : sample.getRawMessage();
            } catch (Exception ignored) {
                // 没样例就用归一文本匹配，匹配率低一些但不影响正确性
            }
            InspectLibrary hit = templateMatcher.match(fp, raw);
            if (hit == null) {
                continue;
            }
            Map<String, Object> it = new HashMap<>(2);
            it.put("id", fp.getId());
            it.put("templateId", hit.getId());
            items.add(it);
        }
        if (items.isEmpty()) {
            return 0;
        }
        int bound = 0;
        for (int i = 0; i < items.size(); i += 200) {
            List<Map<String, Object>> slice = items.subList(i, Math.min(i + 200, items.size()));
            try {
                fingerprintMapper.batchUpdateTemplateId(slice);
                bound += slice.size();
            } catch (Exception e) {
                log.warn("回写模版归属失败：{}", e.getMessage());
            }
        }
        return bound;
    }

    /**
     * 只回绑、不抽取：把现有模版库套到所有还没有模版归属的指纹上。
     *
     * <p>{@link #bindTemplates} 原本只在 AI 抽取之后跑，于是<b>手工新建模版后没有回绑入口</b> ——
     * 模版明明能匹配，指纹的 {@code template_id} 却一直是空，页面上看起来像「建了模版但没生效」，
     * 覆盖率统计也偏低。手工建完一批模版后调这个。
     *
     * <p>只补空值，不会改动已有归属。
     *
     * @param max 单次处理上限，防止一次扫全表
     * @return 实际回绑的指纹数
     */
    public int rebindAll(int max) {
        List<InspectFingerprint> pending =
                fingerprintMapper.selectWithoutTemplate(max <= 0 ? 2000 : max);
        if (pending.isEmpty()) {
            return 0;
        }
        int bound = bindTemplates(pending);
        log.info("【模版回绑】{} 个无归属指纹中，{} 个匹配到现有模版", pending.size(), bound);
        return bound;
    }

    /**
     * 保证有原始样例。
     *
     * <p>模版必须从原始日志抽 —— 归一后的 msg_tpl 已被正则破坏过
     * （{@code 返回代码：[} 就是砍尾砍出来的残句），拿它去抽等于让模型在残缺输入上猜。
     */
    private String ensureSample(InspectFingerprint fp, ExtractSummary summary) {
        if (fp.getId() != null) {
            try {
                InspectSample existing = sampleMapper.selectLatestByFpId(fp.getId());
                if (existing != null && notBlank(existing.getRawMessage())) {
                    return existing.getRawMessage();
                }
            } catch (Exception ignored) {
                // 落到下面重新抓
            }
        }
        long end = System.currentTimeMillis();
        try {
            SampleRow row = slsRepository.querySample(fp.getAppId(), fp.getLoggerClass(),
                    fp.getBizFrame(), fp.getMsgTpl(), fp.getExcCause(), end - SAMPLE_LOOKBACK_MS, end);
            if (row == null || !notBlank(row.getMessage())) {
                return null;
            }
            if (fp.getId() != null) {
                InspectSample sample = new InspectSample();
                sample.setFpId(fp.getId());
                sample.setRawMessage(row.getMessage());
                sample.setTraceId(row.getTraceId());
                sample.setPodName(row.getPodName());
                sample.setImageTag(row.imageTag());
                sample.setLogTime(new java.util.Date(
                        (row.getLogTimeSec() == null ? 0L : row.getLogTimeSec()) * 1000L));
                sampleMapper.batchInsert(Collections.singletonList(sample));
                summary.setSampleFetched(summary.getSampleFetched() + 1);
            }
            return row.getMessage();
        } catch (Exception e) {
            log.debug("抓取样例失败 fpId={}：{}", fp.getId(), e.getMessage());
            return null;
        }
    }

    private boolean notBlank(String s) {
        // 统一口径：字面量 "null" 不算内容（空指针的 exc_cause 就是它）
        return io.github.liufor02144.erroragent.service.InspectText.notBlank(s);
    }
}
