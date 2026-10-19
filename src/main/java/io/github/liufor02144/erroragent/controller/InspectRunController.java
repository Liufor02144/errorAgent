package io.github.liufor02144.erroragent.controller;

import io.github.liufor02144.erroragent.common.Response;
import io.github.liufor02144.erroragent.service.run.InspectRunQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

/**
 * 巡检记录：每轮采集一条，展开看这一轮的事件、AI 分析、token 与推送的卡片。
 */
@RestController
@RequestMapping("/inspect/run")
public class InspectRunController {

    @Resource
    private InspectRunQueryService queryService;

    @Resource
    private io.github.liufor02144.erroragent.job.InspectCollectJob collectJob;

    /**
     * 立即巡检。后台执行，立刻返回；进度在巡检记录列表里看（会先出现一条「进行中」）。
     */
    @org.springframework.web.bind.annotation.PostMapping("/trigger")
    public Response<Boolean> trigger() {
        String reason = collectJob.triggerNow();
        return reason == null ? Response.ok(true) : Response.error(reason);
    }

    @GetMapping
    public Response<InspectRunQueryService.PageResult> page(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Response.ok(queryService.page(status, page, size));
    }

    @GetMapping("/{id:\\d+}")
    public Response<InspectRunQueryService.RunDetail> detail(@PathVariable Long id) {
        InspectRunQueryService.RunDetail d = queryService.detail(id);
        return d == null ? Response.error("巡检记录不存在") : Response.ok(d);
    }

    /** 推送卡片原文，展开某张卡片时才取 */
    @GetMapping("/item/{itemId:\\d+}/card")
    public Response<String> card(@PathVariable Long itemId) {
        String json = queryService.cardJson(itemId);
        return json == null ? Response.error("这条记录没有卡片原文") : Response.ok(json);
    }
}
