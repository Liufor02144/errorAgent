package io.github.liufor02144.erroragent.service.collect;

import io.github.liufor02144.erroragent.config.InspectProperties;
import io.github.liufor02144.erroragent.config.InspectSwitch;
import io.github.liufor02144.erroragent.dto.CollectResult;
import io.github.liufor02144.erroragent.dto.FingerprintRow;
import io.github.liufor02144.erroragent.dto.JudgeResult;
import io.github.liufor02144.erroragent.dto.SampleRow;
import io.github.liufor02144.erroragent.entity.InspectEvent;
import io.github.liufor02144.erroragent.entity.InspectFingerprint;
import io.github.liufor02144.erroragent.entity.InspectSample;
import io.github.liufor02144.erroragent.entity.InspectStat;
import io.github.liufor02144.erroragent.mapper.InspectFingerprintMapper;
import io.github.liufor02144.erroragent.mapper.InspectSampleMapper;
import io.github.liufor02144.erroragent.mapper.InspectStatMapper;
import io.github.liufor02144.erroragent.service.judge.InspectJudgeService;
import io.github.liufor02144.erroragent.service.InspectConfigKeys;
import io.github.liufor02144.erroragent.service.InspectConfigService;
import io.github.liufor02144.erroragent.service.judge.InspectEventService;
import io.github.liufor02144.erroragent.service.judge.InspectLibraryService;
import io.github.liufor02144.erroragent.entity.InspectLibrary;
import io.github.liufor02144.erroragent.dto.EventAggregate;
import io.github.liufor02144.erroragent.service.template.TemplateExtractor;
import io.github.liufor02144.erroragent.service.template.TemplateMatcher;
import io.github.liufor02144.erroragent.service.template.TemplateReviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 采集编排：SLS 聚合 → 指纹入库 → 时序统计 → 判定 → 样本拉取。
 */
@Slf4j
@Service
public class InspectCollectService {

    /** SLS 聚合返回行数上限。按小时分组后行数会翻倍，故留足余量（实测 1 小时 1068 个指纹） */
    private static final int AGGREGATE_LIMIT = 5000;

    /** 批量写库分片大小，避免单条 SQL 过长 */
    private static final int BATCH_SIZE = 500;

    @Resource
    private InspectSwitch inspectSwitch;

    @Resource
    private InspectProperties properties;

    @Resource
    private InspectSlsRepository slsRepository;

    @Resource
    private InspectFingerprintMapper fingerprintMapper;

    @Resource
    private InspectStatMapper statMapper;

    @Resource
    private InspectSampleMapper sampleMapper;

    @Resource
    private InspectJudgeService judgeService;

    
    @Resource
    private InspectLibraryService libraryService;

    @Resource
    private InspectEventService eventService;

    @Resource
    private TemplateMatcher templateMatcher;

    @Resource
    private io.github.liufor02144.erroragent.service.template.TemplateFallbackService templateFallback;

    @Resource
    private TemplateExtractor templateExtractor;

    @Resource
    private TemplateReviewService templateReviewService;

    @Resource
    private InspectConfigService configService;

    @Resource
    private io.github.liufor02144.erroragent.service.run.InspectRunRecorder runRecorder;

