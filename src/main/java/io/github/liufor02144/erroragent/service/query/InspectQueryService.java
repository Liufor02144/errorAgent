package io.github.liufor02144.erroragent.service.query;

import io.github.liufor02144.erroragent.entity.InspectAnalysis;
import io.github.liufor02144.erroragent.entity.InspectFingerprint;
import io.github.liufor02144.erroragent.entity.InspectSample;
import io.github.liufor02144.erroragent.mapper.InspectAnalysisMapper;
import io.github.liufor02144.erroragent.mapper.InspectFingerprintMapper;
import io.github.liufor02144.erroragent.mapper.InspectLlmUsageMapper;
import io.github.liufor02144.erroragent.mapper.InspectSampleMapper;
import io.github.liufor02144.erroragent.mapper.InspectStatMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 页面查询：列表、详情、概览。
 *
 * <p>列表页每行都要显示 AI 分类与严重度，逐行查分析表就是 N+1。
 * 这里一次性按 id 批量取回再装配。
 */
@Slf4j
@Service
public class InspectQueryService {

    @Resource
    private InspectFingerprintMapper fingerprintMapper;

    @Resource
    private InspectAnalysisMapper analysisMapper;

    @Resource
    private InspectSampleMapper sampleMapper;

    @Resource
    private InspectStatMapper statMapper;

    @Resource
    private InspectLlmUsageMapper usageMapper;

    /** 列表行：指纹 + 分析结论 + 近 24h 量级 */
    @Data
    public static class FingerprintRow {
        private InspectFingerprint fingerprint;
        private String category;
        private String severity;
        private String confidence;
        private Boolean isFirstSeen;
        private Long count24h;
        private Boolean analyzed;
    }

    @Data
    public static class PageResult {
        private List<FingerprintRow> rows;
        private long total;
        private int page;
        private int size;
    }

    /** 详情：一次给全，避免页面打开后再发四五个请求 */
    @Data
    public static class FingerprintDetail {
        private InspectFingerprint fingerprint;
        private InspectAnalysis analysis;
        private List<InspectSample> samples;
        private List<Map<String, Object>> trend;
        private Long count24h;
        private Long count7d;
    }

    public PageResult page(String appId, String status, List<String> judgeStates,
                           String category, Integer handled, String keyword, Boolean pushedOnly,
                           Date startTime, Date endTime, String orderBy, int page, int size) {
        PageResult result = new PageResult();
        result.setPage(page);
        result.setSize(size);

        long total = fingerprintMapper.countByCondition(appId, status, judgeStates,
                category, handled, keyword, pushedOnly, startTime, endTime);
        result.setTotal(total);
        if (total == 0) {
            result.setRows(new ArrayList<>());
            return result;
        }

        List<InspectFingerprint> list = fingerprintMapper.selectByCondition(appId, status,
                judgeStates, category, handled, keyword, pushedOnly, startTime, endTime, orderBy,
                (page - 1) * size, size);
        if (list == null || list.isEmpty()) {
            result.setRows(new ArrayList<>());
            return result;
        }

        // 批量取分析结论，避免逐行查询
        List<Long> ids = list.stream().map(InspectFingerprint::getId).collect(Collectors.toList());
        Map<Long, InspectAnalysis> analysisMap = new HashMap<>();
        List<InspectAnalysis> analyses = analysisMapper.selectByFpIds(ids);
        if (analyses != null) {
            for (InspectAnalysis a : analyses) {
                // selectByFpIds 按时间倒序，第一条即最新
                analysisMap.putIfAbsent(a.getFpId(), a);
            }
        }

        // 近 24h 量级同样批量取，否则每行一次 SQL
        Map<Long, Long> countMap = new HashMap<>();
        List<Map<String, Object>> counts =
                statMapper.sumCountByFpIds(ids, hoursAgo(24), new Date());
        if (counts != null) {
            for (Map<String, Object> m : counts) {
                Object id = m.get("fpId");
                Object cnt = m.get("cnt");
                if (id != null && cnt != null) {
                    countMap.put(((Number) id).longValue(), ((Number) cnt).longValue());
                }
            }
        }

        List<FingerprintRow> rows = new ArrayList<>(list.size());
        for (InspectFingerprint fp : list) {
            FingerprintRow row = new FingerprintRow();
            row.setFingerprint(fp);
            row.setCount24h(countMap.getOrDefault(fp.getId(), 0L));

            InspectAnalysis a = analysisMap.get(fp.getId());
            row.setAnalyzed(a != null);
            if (a != null) {
                row.setCategory(a.getCategory());
                row.setSeverity(a.getSeverity());
                row.setConfidence(a.getConfidence());
                row.setIsFirstSeen(a.getIsFirstSeen());
            }
            rows.add(row);
        }
        result.setRows(rows);
        return result;
    }

    public FingerprintDetail detail(Long fpId, int trendDays, int sampleLimit) {
        InspectFingerprint fp = fingerprintMapper.selectById(fpId);
        if (fp == null) {
            return null;
        }
        FingerprintDetail detail = new FingerprintDetail();
        detail.setFingerprint(fp);

        InspectAnalysis analysis = analysisMapper.selectLatestByFpId(fpId);
        detail.setAnalysis(analysis);

        detail.setSamples(sampleMapper.selectByFpId(fpId, sampleLimit));

        Date now = new Date();
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -trendDays);
        detail.setTrend(statMapper.selectTrend(fpId, cal.getTime(), now));
        detail.setCount24h(nz(statMapper.sumCount(fpId, hoursAgo(24), now)));
        detail.setCount7d(nz(statMapper.sumCount(fpId, hoursAgo(24 * 7), now)));
        return detail;
    }

    /**
     * 概览：页面顶部的几个数字。
     *
     * <p>「待处理」是这套东西的核心指标——它代表还没人看过的新增/突增异常。
     * 这个数持续上涨说明推送没人理，或者判定阈值太松。
     */
    /** 采集到的服务列表，供页面下拉 */
    public List<Map<String, Object>> appOptions() {
        return fingerprintMapper.selectAppOptions();
    }

    public Map<String, Object> overview() {
        Map<String, Object> result = new HashMap<>();
        Date todayStart = todayStart();

        result.put("pending", fingerprintMapper.countByCondition(null, "ACTIVE",
                java.util.Arrays.asList("NEW", "SURGE"), null, 0, null, null, null, null));
        result.put("todayNew", fingerprintMapper.countNewSince(todayStart));
        result.put("suspectedBug", fingerprintMapper.countByCondition(null, "ACTIVE",
                null, "SUSPECTED_BUG", 0, null, null, null, null));
        result.put("todayPushed", fingerprintMapper.countPushedSince(todayStart));
        result.put("todayLlmCalls", usageMapper.countSince(todayStart));
        return result;
    }

    private Date hoursAgo(int hours) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR_OF_DAY, -hours);
        return cal.getTime();
    }

    private Date todayStart() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    private Long nz(Long v) {
        return v == null ? 0L : v;
    }
}
