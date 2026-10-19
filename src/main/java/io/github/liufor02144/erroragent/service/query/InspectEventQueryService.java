package io.github.liufor02144.erroragent.service.query;

import io.github.liufor02144.erroragent.entity.InspectAnalysis;
import io.github.liufor02144.erroragent.entity.InspectEvent;
import io.github.liufor02144.erroragent.entity.InspectFingerprint;
import io.github.liufor02144.erroragent.entity.InspectLibrary;
import io.github.liufor02144.erroragent.entity.InspectSample;
import io.github.liufor02144.erroragent.mapper.InspectAnalysisMapper;
import io.github.liufor02144.erroragent.mapper.InspectEventMapper;
import io.github.liufor02144.erroragent.mapper.InspectFingerprintMapper;
import io.github.liufor02144.erroragent.mapper.InspectSampleMapper;
import io.github.liufor02144.erroragent.mapper.InspectStatMapper;
import io.github.liufor02144.erroragent.service.InspectConfigKeys;
import io.github.liufor02144.erroragent.service.InspectConfigService;
import io.github.liufor02144.erroragent.service.judge.InspectLibraryService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 异常事件查询。页面的主数据源。
 *
 * <p>页面上只剩两样东西：<b>异常事件</b>（要看的）和<b>异常库</b>（不用看的）。
 * 分类、状态、判定这些中间概念都不再出现 —— 它们对「这条要不要处理」没有帮助。
 */
@Slf4j
@Service
public class InspectEventQueryService {

    @Resource
    private InspectConfigService configService;

    @Resource
    private InspectEventMapper eventMapper;

    @Resource
    private InspectFingerprintMapper fingerprintMapper;

    @Resource
    private InspectAnalysisMapper analysisMapper;

    @Resource
    private InspectSampleMapper sampleMapper;

    @Resource
    private InspectStatMapper statMapper;

    @Resource
    private InspectLibraryService libraryService;

    @Data
    public static class PageResult {
        private long total;
        private int page;
        private int size;
        private List<InspectEvent> rows;
    }

    @Data
    public static class EventDetail {
        private InspectEvent event;
        /** 来源指纹，展开原始日志与趋势要用 */
        private InspectFingerprint fingerprint;
        private InspectAnalysis analysis;
        private List<InspectSample> samples;
        private List<Map<String, Object>> trend;
        private long count24h;
        private long count7d;
        /** 该异常是否已在异常库中 */
        private boolean inLibrary;
        /** 命中的异常模版，详情页要能看到归到了哪个模版、正则是什么 */
        private InspectLibrary template;
    }

    public PageResult page(Integer handled, String eventType, String appId, String severity,
                           String keyword, Integer sinceDays, int page, int size) {
        Date since = null;
        if (sinceDays != null && sinceDays > 0) {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, -sinceDays);
            since = cal.getTime();
        }
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 200);

        PageResult result = new PageResult();
        result.setPage(p);
        result.setSize(s);
        result.setTotal(eventMapper.countList(handled, eventType, appId, severity, keyword, since));
        result.setRows(eventMapper.selectList(handled, eventType, appId, severity, keyword,
                since, (p - 1) * s, s));
        return result;
    }

    public EventDetail detail(Long eventId, int trendDays, int sampleLimit) {
        InspectEvent event = eventMapper.selectById(eventId);
        if (event == null) {
            return null;
        }
        EventDetail detail = new EventDetail();
        detail.setEvent(event);

        // 模版直接按事件上的 template_id 取 —— 事件就是按模版聚合的，
        // 绕回指纹再匹配一次既慢又可能匹配到别的模版
        if (event.getTemplateId() != null) {
            InspectLibrary tpl = libraryService.getById(event.getTemplateId());
            detail.setTemplate(tpl);
            detail.setInLibrary(tpl != null && tpl.expected());
        }

        Long fpId = event.getFpId();
        if (fpId == null) {
            return detail;
        }
        detail.setFingerprint(fingerprintMapper.selectById(fpId));

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
     * 三张概览卡片：今日新增 / 异常事件 / 异常库。
     *
     * <p>「今日新增」是今天开始的事件，「异常事件」是当前所有未处理的，
     * 「异常库」是已经确认允许发生的条目数 —— 后者持续增长说明系统在被驯服。
     */
    public Map<String, Object> overview() {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> s = eventMapper.summary(todayStart());

        result.put("todayEvents", num(s, "todayEvents"));
        result.put("todayOpenEvents", num(s, "todayOpenEvents"));
        result.put("openEvents", num(s, "openEvents"));
        result.put("totalEvents", num(s, "totalEvents"));
        result.put("libraryTotal", libraryService.countAll());
        // 未确认的模版：这些异常目前每次出现都会报，是最该先看的
        result.put("libraryUnreviewed", libraryService.countUnconfirmed());
        result.put("libraryExpected", libraryService.countBusinessExpected());
        // 采集间隔是动态配置，页面上的「每 N 分钟一轮」以它为准，不能写死
        result.put("scanIntervalSeconds",
                configService.getInt(InspectConfigKeys.COLLECT_SCAN_INTERVAL_SECONDS, 300));
        return result;
    }

    public List<String> appOptions() {
        return eventMapper.selectDistinctApps();
    }

    private long num(Map<String, Object> m, String key) {
        if (m == null) {
            return 0L;
        }
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    private long nz(Long v) {
        return v == null ? 0L : v;
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
}