    /**
     * 抽取冷却：fpHash → 上次送抽取的时刻。
     *
     * <p>放内存不放库：重启后最多每个异常多抽一次，不值得为此加表；
     * 多节点时各算各的，同理可接受。
     */
    private final Map<String, Long> extractCooldown = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 执行一轮采集。
     *
     * @param force 忽略开关强制执行（手动触发验证用）
     */
    public CollectResult collectOnce(boolean force) {
        long began = System.currentTimeMillis();
        CollectResult result = new CollectResult();
        result.setBatchId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));

        if (!force && !inspectSwitch.collectAllowed()) {
            // 开关关着不记：否则停用期间每 5 分钟一条空记录
            result.setSuccess(false);
            result.setMessage("巡检总开关未开启（erroragent.enabled=false）");
            return result;
        }
        // 先开记录再采集：采集失败同样要留一条，「这轮为什么没报」得有地方看
        result.setRunId(runRecorder.start(result.getBatchId(), force));
        try {
            return doCollect(result, began, force);
        } finally {
            runRecorder.finish(result.getRunId(), result);
        }
    }

    private CollectResult doCollect(CollectResult result, long began, boolean force) {
        if (!properties.getSls().ready()) {
            result.setSuccess(false);
            result.setMessage("SLS 配置不完整，请检查 erroragent.sls.* 配置");
            return result;
        }

        try {
            long[] window = resolveWindow();
            result.setWindow(fmt(window[0]) + " ~ " + fmt(window[1]));

            List<FingerprintRow> rows = slsRepository.queryFingerprints(window[0], window[1], AGGREGATE_LIMIT);
            result.setSlsRows(rows.size());
            if (rows.isEmpty()) {
                result.setMessage("窗口内无 ERROR 日志");
                result.setElapsedMs(System.currentTimeMillis() - began);
                return result;
            }
            if (rows.size() >= AGGREGATE_LIMIT) {
                // 触顶意味着有指纹被静默丢弃，必须显式告警而不是当成正常结果
                log.error("SLS 聚合返回行数达到上限 {}，存在指纹被丢弃。需上调 AGGREGATE_LIMIT 或缩小窗口", AGGREGATE_LIMIT);
            }

            // 同一指纹会因跨小时产生多行，按 fp_key 归并
            Map<String, List<FingerprintRow>> grouped = groupByFpKey(rows);
            result.setFingerprints(grouped.size());

            // 关键：必须在 upsert 之前取快照。
            // upsert 会刷新 last_seen_time，之后就无法判断「沉寂复发」，也分不出哪些是首次出现。
            Map<String, InspectFingerprint> previousByHash = loadPreviousByHash(grouped.keySet());

            // 冷启动 = 库里一条指纹都没有（首次部署），同样必须在 upsert 之前判断。
            // 不能用「本轮快照为空」：一轮里全是新指纹时快照也是空的，
            // 系统平时安静、突然冒出全新异常的那一轮会被当成冷启动整轮吞掉
            boolean coldStart = fingerprintMapper.selectAnyId() == null;

            List<InspectFingerprint> toUpsert = new ArrayList<>(grouped.size());
            long totalLogs = 0L;
            for (Map.Entry<String, List<FingerprintRow>> e : grouped.entrySet()) {
                String fpHash = md5(e.getKey());
                boolean isNew = !previousByHash.containsKey(fpHash);
                toUpsert.add(toFingerprint(e.getKey(), fpHash, e.getValue(), isNew));
                for (FingerprintRow r : e.getValue()) {
                    totalLogs += r.getCnt();
                }
            }
            result.setTotalLogs(totalLogs);

            batchUpsertFingerprints(toUpsert);

            // upsert 不回填主键，重新查一次拿 id（一条 SQL）
            Map<String, Long> idByHash = loadIdByHash(grouped.keySet());

            result.setStatRows(writeStats(grouped, idByHash, result.getBatchId()));

            // stat 是覆盖语义，累计次数只能按 SUM 回填，不能累加
            syncTotalCount(idByHash.values());

            if (coldStart) {
                log.warn("【巡检采集】首轮采集：库中无历史指纹，本轮只建立基线与抽取模版，不产生事件");
            }

            // 模版匹配先于判定：判据是模版上的「业务预期」标记，
            // 而匹配要读原始样例，在判定里重新匹配只能拿归一后的残文本去撞
            Map<Long, InspectLibrary> matchedByFpId = matchTemplates(grouped, idByHash);
            result.setMatchedCount(matchedByFpId.size());
            result.setTemplateCount(distinctTemplates(matchedByFpId));
            result.setUnmatchedCount(grouped.size() - matchedByFpId.size());

            List<JudgeResult> judged = judgeAll(coldStart, grouped, idByHash, matchedByFpId, result);
            applyJudgeResults(judged, matchedByFpId);

            result.setSampleCount(fetchSamples(grouped, idByHash, judged, window));

            // 事件按【模版】聚合：order 和 product 的同一个 Redis 超时是一件事。
            // 必须放在样本抓取之后 —— 卡片要带原始报错
            result.setEventCount(recordEvents(judged, grouped, idByHash, matchedByFpId, result.getRunId()));

            // 先收敛再记新事件会把本轮刚累计上的事件误关，顺序不能调
            eventService.autoCloseQuiet();

            // 没匹配上模版的送大模型抽模版。放在最后：它可能失败或被额度拦住，
            // 而前面的采集、判定、事件都不该受影响
            result.setTemplateExtracted(extractTemplates(grouped, idByHash, matchedByFpId, window));

            // 新抽/新兜底出来的模版交给 AI 复核，能确定是业务预期的直接标好。
            // 放在抽取之后：先有模版才有得复核。
            // 和抽取一样受 llm 开关管控，失败不影响本轮其他结果
            reviewNewTemplates();


            result.setElapsedMs(System.currentTimeMillis() - began);
            warnIfSlowerThanInterval(result.getElapsedMs());
            log.info("【巡检采集】{}", result.brief());
            logActionable(judged, grouped, idByHash);
            return result;
        } catch (Exception ex) {
            result.setSuccess(false);
            result.setMessage(ex.getMessage());
            result.setElapsedMs(System.currentTimeMillis() - began);
            log.error("【巡检采集】失败 batch={}", result.getBatchId(), ex);
            return result;
        }
    }

    /**
     * 采集窗口：最近两个分桶（默认 10 分钟）。
     *
     * <p><b>为什么是两个桶而不是一个</b>：一轮跑在桶的中间时（比如 14:27 这轮
     * 覆盖 14:17~14:27），14:25 那个桶只被数了一半，写进去是个偏小的值。
     * 下一轮 14:32 覆盖 14:22~14:32，把 14:25 这个桶<b>完整</b>包含进来，
     * 用终值覆盖掉那个半截值。所以窗口 = 2 × 桶，每个桶都必然被完整覆盖一次。
     *
     * <p><b>为什么不用累加</b>：窗口重叠就会重复计数 ——
     * 事件量级曾因此虚高 2~3 倍。覆盖写入不怕重叠，代价只是窗口要盖住整个桶。
     *
     * <p>多给一个桶的余量，容忍单轮抖动与偶发失败：一轮没跑成，
     * 下一轮的窗口仍然盖得住它漏掉的桶。
     */
    private long[] resolveWindow() {
        long now = System.currentTimeMillis();
        long bucketMs = BUCKET_SECONDS * 1000L;
        // 向下对齐到桶边界再回退，保证窗口边界永远落在桶边界上
        long alignedNow = now - (now % bucketMs);
        long from = alignedNow - WINDOW_BUCKETS * bucketMs;
        return new long[]{from, now};
    }

    /** 分桶粒度（秒），与 {@code InspectSqlBuilder.BUCKET_SECONDS} 必须一致 */
    private static final int BUCKET_SECONDS = 300;

    /** 判突增时比较的桶数：最近 2 个完整的 5 分钟桶，即 10 分钟 */
    private static final int SURGE_WINDOW_BUCKETS = 2;

    /** 窗口覆盖几个桶。必须 ≥ 2，否则边界上的桶永远只被数到一半 */
    private static final int WINDOW_BUCKETS = 2;

    /**
     * 一轮跑得比采集间隔还久，说明后续轮次会被「上一轮未完成」挡掉。
     *
     * <p>这不是性能优化问题而是可用性问题：巡检会事实上停摆，
     * 而且停摆是静默的 —— 没有这条日志就只能靠人发现「怎么好久没告警了」。
     */
    private void warnIfSlowerThanInterval(long elapsedMs) {
        int intervalSeconds = configService.getInt(
                InspectConfigKeys.COLLECT_SCAN_INTERVAL_SECONDS, 300);
        if (elapsedMs > intervalSeconds * 1000L) {
            log.error("【巡检采集】本轮耗时 {} 秒，已超过采集间隔 {} 秒，"
                            + "后续轮次会被跳过。请检查 SLS 查询耗时或缩小窗口",
                    elapsedMs / 1000, intervalSeconds);
        }
    }

    /**
     * 自动复核本轮新产生的模版。
     *
     * <p>不做的话兜底模版会一直堆在「系统故障」里等人工逐条点 ——
     * 而其中相当一部分一眼就是业务预期。
     *
     * <p>吞掉异常：复核是锦上添花，失败了下一轮还有机会，
     * 不能让它把已经采集判定完的一轮整个带崩。
     */
    private void reviewNewTemplates() {
        try {
            TemplateReviewService.ReviewSummary s = templateReviewService.reviewNewlyCreated();
            if (s.getReviewed() > 0 || s.getKeptAsFault() > 0) {
                log.info("【模版自动复核】复核 {} 个：标为业务预期 {} 个，判为故障 {} 个，转人工 {} 个",
                        s.getReviewed(), s.getAutoMarked(), s.getKeptAsFault(), s.getNeedHuman());
            }
        } catch (Exception e) {
            log.warn("模版自动复核失败，不影响本轮采集：{}", e.getMessage());
        }
    }

    private Map<String, List<FingerprintRow>> groupByFpKey(List<FingerprintRow> rows) {
        Map<String, List<FingerprintRow>> grouped = new LinkedHashMap<>();
        for (FingerprintRow r : rows) {
            grouped.computeIfAbsent(r.getFpKey(), k -> new ArrayList<>()).add(r);
        }
        return grouped;
    }

    /** 采集前的指纹快照，键为 fpHash */
    private Map<String, InspectFingerprint> loadPreviousByHash(Collection<String> fpKeys) {
        List<String> hashes = new ArrayList<>();
        for (String key : fpKeys) {
            hashes.add(md5(key));
        }
        Map<String, InspectFingerprint> map = new HashMap<>();
        for (List<String> part : partition(hashes, BATCH_SIZE)) {
            List<InspectFingerprint> list = fingerprintMapper.selectByHashes(part);
            if (list != null) {
                for (InspectFingerprint f : list) {
                    map.put(f.getFpHash(), f);
                }
            }
        }
        return map;
    }

    private Map<String, Long> loadIdByHash(Collection<String> fpKeys) {
        List<String> hashes = new ArrayList<>();
        for (String key : fpKeys) {
            hashes.add(md5(key));
        }
        Map<String, Long> idByHash = new HashMap<>();
        for (List<String> part : partition(hashes, BATCH_SIZE)) {
            List<InspectFingerprint> list = fingerprintMapper.selectByHashes(part);
            if (list != null) {
                for (InspectFingerprint f : list) {
                    idByHash.put(f.getFpHash(), f.getId());
                }
            }
        }
        return idByHash;
    }

    private InspectFingerprint toFingerprint(String fpKey, String fpHash,
                                             List<FingerprintRow> hours, boolean isNew) {
        FingerprintRow any = hours.get(0);
        long minTs = Long.MAX_VALUE;
        long maxTs = 0L;
        for (FingerprintRow r : hours) {
            minTs = Math.min(minTs, r.getHourSec());
            maxTs = Math.max(maxTs, r.getLastTsSec() > 0 ? r.getLastTsSec() : r.getHourSec());
        }

        InspectFingerprint fp = new InspectFingerprint();
        fp.setFpHash(fpHash);
        fp.setFpKey(truncate(fpKey, 1000));
        fp.setAppId(any.getAppId());
        // 指标型日志跨服务合并后 app_id 只是任取的一个，靠 appCount 表达「多服务」
        fp.setAppCount(any.getAppCount());
        fp.setHasStack(any.hasStack());
        // 'NONE' 是聚合 SQL 里 coalesce 的兜底占位，不是异常类名。
        // 落库会让它一路漏到页面和卡片上 —— 实测 193 个指纹、44 万条日志
        // 在列表里显示成「NONE」，而这类日志真正可读的是 msg_tpl
        fp.setExcSimple(placeholderToNull(any.getExcSimple()));
        // 有堆栈时 logger 不参与指纹，但仍记录下来便于样本反查与展示
        fp.setLoggerClass(any.getLoggerClass());
        fp.setBizFrame(placeholderToNull(any.getBizFrame()));
        fp.setMsgTpl(truncate(any.getMsgTpl(), 500));
        fp.setExcCause(truncate(blankToNull(any.getExcCause()), 200));
        // 首现时间对已存在指纹不会被覆盖（见 XML 注释），这里的值只在插入时生效
        fp.setFirstSeenTime(new Date(minTs * 1000L));
        fp.setLastSeenTime(new Date(maxTs * 1000L));
        fp.setTotalCount(0L);
        fp.setStatus("ACTIVE");
        fp.setJudgeState(isNew ? JudgeResult.NEW : JudgeResult.QUIET);
        return fp;
    }

    private void batchUpsertFingerprints(List<InspectFingerprint> list) {
        for (List<InspectFingerprint> part : partition(list, BATCH_SIZE)) {
            fingerprintMapper.batchUpsert(part);
        }
    }

    private int writeStats(Map<String, List<FingerprintRow>> grouped,
                           Map<String, Long> idByHash, String batchId) {
        List<InspectStat> stats = new ArrayList<>();
        for (Map.Entry<String, List<FingerprintRow>> e : grouped.entrySet()) {
            Long fpId = idByHash.get(md5(e.getKey()));
            if (fpId == null) {
                continue;
            }
            for (FingerprintRow r : e.getValue()) {
                InspectStat stat = new InspectStat();
                stat.setFpId(fpId);
                stat.setStatHour(new Date(r.getHourSec() * 1000L));
                stat.setCnt((int) Math.min(r.getCnt(), Integer.MAX_VALUE));
                stat.setBatchId(batchId);
                stats.add(stat);
            }
        }
        for (List<InspectStat> part : partition(stats, BATCH_SIZE)) {
            statMapper.batchUpsert(part);
        }
        return stats.size();
    }

    private void syncTotalCount(Collection<Long> ids) {
        for (List<Long> part : partition(new ArrayList<>(ids), BATCH_SIZE)) {
            if (!part.isEmpty()) {
                fingerprintMapper.syncTotalCount(part);
            }
        }
    }

    /**
     * 逐个判定。判定必须在 stat 写入之后 —— 基线与 24h 量都来自 stat 表。
     */

    private List<JudgeResult> judgeAll(boolean coldStart,
                                       Map<String, List<FingerprintRow>> grouped,
                                       Map<String, Long> idByHash,
                                       Map<Long, InspectLibrary> matchedByFpId,
                                       CollectResult result) {
        // 突增比的是「最近 2 个完整的 5 分钟桶」，基线也按同样长度折算。
        // 之前用「本小时到现在」比「整小时均值」，整点刚过时当前量天然偏小，突增判不出来
        long nowSec = System.currentTimeMillis() / 1000L;
        long alignedNowSec = nowSec - nowSec % BUCKET_SECONDS;
        long recentFromSec = alignedNowSec - SURGE_WINDOW_BUCKETS * BUCKET_SECONDS;
        int recentMinutes = SURGE_WINDOW_BUCKETS * BUCKET_SECONDS / 60;
        Calendar hourCal = Calendar.getInstance();
        hourCal.setTimeInMillis(recentFromSec * 1000L);
        hourCal.set(Calendar.MINUTE, 0);
        hourCal.set(Calendar.SECOND, 0);
        hourCal.set(Calendar.MILLISECOND, 0);
        Date windowHour = hourCal.getTime();
        List<JudgeResult> judged = new ArrayList<>();

        for (Map.Entry<String, List<FingerprintRow>> e : grouped.entrySet()) {
            String fpHash = md5(e.getKey());
            Long fpId = idByHash.get(fpHash);
            if (fpId == null) {
                continue;
            }
            InspectFingerprint current = toFingerprint(e.getKey(), fpHash, e.getValue(), false);
            current.setId(fpId);

            long recentCount = 0L;
            for (FingerprintRow r : e.getValue()) {
                if (r.getHourSec() >= recentFromSec && r.getHourSec() < alignedNowSec) {
                    recentCount += r.getCnt();
                }
            }

            JudgeResult jr = judgeService.judge(current, matchedByFpId.get(fpId),
                    coldStart, recentCount, recentMinutes, windowHour);
            judged.add(jr);

            if (jr.isInLibrary()) {
                result.setInLibraryCount(result.getInLibraryCount() + 1);
            }
            if (jr.isNeedAi()) {
                result.setNeedAiCount(result.getNeedAiCount() + 1);
            }
            switch (jr.getJudgeState()) {
                case JudgeResult.NEW:
                    result.setNewCount(result.getNewCount() + 1);
                    if (result.getNewFpKeys().size() < 50) {
                        result.getNewFpKeys().add(e.getKey());
                    }
                    break;
                case JudgeResult.SURGE:
                    result.setSurgeCount(result.getSurgeCount() + 1);
                    break;
                default:
                    result.setQuietCount(result.getQuietCount() + 1);
            }
        }
        return judged;
    }

    /** 回写判定结果与模版归属，并累加模版命中次数（长期零命中的模版可以清理） */
    private void applyJudgeResults(List<JudgeResult> judged,
                                   Map<Long, InspectLibrary> matchedByFpId) {
        List<Map<String, Object>> stateItems = new ArrayList<>();
        Map<Long, Long> hitByItem = new HashMap<>();

        for (JudgeResult jr : judged) {
            Map<String, Object> item = new HashMap<>(2);
            item.put("id", jr.getFpId());
            item.put("judgeState", jr.getJudgeState());
            stateItems.add(item);

            InspectLibrary hit = matchedByFpId.get(jr.getFpId());
            if (hit != null) {
                hitByItem.merge(hit.getId(), 1L, Long::sum);
            }
        }

        // 指纹的模版归属回写，页面上要能从指纹看到它归到了哪个模版
        List<Map<String, Object>> tplItems = new ArrayList<>();
        for (Map.Entry<Long, InspectLibrary> e : matchedByFpId.entrySet()) {
            Map<String, Object> it = new HashMap<>(2);
            it.put("id", e.getKey());
            it.put("templateId", e.getValue().getId());
            tplItems.add(it);
        }
        for (List<Map<String, Object>> part : partition(tplItems, 200)) {
            if (!part.isEmpty()) {
                fingerprintMapper.batchUpdateTemplateId(part);
            }
        }

        for (List<Map<String, Object>> part : partition(stateItems, 200)) {
            fingerprintMapper.batchUpdateJudgeState(part);
        }
        for (Map.Entry<Long, Long> e : hitByItem.entrySet()) {
            libraryService.recordHits(java.util.Collections.singletonList(e.getKey()), e.getValue());
        }
    }

    /**
     * 模版匹配：把粗指纹归到已有模版上。
     *
     * <p>用<b>原始样例</b>匹配而不是归一后的文本 —— 归一规则会把内容砍坏
     * （{@code 返回代码：[} 就是砍尾砍出来的残句），拿残句去撞正则必然匹配不上。
     * 样例来自上一轮抓取，首次出现的指纹这一轮可能匹配不上，下一轮就有了。
     */
    private Map<Long, InspectLibrary> matchTemplates(Map<String, List<FingerprintRow>> grouped,
                                                     Map<String, Long> idByHash) {
        List<InspectFingerprint> fps = new ArrayList<>(grouped.size());
        Map<Long, String> rawByFpId = new HashMap<>();

        for (Map.Entry<String, List<FingerprintRow>> e : grouped.entrySet()) {
            String fpHash = md5(e.getKey());
            Long fpId = idByHash.get(fpHash);
            if (fpId == null) {
                continue;
            }
            InspectFingerprint fp = toFingerprint(e.getKey(), fpHash, e.getValue(), false);
            fp.setId(fpId);
            fps.add(fp);
            try {
                InspectSample sample = sampleMapper.selectLatestByFpId(fpId);
                if (sample != null && sample.getRawMessage() != null) {
                    rawByFpId.put(fpId, sample.getRawMessage());
                }
            } catch (Exception ex) {
                // 取不到样例只会降低匹配率，不该让整轮采集失败
                log.debug("读取样例失败 fpId={}：{}", fpId, ex.getMessage());
            }
        }

        Map<Long, InspectLibrary> matched = templateMatcher.matchAll(fps, rawByFpId);
        log.info("【模版匹配】指纹 {} 个，命中 {} 个，未命中 {} 个（模版库 {} 条）",
                fps.size(), matched.size(), fps.size() - matched.size(), templateMatcher.size());
        return matched;
    }

    /**
     * 把判定结果落成异常事件，<b>按模版聚合</b>。
     *
     * <p>模版是唯一的聚合键：order 和 product 同时 Redis 超时是一件事，
     * 不是两件。涉及了哪些服务、哪些代码位置记在事件上展示 ——
     * 聚合掉的是身份，不是信息。
     *
     * <p>还没匹配到模版的指纹按指纹自身成事件（退化行为），
     * 否则首次出现的异常会因为「没有模版」而彻底不报。
     */
    private static int distinctTemplates(Map<Long, InspectLibrary> matchedByFpId) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (InspectLibrary l : matchedByFpId.values()) {
            if (l != null && l.getId() != null) {
                ids.add(l.getId());
            }
        }
        return ids.size();
    }

    private int recordEvents(List<JudgeResult> judged,
                             Map<String, List<FingerprintRow>> grouped,
                             Map<String, Long> idByHash,
                             Map<Long, InspectLibrary> matchedByFpId,
                             Long runId) {
        Map<Long, String> keyById = new HashMap<>();
        for (String fpKey : grouped.keySet()) {
            Long id = idByHash.get(md5(fpKey));
            if (id != null) {
                keyById.put(id, fpKey);
            }
        }

        // 先按模版把本轮的判定结果归并，再逐个模版落事件
        Map<String, EventAggregate> byTemplate = new LinkedHashMap<>();
        for (JudgeResult jr : judged) {
            if (!jr.isActionable() || jr.getFpId() == null) {
                continue;
            }
            String fpKey = keyById.get(jr.getFpId());
            List<FingerprintRow> rows = fpKey == null ? null : grouped.get(fpKey);
            if (rows == null || rows.isEmpty()) {
                continue;
            }
            InspectFingerprint fp = toFingerprint(fpKey, md5(fpKey), rows, false);
            fp.setId(jr.getFpId());

            InspectLibrary tpl = matchedByFpId.get(jr.getFpId());
            if (tpl == null) {
                // 事件必须有模版归属：没有模版就当场兜一个出来。
                // 否则页面上会出现「未归类」事件 —— 同一个异常因为一条走 msg_tpl、
                // 一条走 exc_cause 被拆成两条事件（「账户已绑定第三方账号」就是这么来的），
                // 而且系统里没有任何可标注、可复核的载体。
                tpl = templateFallback.ensureTemplate(fp);
                if (tpl != null) {
                    matchedByFpId.put(jr.getFpId(), tpl);
                }
            }
            if (tpl == null) {
                // 连兜底模版都造不出来（文本太短，正则会匹配一切），
                // 宁可不落事件也不落一条无归属的 —— 下一轮有样例了再说
                log.warn("跳过无法建立模版归属的异常：fpId={} exc={} msg={}",
                        jr.getFpId(), fp.getExcSimple(), fp.getMsgTpl());
                continue;
            }

            // 代码缺陷（空指针、越界…）按代码位置分开：不同位置是不同的 bug。
            // 否则「NullPointerException」这种一个词的模版会把所有服务的空指针
            // 并成一个事件，新出现的空指针只会悄悄累计到旧事件上
            boolean perLocation = io.github.liufor02144.erroragent.service.template.TechnicalFaultRule
                    .isCodeDefect(fp.getExcSimple())
                    && fp.getBizFrame() != null && !"NO_STACK".equals(fp.getBizFrame());
            String groupKey = perLocation
                    ? "T" + tpl.getId() + "@" + fp.getAppId() + "|" + fp.getBizFrame()
                    : "T" + tpl.getId();
            EventAggregate agg = byTemplate.get(groupKey);
            if (agg == null) {
                agg = new EventAggregate(tpl, jr);
                agg.setPerLocation(perLocation);
                byTemplate.put(groupKey, agg);
            }
            agg.add(fp, jr, sumCnt(rows));
        }

        int count = 0;
        for (EventAggregate agg : byTemplate.values()) {
            try {
                InspectEvent recorded = eventService.record(agg);
                if (recorded != null) {
                    count++;
                    runRecorder.event(runId, recorded);
                }
            } catch (Exception ex) {
                log.warn("登记异常事件失败 template={}：{}", agg.templateId(), ex.getMessage());
            }
        }
        return count;
    }

    /**
     * 对没匹配上模版的指纹抽模版。
     *
     * <p>抽取前先确保有原始样例 —— 模版要从原始日志里抽，拿归一后的残句
     * 只会让模型在残缺输入上猜。没有样例的当轮抓一次。
     *
     * @return 本轮新增的模版数
     */
    private int extractTemplates(Map<String, List<FingerprintRow>> grouped,
                                 Map<String, Long> idByHash,
                                 Map<Long, InspectLibrary> matchedByFpId,
                                 long[] window) {
        if (!inspectSwitch.llmAllowed()) {
            return 0;
        }
        int maxPerRound = configService.getInt(InspectConfigKeys.TEMPLATE_EXTRACT_MAX_PER_ROUND, 60);
        int batchSize = configService.getInt(InspectConfigKeys.TEMPLATE_EXTRACT_BATCH_SIZE, 15);
        int minLength = configService.getInt(InspectConfigKeys.TEMPLATE_MATCH_MIN_LENGTH, 6);

        // 量级大的先抽：模版库要先覆盖占日志量最多的那批
        long now = System.currentTimeMillis();
        long retryMs = Math.max(1, configService.getInt(
                InspectConfigKeys.TEMPLATE_EXTRACT_RETRY_HOURS, 24)) * 3600_000L;
        extractCooldown.values().removeIf(t -> now - t > retryMs);
        List<Map.Entry<String, List<FingerprintRow>>> pending = new ArrayList<>();
        int cooling = 0;
        for (Map.Entry<String, List<FingerprintRow>> e : grouped.entrySet()) {
            Long fpId = idByHash.get(md5(e.getKey()));
            if (fpId != null && !matchedByFpId.containsKey(fpId)) {
                if (extractCooldown.containsKey(md5(e.getKey()))) {
                    cooling++;
                    continue;
                }
                pending.add(e);
            }
        }
        if (cooling > 0) {
            log.debug("【模版抽取】{} 个异常近期抽取失败，冷却中不重试", cooling);
        }
        if (pending.isEmpty()) {
            return 0;
        }
        pending.sort((a, b) -> Long.compare(sumCnt(b.getValue()), sumCnt(a.getValue())));
        if (pending.size() > maxPerRound) {
            log.info("【模版抽取】待抽取 {} 个，本轮只处理量级最大的 {} 个，其余下轮继续",
                    pending.size(), maxPerRound);
            pending = pending.subList(0, maxPerRound);
        }

        int extracted = 0;
        for (int i = 0; i < pending.size(); i += batchSize) {
            List<Map.Entry<String, List<FingerprintRow>>> batch =
                    pending.subList(i, Math.min(i + batchSize, pending.size()));
            try {
                extracted += extractBatch(batch, idByHash, window, minLength);
            } catch (Exception e) {
                log.warn("模版抽取批次失败（第 {} 批）：{}", i / batchSize + 1, e.getMessage());
            }
            // 送过一次就进冷却，不论成败：成功的下一轮已能匹配上模版，本来就不会再进来；
            // 失败的（兜底模版都建不出来的短文本、模型产出过不了校验）每轮重试只会得到同样的结果。
            // 实测同一条异常连续多轮抽取、每次 0 个通过，单次输出上万 token
            for (Map.Entry<String, List<FingerprintRow>> e : batch) {
                extractCooldown.put(md5(e.getKey()), now);
            }
        }
        return extracted;
    }

    private int extractBatch(List<Map.Entry<String, List<FingerprintRow>>> batch,
                             Map<String, Long> idByHash, long[] window, int minLength) {
        List<InspectFingerprint> fps = new ArrayList<>(batch.size());
        List<String> samples = new ArrayList<>(batch.size());

        for (Map.Entry<String, List<FingerprintRow>> e : batch) {
            String fpHash = md5(e.getKey());
            Long fpId = idByHash.get(fpHash);
            InspectFingerprint fp = toFingerprint(e.getKey(), fpHash, e.getValue(), false);
            fp.setId(fpId);
            fps.add(fp);
            samples.add(ensureSample(fp, e.getValue().get(0), window));
        }

        TemplateExtractor.ExtractResult result =
                templateExtractor.extract(fps, samples, minLength);
        if (!result.isSuccess() || result.getTemplates().isEmpty()) {
            return 0;
        }
        List<InspectLibrary> toEnroll = new ArrayList<>();
        for (TemplateExtractor.ExtractedTemplate t : result.getTemplates()) {
            int idx = t.getIndex();
            if (idx < 0 || idx >= fps.size()) {
                continue;
            }
            toEnroll.add(templateExtractor.toLibrary(t, fps.get(idx), samples.get(idx)));
        }
        return libraryService.enroll(toEnroll);
    }

    /** 保证有原始样例：库里有就用，没有就当轮抓一次 */
    private String ensureSample(InspectFingerprint fp, FingerprintRow any, long[] window) {
        if (fp.getId() != null) {
            try {
                InspectSample existing = sampleMapper.selectLatestByFpId(fp.getId());
                if (existing != null && existing.getRawMessage() != null) {
                    return existing.getRawMessage();
                }
            } catch (Exception ignored) {
                // 落到下面重新抓
            }
        }
        try {
            SampleRow row = slsRepository.querySample(any.getAppId(), any.getLoggerClass(),
                    any.getBizFrame(), any.getMsgTpl(), any.getExcCause(), window[0], window[1]);
            if (row != null && fp.getId() != null) {
                InspectSample sample = new InspectSample();
                sample.setFpId(fp.getId());
                sample.setRawMessage(row.getMessage());
                sample.setTraceId(row.getTraceId());
                sample.setPodName(row.getPodName());
                sample.setImageTag(row.imageTag());
                sample.setLogTime(new Date((row.getLogTimeSec() == null ? 0L : row.getLogTimeSec()) * 1000L));
                sampleMapper.batchInsert(java.util.Collections.singletonList(sample));
                return row.getMessage();
            }
            return row == null ? null : row.getMessage();
        } catch (Exception e) {
            log.debug("抓取样例失败 fpId={}：{}", fp.getId(), e.getMessage());
            return null;
        }
    }

    private long sumCnt(List<FingerprintRow> rows) {
        long sum = 0L;
        for (FingerprintRow r : rows) {
            sum += r.getCnt();
        }
        return sum;
    }

    /**
     * 拉取原始日志样本。
     *
     * <p>只对<b>需要送 AI</b> 的指纹拉 —— 每轮只有几个到几十个，逐个查询不构成压力；
     * 而全部指纹都拉会变成上千次 SLS 查询。
     */
    private int fetchSamples(Map<String, List<FingerprintRow>> grouped,
                             Map<String, Long> idByHash,
                             List<JudgeResult> judged, long[] window) {
        Map<Long, JudgeResult> needAiById = new HashMap<>();
        for (JudgeResult jr : judged) {
            if (jr.isNeedAi()) {
                needAiById.put(jr.getFpId(), jr);
            }
        }
        if (needAiById.isEmpty()) {
            return 0;
        }

        List<InspectSample> samples = new ArrayList<>();
        for (Map.Entry<String, List<FingerprintRow>> e : grouped.entrySet()) {
            Long fpId = idByHash.get(md5(e.getKey()));
            if (fpId == null || !needAiById.containsKey(fpId)) {
                continue;
            }
            if (sampleMapper.countByFpId(fpId) > 0) {
                continue;
            }
            FingerprintRow any = e.getValue().get(0);
            try {
                SampleRow row = slsRepository.querySample(any.getAppId(), any.getLoggerClass(),
                        any.getBizFrame(), any.getMsgTpl(), any.getExcCause(), window[0], window[1]);
                if (row == null) {
                    continue;
                }
                InspectSample sample = new InspectSample();
                sample.setFpId(fpId);
                sample.setRawMessage(row.getMessage());
                sample.setTraceId(row.getTraceId());
                sample.setPodName(row.getPodName());
                sample.setImageTag(row.imageTag());
                sample.setLogTime(new Date((row.getLogTimeSec() == null ? 0L : row.getLogTimeSec()) * 1000L));
                samples.add(sample);
            } catch (Exception ex) {
                // 单个样本拉取失败不影响整轮采集
                log.warn("拉取样本失败 fpKey={} err={}", e.getKey(), ex.getMessage());
            }
        }
        for (List<InspectSample> part : partition(samples, 100)) {
            sampleMapper.batchInsert(part);
        }
        return samples.size();
    }

    /** 把需要关注的判定结果打进日志，便于上线初期直接从日志核对判定是否合理 */
    private void logActionable(List<JudgeResult> judged, Map<String, List<FingerprintRow>> grouped,
                               Map<String, Long> idByHash) {
        Map<Long, String> keyById = new HashMap<>();
        for (String fpKey : grouped.keySet()) {
            Long id = idByHash.get(md5(fpKey));
            if (id != null) {
                keyById.put(id, fpKey);
            }
        }
        for (JudgeResult jr : judged) {
            if (jr.isActionable()) {
                log.info("【巡检判定】{} needAi={} 24h={} {} | {}",
                        jr.getJudgeState(), jr.isNeedAi(), jr.getCount24h(),
                        jr.getReason(), keyById.get(jr.getFpId()));
            }
        }
    }

    private static String md5(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5")
                    .digest((text == null ? "" : text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SLS 的 coalesce 兜底会返回空串，落库时统一成 null，避免页面显示空白占位 */
    /** 把聚合 SQL 的兜底占位还原成 null，避免占位符漏到界面 */
    private static String placeholderToNull(String v) {
        String t = blankToNull(v);
        return t == null || "NONE".equals(t) || "NO_STACK".equals(t) ? null : t;
    }

    private static String blankToNull(String v) {
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    private static String truncate(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(list.subList(i, Math.min(list.size(), i + size)));
        }
        return parts;
    }

    private static String fmt(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm").format(new Date(ms));
    }
}
