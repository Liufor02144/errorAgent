package io.github.liufor02144.erroragent.controller;

import io.github.liufor02144.erroragent.common.Response;
import io.github.liufor02144.erroragent.dto.CodeLocateResult;
import io.github.liufor02144.erroragent.entity.InspectFingerprint;
import io.github.liufor02144.erroragent.entity.InspectProject;
import io.github.liufor02144.erroragent.entity.InspectSample;
import io.github.liufor02144.erroragent.mapper.InspectFingerprintMapper;
import io.github.liufor02144.erroragent.mapper.InspectSampleMapper;
import io.github.liufor02144.erroragent.service.code.CodeLocator;
import io.github.liufor02144.erroragent.service.code.GitSyncService;
import io.github.liufor02144.erroragent.service.code.InspectProjectService;
import io.github.liufor02144.erroragent.service.code.JavaSourceIndex;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码仓库管理与定位验证。
 */
@Slf4j
@RestController
@RequestMapping("/inspect")
public class InspectCodeController {

    @Resource
    private InspectProjectService projectService;

    @Resource
    private GitSyncService gitSyncService;

    @Resource
    private JavaSourceIndex sourceIndex;

    @Resource
    private CodeLocator codeLocator;

    @Resource
    private InspectFingerprintMapper fingerprintMapper;

    @Resource
    private InspectSampleMapper sampleMapper;

    // ---------------- 仓库管理 ----------------

    @GetMapping("/project")
    public Response<List<InspectProject>> list() {
        return Response.ok(projectService.listAll());
    }

    /**
     * 登记仓库。
     *
     * <p>repoPath 必须是含 subgroup 的完整路径，例如
     * {@code backend/trade/order-center} ——
     * 仅填仓库名拼不出克隆地址。
     */
    @PostMapping("/project")
    public Response<InspectProject> add(@RequestBody Map<String, String> body) {
        String repoPath = body.get("repoPath");
        String appIds = body.get("appIds");
        if (repoPath == null || repoPath.trim().isEmpty()) {
            return Response.error("repoPath 不能为空，需含 subgroup 完整路径");
        }
        if (appIds == null || appIds.trim().isEmpty()) {
            return Response.error("appIds 不能为空，多个用 | 分隔");
        }
        return Response.ok(projectService.add(repoPath, appIds, body.get("branch")));
    }

    @DeleteMapping("/project/{id}")
    public Response<Void> delete(@PathVariable Long id) {
        projectService.delete(id);
        return Response.ok(null);
    }

    /** 同步单个仓库 */
    @PostMapping("/project/{id}/sync")
    public Response<Map<String, Object>> sync(@PathVariable Long id) {
        InspectProject p = projectService.listAll().stream()
                .filter(x -> x.getId().equals(id)).findFirst().orElse(null);
        if (p == null) {
            return Response.error("仓库不存在");
        }
        long began = System.currentTimeMillis();
        boolean ok = gitSyncService.sync(p);
        if (ok) {
            sourceIndex.invalidate(gitSyncService.repoDir(p));
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", ok);
        result.put("repoPath", p.getRepoPath());
        result.put("localPath", gitSyncService.repoDir(p).getAbsolutePath());
        result.put("commit", ok ? gitSyncService.headCommit(p) : null);
        result.put("elapsedMs", System.currentTimeMillis() - began);
        return Response.ok(result);
    }

    @PostMapping("/project/sync-all")
    public Response<Map<String, Object>> syncAll() {
        long began = System.currentTimeMillis();
        int ok = gitSyncService.syncAll();
        Map<String, Object> result = new HashMap<>();
        result.put("success", ok);
        result.put("total", projectService.listEnabled().size());
        result.put("elapsedMs", System.currentTimeMillis() - began);
        return Response.ok(result);
    }

    // ---------------- 定位验证 ----------------

    /**
     * 对指定指纹执行代码定位。
     *
     * <p>验证用：返回的 confidence 直接反映定位质量 ——
     * HIGH 表示行号校验通过，MEDIUM 表示行号漂移已按方法名兜底，
     * LOW/UNLOCATED 说明这条指纹给 AI 的上下文不可靠。
     */
    @PostMapping("/locate/{fpId}")
    public Response<Map<String, Object>> locate(@PathVariable Long fpId) {
        InspectFingerprint fp = fingerprintMapper.selectById(fpId);
        if (fp == null) {
            return Response.error("指纹不存在");
        }
        InspectSample sample = sampleMapper.selectLatestByFpId(fpId);
        String raw = sample == null ? null : sample.getRawMessage();

        long began = System.currentTimeMillis();
        CodeLocateResult located = codeLocator.locate(fp, raw);

        Map<String, Object> result = new HashMap<>();
        result.put("fpKey", fp.getFpKey());
        result.put("appId", fp.getAppId());
        result.put("hasStack", fp.getHasStack());
        result.put("hasSample", sample != null);
        result.put("locate", located);
        result.put("elapsedMs", System.currentTimeMillis() - began);
        return Response.ok(result);
    }

    /** 批量抽样定位，用于统计定位成功率（验收指标：有堆栈 ≥95%、无堆栈 ≥70%） */
    @PostMapping("/locate/sample-rate")
    public Response<Map<String, Object>> sampleRate(@RequestBody Map<String, Object> body) {
        Object idsObj = body.get("fpIds");
        if (!(idsObj instanceof List)) {
            return Response.error("fpIds 必须是数组");
        }
        int high = 0, medium = 0, low = 0, unlocated = 0;
        int stackTotal = 0, stackOk = 0, loggerTotal = 0, loggerOk = 0;

        for (Object o : (List<?>) idsObj) {
            Long fpId = Long.valueOf(String.valueOf(o));
            InspectFingerprint fp = fingerprintMapper.selectById(fpId);
            if (fp == null) {
                continue;
            }
            InspectSample sample = sampleMapper.selectLatestByFpId(fpId);
            CodeLocateResult r = codeLocator.locate(fp, sample == null ? null : sample.getRawMessage());

            boolean ok = CodeLocateResult.HIGH.equals(r.getConfidence())
                    || CodeLocateResult.MEDIUM.equals(r.getConfidence());
            if (Boolean.TRUE.equals(fp.getHasStack())) {
                stackTotal++;
                if (ok) {
                    stackOk++;
                }
            } else {
                loggerTotal++;
                if (ok) {
                    loggerOk++;
                }
            }
            switch (r.getConfidence()) {
                case CodeLocateResult.HIGH: high++; break;
                case CodeLocateResult.MEDIUM: medium++; break;
                case CodeLocateResult.LOW: low++; break;
                default: unlocated++;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("high", high);
        result.put("medium", medium);
        result.put("low", low);
        result.put("unlocated", unlocated);
        result.put("stackRoute", rate(stackOk, stackTotal));
        result.put("loggerRoute", rate(loggerOk, loggerTotal));
        return Response.ok(result);
    }

    private String rate(int ok, int total) {
        if (total == 0) {
            return "无样本";
        }
        return String.format("%d/%d = %.1f%%", ok, total, ok * 100.0 / total);
    }
}
