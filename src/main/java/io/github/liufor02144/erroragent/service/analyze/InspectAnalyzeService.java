package io.github.liufor02144.erroragent.service.analyze;

import io.github.liufor02144.erroragent.config.InspectSwitch;
import io.github.liufor02144.erroragent.dto.AnalysisResult;
import io.github.liufor02144.erroragent.dto.CodeLocateResult;
import io.github.liufor02144.erroragent.dto.JudgeResult;
import io.github.liufor02144.erroragent.entity.InspectAnalysis;
import io.github.liufor02144.erroragent.entity.InspectFingerprint;
import io.github.liufor02144.erroragent.entity.InspectSample;
import io.github.liufor02144.erroragent.mapper.InspectAnalysisMapper;
import io.github.liufor02144.erroragent.mapper.InspectFingerprintMapper;
import io.github.liufor02144.erroragent.mapper.InspectLlmUsageMapper;
import io.github.liufor02144.erroragent.mapper.InspectSampleMapper;
import io.github.liufor02144.erroragent.mapper.InspectStatMapper;
import io.github.liufor02144.erroragent.service.InspectConfigKeys;
import io.github.liufor02144.erroragent.service.InspectConfigService;
import io.github.liufor02144.erroragent.service.InspectText;
import io.github.liufor02144.erroragent.service.code.CodeLocator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * AI 分析编排：闸门 → 组装材料 → 调用模型 → 解析 → 落库。
 *
 * <p>闸门是成本可行性的前提，不是优化项：
 * 按条分析是 524 亿 tokens/天（不可行），按指纹全量是 127 万，
 * 而只分析新增/突增是 13 万 —— 相差四个数量级。
 */
@Slf4j
@Service
public class InspectAnalyzeService {

    private static final String SCENE = "ANALYZE";

    @Resource
    private InspectSwitch inspectSwitch;

    @Resource
    private InspectConfigService configService;

    @Resource
    private InspectFingerprintMapper fingerprintMapper;

    @Resource
    private InspectSampleMapper sampleMapper;

    @Resource
    private io.github.liufor02144.erroragent.service.collect.InspectSlsRepository slsRepository;

    @Resource
    private InspectStatMapper statMapper;

    @Resource
    private io.github.liufor02144.erroragent.config.InspectProperties properties;

    @Resource
    private InspectAnalysisMapper analysisMapper;

    @Resource
    private InspectLlmUsageMapper usageMapper;

    @Resource
    private CodeLocator codeLocator;

    @Resource
    private AnalysisPromptBuilder promptBuilder;

    @Resource
    private AnalysisResponseParser parser;

    @Resource
    private LlmClient llmClient;

