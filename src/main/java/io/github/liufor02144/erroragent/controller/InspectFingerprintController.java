package io.github.liufor02144.erroragent.controller;

import io.github.liufor02144.erroragent.common.Response;
import io.github.liufor02144.erroragent.service.query.InspectQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 指纹列表与详情，管理页面的主数据源。
 */
@Slf4j
@RestController
@RequestMapping("/inspect/fingerprint")
public class InspectFingerprintController {

    /** 单页上限，防止前端传个 10000 把库拖垮 */
    private static final int MAX_PAGE_SIZE = 100;

    @Resource
    private InspectQueryService queryService;

    /**
     * 列表。
     *
     * @param judgeState 判定状态，逗号分隔；传 pending 是个快捷方式，等价于 NEW,SURGE
     * @param orderBy    severity(默认) / recent / count
     */
    @GetMapping
    public Response<InspectQueryService.PageResult> page(
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String judgeState,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer handled,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean pushedOnly,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date startTime,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date endTime,
            @RequestParam(required = false, defaultValue = "severity") String orderBy,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        return Response.ok(queryService.page(blankToNull(appId), blankToNull(status),
                parseJudgeStates(judgeState), blankToNull(category), handled,
                blankToNull(keyword), pushedOnly, startTime, endTime, orderBy,
                Math.max(page, 1), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)));
    }

    /**
     * 详情：指纹、分析结论、发版关联、样本、趋势、反馈记录一次返回。
     *
     * <p>页面打开就要看全，分成五个请求只会让加载更慢、状态更难对齐。
     */
    // 限定为数字：否则 /overview 这类字面量路径存在被当成 fpId 的风险
    @GetMapping("/{fpId:\\d+}")
    public Response<InspectQueryService.FingerprintDetail> detail(
            @PathVariable Long fpId,
            @RequestParam(defaultValue = "7") int trendDays,
            @RequestParam(defaultValue = "5") int sampleLimit) {
        InspectQueryService.FingerprintDetail detail =
                queryService.detail(fpId, trendDays, Math.min(Math.max(sampleLimit, 1), 20));
        if (detail == null) {
            return Response.error("指纹不存在");
        }
        return Response.ok(detail);
    }

    /** 服务下拉选项：取实际采集到的服务，避免页面上手打服务名 */
    @GetMapping("/apps")
    public Response<List<Map<String, Object>>> apps() {
        return Response.ok(queryService.appOptions());
    }

    /** 顶部概览数字 */
    @GetMapping("/overview")
    public Response<Map<String, Object>> overview() {
        return Response.ok(queryService.overview());
    }

    /**
     * pending 是列表页默认视图的快捷写法：未处理的新增/突增/复发。
     */
    private List<String> parseJudgeStates(String judgeState) {
        if (judgeState == null || judgeState.trim().isEmpty()) {
            return null;
        }
        String v = judgeState.trim();
        if ("pending".equalsIgnoreCase(v)) {
            return Arrays.asList("NEW", "SURGE");
        }
        return Arrays.asList(v.split("\\s*,\\s*"));
    }

    private String blankToNull(String v) {
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }
}
