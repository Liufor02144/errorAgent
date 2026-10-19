package io.github.liufor02144.erroragent.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.liufor02144.erroragent.entity.InspectConfig;
import io.github.liufor02144.erroragent.mapper.InspectConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 动态配置读取。调优参数与业务规则存 {@code ai_inspect_config}，页面改完 30 秒内生效。
 *
 * <p>取值优先级：当前值 → 默认值 → 调用方传入的兜底值。
 * 三层兜底是为了保证「配置表被误删某行」也不会让任务崩掉。
 */
@Slf4j
@Service
public class InspectConfigService {

    /** 配置项只有二十几个，整体缓存比逐 key 缓存简单且命中率更高 */
    private static final String ALL = "all";

    @Resource
    private InspectConfigMapper configMapper;

    private final LoadingCache<String, Map<String, String>> cache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build(k -> loadAll());

    private Map<String, String> loadAll() {
        Map<String, String> map = new HashMap<>();
        try {
            List<InspectConfig> list = configMapper.selectAll();
            if (list != null) {
                for (InspectConfig c : list) {
                    String value = notBlank(c.getCfgValue()) ? c.getCfgValue() : c.getDefaultValue();
                    map.put(c.getCfgKey(), value);
                }
            }
        } catch (Exception e) {
            // 读配置失败不能让采集直接挂掉，返回空表让调用方走代码默认值
            log.error("加载巡检动态配置失败，本轮将使用代码默认值", e);
        }
        return map;
    }

    /** 强制刷新（页面保存配置后调用，避免等 30 秒） */
    public void refresh() {
        cache.invalidate(ALL);
    }

    public String getString(String key, String defaultValue) {
        String v = cache.get(ALL).get(key);
        return notBlank(v) ? v : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        String v = getString(key, null);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("巡检配置 {} 的值 [{}] 不是合法整数，回退默认值 {}", key, v, defaultValue);
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue) {
        String v = getString(key, null);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            log.warn("巡检配置 {} 的值 [{}] 不是合法长整数，回退默认值 {}", key, v, defaultValue);
            return defaultValue;
        }
    }

    public boolean getBool(String key, boolean defaultValue) {
        String v = getString(key, null);
        return v == null ? defaultValue : Boolean.parseBoolean(v.trim());
    }

    /** 逗号分隔的列表 */
    public List<String> getList(String key) {
        String v = getString(key, null);
        if (!notBlank(v)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String item : v.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** 取列表配置，库里没有或为空时用默认值 */
    public List<String> getList(String key, String defaultCsv) {
        List<String> v = getList(key);
        if (!v.isEmpty() || defaultCsv == null) {
            return v;
        }
        List<String> result = new ArrayList<>();
        for (String item : defaultCsv.split(",")) {
            if (!item.trim().isEmpty()) {
                result.add(item.trim());
            }
        }
        return result;
    }

    /** 页面展示用：全部配置项（含默认值与说明） */
    public List<InspectConfig> listAll() {
        return configMapper.selectAll();
    }

    /** 页面保存 */
    public void update(String key, String value, String operator) {
        configMapper.updateValue(key, value, operator);
        refresh();
        log.info("巡检配置变更 {} = {} by {}", key, value, operator);
    }

    /**
     * 按分组返回，配置页面直接渲染。
     *
     * <p>顺带标出哪些被改过（当前值 ≠ 默认值）—— 调参调乱了之后，
     * 「我到底改过什么」是最先要回答的问题。
     */
    public Map<String, List<Map<String, Object>>> listGrouped() {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (InspectConfig c : listAll()) {
            String group = notBlank(c.getGroupName()) ? c.getGroupName() : "DEFAULT";
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("cfgKey", c.getCfgKey());
            item.put("cfgValue", c.getCfgValue());
            item.put("defaultValue", c.getDefaultValue());
            item.put("valueType", c.getValueType());
            item.put("description", c.getDescription());
            item.put("operator", c.getOperator());
            item.put("updateTime", c.getUpdateTime());
            item.put("modified", c.getCfgValue() != null
                    && !c.getCfgValue().equals(c.getDefaultValue()));
            grouped.computeIfAbsent(group, k -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    /** 恢复默认值。调崩了要能一键退回 */
    public String resetToDefault(String key, String operator) {
        for (InspectConfig c : listAll()) {
            if (c.getCfgKey().equals(key)) {
                update(key, c.getDefaultValue(), operator);
                return c.getDefaultValue();
            }
        }
        return null;
    }

    private static boolean notBlank(String v) {
        return v != null && !v.trim().isEmpty();
    }
}