    /**
     * 分析一个指纹。
     *
     * @param judge 判定结果，可为 null（手动触发时）
     * @param force 忽略开关与幂等，强制重新分析（验证用）
     * @return 分析结果，被闸门拦下时返回 null
     */
    public InspectAnalysis analyze(Long fpId, JudgeResult judge, boolean force) {
        InspectFingerprint fp = fingerprintMapper.selectById(fpId);
        if (fp == null) {
            log.warn("指纹不存在 fpId={}", fpId);
            return null;
        }

        // 日上限任何情况都要守，force 也不例外 —— 手动触发同样在烧额度，
        // 而事件调度会带 force=true 来跳过指纹级幂等
        String overLimit = checkDailyLimit();
        if (overLimit != null) {
            log.debug("AI 分析被闸门拦截 fpId={} 原因={}", fpId, overLimit);
            return null;
        }

        if (!force) {
            String blocked = checkGate(fp);
            if (blocked != null) {
                log.debug("AI 分析被闸门拦截 fpId={} 原因={}", fpId, blocked);
                return null;
            }
        }

        // 判定信息若未传入就从库里重建。
        // 分析任务与手动触发都不会传 judge，不补的话提示词里的「量级与判定」
        // 永远是空的 —— 而量级正是模型判严重度的关键依据：
        // 同一个异常出现 1662 次和 5 次，结论完全不同。
        if (judge == null) {
            judge = rebuildJudge(fp);
        } else if (judge.getCount24h() == 0) {
            // 调用方只给了状态与依据（如突增事件），量级仍按统计表补齐
            JudgeResult counts = rebuildJudge(fp);
            judge.setCount24h(counts.getCount24h());
            judge.setCurrentHourCount(counts.getCurrentHourCount());
        }

        // 组装材料：样本 → 同一请求的其他报错 → 代码定位
        InspectSample sample = freshSample(fp, sampleMapper.selectLatestByFpId(fpId));
        String rawMessage = sample == null ? null : sample.getRawMessage();
        List<String> siblings = siblingErrors(sample);
        CodeLocateResult locate = codeLocator.locate(fp, rawMessage);

        String systemPrompt = promptBuilder.systemPrompt();
        String userPrompt = promptBuilder.userPrompt(fp, judge, locate, sample, siblings);

        int maxInput = configService.getInt(InspectConfigKeys.LLM_MAX_INPUT_TOKENS, 8000);
        // 粗略按 1 token ≈ 1.5 字符估算，只为兜底防止超长请求被服务端直接拒绝
        if (userPrompt.length() > maxInput * 3) {
            userPrompt = userPrompt.substring(0, maxInput * 3) + "\n...（输入过长已截断）";
            log.warn("分析输入超长已截断 fpId={}", fpId);
        }

        LlmClient.LlmResponse response = llmClient.chat(SCENE, fpId, systemPrompt, userPrompt);
        if (!response.isSuccess()) {
            log.warn("AI 分析失败 fpId={} err={}", fpId, response.getErrorMsg());
            return null;
        }

        AnalysisResult parsed = parser.parse(response.getContent());
        if (parsed == null) {
            log.warn("AI 分析输出无法解析 fpId={}", fpId);
            return null;
        }

        InspectAnalysis analysis = toEntity(fp, judge, locate, response, parsed);
        // 同一请求的其他报错随分析落库，卡片上展示，人可以对着它核对 AI 的结论
        analysis.setTraceChain(siblings.isEmpty() ? null : siblingsForCard(siblings));
        analysisMapper.insert(analysis);
        // 同步到指纹表，供列表页筛选与推送条件判定
        fingerprintMapper.updateAnalysis(fpId, analysis.getCategory(), analysis.getSeverity());

        log.info("【AI 分析】fpId={} {}/{} 置信度={}(定位{}) tokens={}+{} 耗时={}ms | {}",
                fpId, analysis.getCategory(), analysis.getSeverity(),
                analysis.getConfidence(), analysis.getLocateConfidence(),
                analysis.getInputTokens(), analysis.getOutputTokens(),
                analysis.getElapsedMs(), fp.getFpKey());
        return analysis;
    }

    /**
     * 组装将要发送给模型的提示词，不调用模型。
     *
     * <p>与 {@link #analyze} 复用同一套组装逻辑 —— 预览的全部意义就是
     * 「看清实际会发出去什么」，两边各写一份迟早会不一致，
     * 那时调出来的提示词与线上跑的不是同一个东西。
     *
     * @return [systemPrompt, userPrompt]；指纹不存在时返回 null
     */
    public String[] buildPrompts(Long fpId) {
        InspectFingerprint fp = fingerprintMapper.selectById(fpId);
        if (fp == null) {
            return null;
        }
        InspectSample sample = freshSample(fp, sampleMapper.selectLatestByFpId(fpId));
        String rawMessage = sample == null ? null : sample.getRawMessage();
        return new String[]{
                promptBuilder.systemPrompt(),
                promptBuilder.userPrompt(fp, rebuildJudge(fp), codeLocator.locate(fp, rawMessage),
                        sample, siblingErrors(sample))
        };
    }

    /** 样本比最近一次出现早这么多，就视为过期 */
    private static final long SAMPLE_STALE_MS = 30 * 60_000L;

