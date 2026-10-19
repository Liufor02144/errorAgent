package io.github.liufor02144.erroragent.controller;

import io.github.liufor02144.erroragent.common.CurrentUser;
import io.github.liufor02144.erroragent.common.Response;
import io.github.liufor02144.erroragent.entity.InspectConfig;
import io.github.liufor02144.erroragent.service.InspectConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 巡检动态配置。页面上的「配置」入口。
 *
 * <p>这些参数刻意不放配置文件/环境变量：突增倍数、量级门槛、重推节奏这类值需要边跑边调，
 * 走配置发布流程太重。配置文件/环境变量只留凭据、基础设施地址和急停开关。
 */
@Slf4j
@RestController
@RequestMapping("/inspect")
public class InspectConfigController {

    @Resource
    private InspectConfigService configService;

    // ---------------- 动态配置 ----------------

    @GetMapping("/config")
    public Response<List<InspectConfig>> listConfig() {
        return Response.ok(configService.listAll());
    }

    /** 按分组返回，配置页面直接渲染 */
    @GetMapping("/config/grouped")
    public Response<Map<String, List<Map<String, Object>>>> groupedConfig() {
        return Response.ok(configService.listGrouped());
    }

    /** 恢复默认值 */
    @PostMapping("/config/{cfgKey}/reset")
    public Response<String> resetConfig(@PathVariable String cfgKey) {
        String value = configService.resetToDefault(cfgKey, currentUser());
        return value == null ? Response.error("配置项不存在：" + cfgKey) : Response.ok(value);
    }

    @PostMapping("/config")
    public Response<Void> updateConfig(@RequestBody Map<String, String> body) {
        String key = body.get("cfgKey");
        String value = body.get("cfgValue");
        if (key == null || key.trim().isEmpty()) {
            return Response.error("cfgKey 不能为空");
        }
        configService.update(key, value, currentUser());
        return Response.ok(null);
    }

    /** 未登录场景（如内部调用、验证脚本）退化为 system，不因取不到用户而中断 */
    private String currentUser() {
        return CurrentUser.name();
    }
}
