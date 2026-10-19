package io.github.liufor02144.erroragent.service.code;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.liufor02144.erroragent.entity.InspectProject;
import io.github.liufor02144.erroragent.mapper.InspectProjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 仓库映射：app_id → Git 仓库。
 *
 * <p>一仓多服务，所以映射是「多对一」：order-center 一个仓库
 * 承载 order-task / order-service / order-listener 等多个 app_id。
 */
@Slf4j
@Service
public class InspectProjectService {

    private static final String ALL = "all";

    @Resource
    private InspectProjectMapper projectMapper;

    /** app_id → 仓库。仓库数只有二十来个，整体缓存即可 */
    private final LoadingCache<String, Map<String, InspectProject>> cache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build(k -> buildMapping());

    private Map<String, InspectProject> buildMapping() {
        Map<String, InspectProject> map = new HashMap<>();
        try {
            List<InspectProject> projects = projectMapper.selectEnabled();
            if (projects == null) {
                return map;
            }
            for (InspectProject p : projects) {
                for (String appId : splitAppIds(p.getAppIds())) {
                    InspectProject exists = map.put(appId, p);
                    if (exists != null) {
                        // 同一个 app_id 映射到两个仓库会导致定位到错误的代码，必须暴露出来
                        log.error("app_id [{}] 同时映射到仓库 {} 与 {}，请修正 ai_inspect_project 配置",
                                appId, exists.getRepoPath(), p.getRepoPath());
                    }
                }
            }
        } catch (Exception e) {
            log.error("加载仓库映射失败", e);
        }
        return map;
    }

    public void refresh() {
        cache.invalidate(ALL);
    }

    /** 按服务名找仓库，未登记返回 null */
    public InspectProject findByAppId(String appId) {
        if (appId == null) {
            return null;
        }
        return cache.get(ALL).get(appId);
    }

    public List<InspectProject> listEnabled() {
        List<InspectProject> list = projectMapper.selectEnabled();
        return list == null ? Collections.emptyList() : list;
    }

    public List<InspectProject> listAll() {
        List<InspectProject> list = projectMapper.selectAll();
        return list == null ? Collections.emptyList() : list;
    }

    public InspectProject add(String repoPath, String appIds, String branch) {
        InspectProject p = new InspectProject();
        p.setRepoPath(trimSlash(repoPath));
        p.setRepoName(lastSegment(p.getRepoPath()));
        p.setAppIds(appIds);
        p.setBranch(branch == null || branch.trim().isEmpty() ? "master" : branch.trim());
        projectMapper.insert(p);
        refresh();
        log.info("登记代码仓库 {} → app_ids={}", p.getRepoPath(), appIds);
        return p;
    }

    public void update(Long id, String appIds, String branch, Integer enabled) {
        InspectProject p = new InspectProject();
        p.setId(id);
        p.setAppIds(appIds);
        p.setBranch(branch);
        p.setEnabled(enabled);
        projectMapper.update(p);
        refresh();
    }

    public void delete(Long id) {
        projectMapper.deleteById(id);
        refresh();
    }

    public static List<String> splitAppIds(String appIds) {
        List<String> list = new ArrayList<>();
        if (appIds == null || appIds.trim().isEmpty()) {
            return list;
        }
        for (String s : appIds.split("[|,]")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                list.add(t);
            }
        }
        return list;
    }

    private String trimSlash(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private String lastSegment(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }
}
