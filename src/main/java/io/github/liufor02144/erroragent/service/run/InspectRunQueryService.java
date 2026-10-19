package io.github.liufor02144.erroragent.service.run;

import io.github.liufor02144.erroragent.entity.InspectRun;
import io.github.liufor02144.erroragent.entity.InspectRunItem;
import io.github.liufor02144.erroragent.mapper.InspectLlmUsageMapper;
import io.github.liufor02144.erroragent.mapper.InspectRunItemMapper;
import io.github.liufor02144.erroragent.mapper.InspectRunMapper;
import lombok.Data;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 巡检记录查询 */
@Service
public class InspectRunQueryService {

    @Resource
    private InspectRunMapper runMapper;

    @Resource
    private InspectRunItemMapper itemMapper;

    @Resource
    private InspectLlmUsageMapper usageMapper;

    @Data
    public static class PageResult {
        private int total;
        private List<InspectRun> rows;
    }

    @Data
    public static class RunDetail {
        private InspectRun run;
        /** 本轮时间段的截止：下一轮开始时刻，最新一轮为 null（到现在） */
        private Date periodEnd;
        private List<InspectRunItem> events = new ArrayList<>();
        private List<InspectRunItem> analyses = new ArrayList<>();
        private List<InspectRunItem> pushes = new ArrayList<>();
        /** 按场景汇总的 LLM 用量：分析、抽模版、模版复核 */
        private List<Map<String, Object>> llmUsage = new ArrayList<>();
    }

    public PageResult page(String status, int page, int size) {
        int s = Math.max(1, Math.min(size, 100));
        int p = Math.max(1, page);
        PageResult r = new PageResult();
        r.setTotal(runMapper.count(status));
        List<InspectRun> rows = runMapper.selectPage(status, (p - 1) * s, s);
        // token 按每轮时间段汇总。一页最多 100 条，每条两次走索引的小查询
        for (InspectRun run : rows) {
            fillUsage(run, usage(run));
        }
        r.setRows(rows);
        return r;
    }

    public RunDetail detail(Long id) {
        InspectRun run = runMapper.selectById(id);
        if (run == null) {
            return null;
        }
        RunDetail d = new RunDetail();
        d.setRun(run);
        d.setPeriodEnd(runMapper.selectNextStart(id));
        List<Map<String, Object>> usage = usage(run);
        d.setLlmUsage(usage);
        fillUsage(run, usage);
        for (InspectRunItem item : itemMapper.selectByRunId(id)) {
            if (InspectRunItem.STAGE_EVENT.equals(item.getStage())) {
                d.getEvents().add(item);
            } else if (InspectRunItem.STAGE_ANALYZE.equals(item.getStage())) {
                d.getAnalyses().add(item);
            } else {
                d.getPushes().add(item);
            }
        }
        return d;
    }

    public String cardJson(Long itemId) {
        return itemMapper.selectCardJson(itemId);
    }

    private List<Map<String, Object>> usage(InspectRun run) {
        if (run.getStartTime() == null) {
            return Collections.emptyList();
        }
        Date next = runMapper.selectNextStart(run.getId());
        List<Map<String, Object>> rows = usageMapper.sumBySceneBetween(run.getStartTime(), next);
        return rows == null ? Collections.<Map<String, Object>>emptyList() : rows;
    }

    private void fillUsage(InspectRun run, List<Map<String, Object>> usage) {
        int calls = 0;
        long in = 0;
        long out = 0;
        BigDecimal cost = BigDecimal.ZERO;
        java.util.Set<String> models = new java.util.LinkedHashSet<>();
        for (Map<String, Object> m : usage) {
            Object model = m.get("model");
            if (model != null && !String.valueOf(model).trim().isEmpty()) {
                models.add(String.valueOf(model));
            }
            calls += num(m.get("calls")).intValue();
            in += num(m.get("inputTokens")).longValue();
            out += num(m.get("outputTokens")).longValue();
            Object c = m.get("cost");
            if (c instanceof BigDecimal) {
                cost = cost.add((BigDecimal) c);
            } else if (c instanceof Number) {
                cost = cost.add(BigDecimal.valueOf(((Number) c).doubleValue()));
            }
        }
        run.setLlmCalls(calls);
        run.setModels(models.isEmpty() ? null : String.join(", ", models));
        run.setInputTokens(in);
        run.setOutputTokens(out);
        run.setCost(cost);
    }

    private static Number num(Object o) {
        return o instanceof Number ? (Number) o : 0;
    }
}
