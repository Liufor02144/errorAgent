package io.github.liufor02144.erroragent.controller;

import io.github.liufor02144.erroragent.common.Response;
import io.github.liufor02144.erroragent.config.InspectSwitch;
import io.github.liufor02144.erroragent.dto.CollectResult;
import io.github.liufor02144.erroragent.service.collect.InspectCollectService;
import io.github.liufor02144.erroragent.service.collect.InspectSlsRepository;
import io.github.liufor02144.erroragent.service.collect.InspectSqlBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集验证接口。
 *
 * <p>存在的目的是让采集能被手动触发并立刻看到结果，而不必等定时任务、
 * 也不必开总开关 —— 冷启动阶段需要反复对比「指纹数是否符合实测基准」。
 */
@Slf4j
@RestController
@RequestMapping("/inspect/collect")
public class InspectCollectController {

    @Resource
    private InspectCollectService collectService;

    @Resource
    private InspectSlsRepository slsRepository;

    @Resource
    private InspectSqlBuilder sqlBuilder;

    @Resource
    private InspectSwitch inspectSwitch;

    /**
     * 手动执行一轮采集。
     *
     * @param force 为 true 时忽略总开关，用于开关关闭状态下的验证
     */
    @PostMapping("/trigger")
    public Response<CollectResult> trigger(@RequestParam(defaultValue = "true") boolean force) {
        CollectResult result = collectService.collectOnce(force);
        if (!result.isSuccess()) {
            return Response.error(result.getMessage());
        }
        return Response.ok(result);
    }

    /**
     * 诊断：逐条探测 SLS 通道，定位「哪一段 SQL 用不了」。
     *
     * <p>排查顺序由浅入深：先确认通道与鉴权，再逐个验证查询里用到的字段是否可用。
     * 返回每条的成败与原始错误，而不是只给一个笼统的失败。
     */
    @GetMapping("/probe")
    public Response<List<Map<String, Object>>> probe(
            @RequestParam(defaultValue = "10") int minutes) {
        long end = System.currentTimeMillis();
        long start = end - minutes * 60_000L;

        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("1.通道与鉴权", "log_level: ERROR | select count(*) as c from log");
        cases.put("2.message 字段", "log_level: ERROR | select count(*) as c from log where message like '%Exception%'");
        cases.put("3.容器名字段", "log_level: ERROR | select count(*) as c from log where \"_container_name_\" like 'mycompany-%'");
        cases.put("4.app_id 字段", "log_level: ERROR | select count(*) as c from log where app_id != ''");
        cases.put("5.正则函数", "log_level: ERROR | select count(*) as c from log where regexp_like(message, 'Exception')");
        cases.put("6.message 出现在 select", "log_level: ERROR | select substr(message, 1, 10) as m from log limit 1");

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map.Entry<String, String> e : cases.entrySet()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("case", e.getKey());
            r.put("sql", e.getValue());
            try {
                List<Map<String, String>> rows = slsRepository.probe(e.getValue(), start, end);
                r.put("ok", true);
                r.put("rows", rows.size());
                r.put("sample", rows.isEmpty() ? null : rows.get(0));
            } catch (Exception ex) {
                r.put("ok", false);
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                r.put("error", cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
            results.add(r);
        }
        return Response.ok(results);
    }

    /**
     * 查看实际会下发给 SLS 的查询语句。
     *
     * <p>可直接复制到 SLS 控制台执行来交叉验证 —— 聚合 SQL 里的正则一旦写错
     * （例如业务栈帧那条），表现是全部返回 null 而不是报错，只看代码很难发现。
     */
    @GetMapping("/sql")
    public Response<Map<String, String>> previewSql(
            @RequestParam(defaultValue = "5000") int limit,
            @RequestParam(required = false) String appId) {
        Map<String, String> map = new HashMap<>();
        map.put("scopeFilter", sqlBuilder.buildScopeFilter());
        map.put("aggregateSql", sqlBuilder.buildAggregateSql(limit));
        if (appId != null && !appId.trim().isEmpty()) {
            map.put("sampleSqlExample", sqlBuilder.buildSampleQuery(appId, null, null));
        }
        return Response.ok(map);
    }

    /** 当前实际生效的开关状态（验证配置改动是否真的生效） */
    @GetMapping("/switch")
    public Response<String> switchState() {
        return Response.ok(inspectSwitch.describe());
    }
}