    /**
     * 样本过期就换成最近一次出现的那条。
     *
     * <p>样本只在指纹第一次出现时抓一次，之后不再更新。实测某个第三方支付取消订单的异常：指纹 3 天前就出现过，
     * 再出现时分析拿的仍是当初的样本和它的 traceId，查到的是 3 天前那次请求的链路，
     * 与卡片上的示例 traceId 对不上。
     */
    private InspectSample freshSample(InspectFingerprint fp, InspectSample current) {
        if (fp.getLastSeenTime() == null) {
            return current;
        }
        long last = fp.getLastSeenTime().getTime();
        if (current != null && current.getLogTime() != null
                && current.getLogTime().getTime() >= last - SAMPLE_STALE_MS) {
            return current;
        }
        try {
            io.github.liufor02144.erroragent.dto.SampleRow row = slsRepository.querySample(fp.getAppId(),
                    fp.getLoggerClass(), fp.getBizFrame(), fp.getMsgTpl(), fp.getExcCause(),
                    last - 15 * 60_000L, last + 2 * 60_000L);
            if (row == null || !io.github.liufor02144.erroragent.service.InspectText.notBlank(row.getMessage())) {
                return current;
            }
            InspectSample fresh = new InspectSample();
            fresh.setFpId(fp.getId());
            fresh.setRawMessage(row.getMessage());
            fresh.setTraceId(row.getTraceId());
            fresh.setPodName(row.getPodName());
            fresh.setImageTag(row.imageTag());
            fresh.setLogTime(new Date((row.getLogTimeSec() == null ? 0L : row.getLogTimeSec()) * 1000L));
            sampleMapper.batchInsert(java.util.Collections.singletonList(fresh));
            log.info("【样本刷新】fpId={} 原样本 {} 早于最近一次出现 {}，已换成最新一条",
                    fp.getId(), current == null ? "无" : current.getLogTime(), fp.getLastSeenTime());
            return fresh;
        } catch (Exception e) {
            log.warn("刷新样本失败 fpId={}：{}", fp.getId(), e.getMessage());
            return current;
        }
    }

    private static final int MAX_SIBLING_ERRORS = 8;

    /** 同一次请求里其他服务、其他位置打的 ERROR 日志，每条取「第一行 + 根因」 */
    private List<String> siblingErrors(InspectSample sample) {
        List<String> out = new ArrayList<>();
        if (sample == null || sample.getLogTime() == null
                || InspectText.normalizeTraceId(sample.getTraceId()) == null) {
            return out;
        }
        long t = sample.getLogTime().getTime();
        String own = io.github.liufor02144.erroragent.service.template.LogKeyText.of(sample.getRawMessage());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map<String, String> row : slsRepository.queryErrorsByTrace(
                sample.getTraceId(), t - 5 * 60_000L, t + 5 * 60_000L, 30)) {
            String key = io.github.liufor02144.erroragent.service.template.LogKeyText.of(row.get("message"));
            if (key.isEmpty() || key.equals(own) || !seen.add(key)) {
                continue;
            }
            String app = row.get("app_id") == null ? "?"
                    : properties == null ? row.get("app_id") : properties.getNaming().displayApp(row.get("app_id"));
            out.add("[" + app + "] " + (key.length() > 220 ? key.substring(0, 220) + "…" : key));
            if (out.size() >= MAX_SIBLING_ERRORS) {
                break;
            }
        }
        return out;
    }

    /** 卡片上展示的「同一次请求里的其他报错」 */
    private String siblingsForCard(List<String> siblings) {
        StringBuilder sb = new StringBuilder("同一次请求里的其他报错：\n");
        for (String e : siblings) {
            sb.append(e).append('\n');
        }
        String s = sb.toString();
        return s.length() <= 4000 ? s : s.substring(0, 4000);
    }

    /**
     * 从已落库的数据重建判定信息。
     *
     * <p>只取确定性事实：量级来自统计表，判定状态来自指纹表。
     * 基线不重算 —— 那是采集时判定的产物，事后重算没有意义，
     * 宁可不给也不给一个错的。
     */
    private JudgeResult rebuildJudge(InspectFingerprint fp) {
        JudgeResult judge = new JudgeResult();
        judge.setFpId(fp.getId());
        judge.setJudgeState(fp.getJudgeState());

        Date now = new Date();
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR_OF_DAY, -24);
        Long count24h = statMapper.sumCount(fp.getId(), cal.getTime(), now);
        judge.setCount24h(count24h == null ? 0L : count24h);

        Calendar hourStart = Calendar.getInstance();
        hourStart.set(Calendar.MINUTE, 0);
        hourStart.set(Calendar.SECOND, 0);
        hourStart.set(Calendar.MILLISECOND, 0);
        Long currentHour = statMapper.sumCount(fp.getId(), hourStart.getTime(), now);
        judge.setCurrentHourCount(currentHour == null ? 0L : currentHour);

        judge.setReason("量级由统计表汇总，判定状态取自最近一次采集");
        return judge;
    }

    /**
     * 闸门。任一条命中即跳过，返回拦截原因；放行返回 null。
     */
    /** 当日额度闸门。独立于 force —— 强制分析也不该把额度烧光 */
    private String checkDailyLimit() {
        int dailyLimit = configService.getInt(InspectConfigKeys.LLM_DAILY_LIMIT, 100);
        Calendar dayStart = Calendar.getInstance();
        dayStart.set(Calendar.HOUR_OF_DAY, 0);
        dayStart.set(Calendar.MINUTE, 0);
        dayStart.set(Calendar.SECOND, 0);
        dayStart.set(Calendar.MILLISECOND, 0);
        int used = usageMapper.countSince(dayStart.getTime());
        if (used >= dailyLimit) {
            // 熔断是为了防止指纹异常膨胀或幂等失效时把额度烧光
            log.error("AI 分析已达当日上限 {}/{}，本日剩余分析全部转人工。"
                    + "若属正常增长可调大 llm.daily.limit", used, dailyLimit);
            return "已达当日调用上限 " + dailyLimit;
        }
        return null;
    }

    private String checkGate(InspectFingerprint fp) {
        String blockedBySwitch = inspectSwitch.whyBlocked("LLM");
        if (blockedBySwitch != null) {
            return blockedBySwitch;
        }
        if ("SUPPRESSED".equals(fp.getStatus())) {
            return "指纹已被抑制";
        }

        int dedupHours = configService.getInt(InspectConfigKeys.LLM_DEDUP_HOURS, 24);
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR_OF_DAY, -dedupHours);
        if (analysisMapper.countSince(fp.getId(), cal.getTime()) > 0) {
            return dedupHours + " 小时内已分析过，复用既有结论";
        }

        return null;
    }

    private InspectAnalysis toEntity(InspectFingerprint fp, JudgeResult judge, CodeLocateResult locate,
                                     LlmClient.LlmResponse response, AnalysisResult parsed) {
        InspectAnalysis a = new InspectAnalysis();
        a.setFpId(fp.getId());
        a.setCategory(parsed.getCategory());
        a.setSeverity(parsed.getSeverity());
        // 「是否首次出现」是系统事实，不取模型的说法
        a.setIsFirstSeen(judge != null && JudgeResult.NEW.equals(judge.getJudgeState()));
        a.setRootCause(parsed.getRootCause());
        a.setBusinessImpact(parsed.getBusinessImpact());
        a.setImpactNote(parsed.getImpactNote());
        a.setConfidence(parsed.getConfidence());
        a.setModel(response.getModel());
        a.setInputTokens(response.getInputTokens());
        a.setOutputTokens(response.getOutputTokens());
        a.setElapsedMs(response.getElapsedMs());
        if (locate != null) {
            a.setLocateConfidence(locate.getConfidence());
            a.setCodeSnippet(locate.getSnippet());
            if (locate.getFilePath() != null) {
                a.setCodeRef(locate.getRepoName() + "/" + locate.getFilePath()
                        + (locate.getResolvedLine() == null ? "" : ":" + locate.getResolvedLine()));
            }
        }
        return a;
    }

    /** 最近一次分析结果，供页面与推送读取 */
    public InspectAnalysis latest(Long fpId) {
        return analysisMapper.selectLatestByFpId(fpId);
    }
}
