package io.github.liufor02144.erroragent.service.push;

import io.github.liufor02144.erroragent.entity.InspectEvent;
import io.github.liufor02144.erroragent.entity.InspectFingerprint;
import io.github.liufor02144.erroragent.mapper.InspectFingerprintMapper;
import io.github.liufor02144.erroragent.service.collect.InspectSlsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 判断两个事件是不是「同一批请求」打出来的。
 *
 * <p>同一个故障在一次请求里常常打好几条文字不同的 ERROR：第三方配送平台的计费接口返回为空时，
 * 每次请求都打「解密配送平台报文失败 … 计费接口返回为空」（内层）和
 * 「查询配送账单出错,降级处理 … 解密配送平台报文异常」（外层）两条。
 * 文字不同就是两个模版、两个事件，群里连推两张卡片 —— 实测两者 28 次请求完全重合。
 *
 * <p>判据是请求（traceId）重合度，而不是文字相似度：文字可以完全不像，
 * 但只要是同一批请求打出来的，就是同一件事。
 *
 * <p>一轮推送里每个事件只查一次 SLS，结果缓存在调用方传入的 map 里。
 */
@Slf4j
@Service
public class SameRequestMerger {

    /** 请求重合占较小一方的比例达到这个值，就算同一批请求 */
    static final double MIN_OVERLAP = 0.8;

    /** 往回看多久的请求：事件最近一次出现前 30 分钟 */
    private static final long LOOKBACK_MS = 30 * 60_000L;

    private static final int TRACE_LIMIT = 100;

    @Resource
    private InspectFingerprintMapper fingerprintMapper;

    @Resource
    private InspectSlsRepository slsRepository;

    /**
     * 事件最近的那批请求。没有 traceId 的事件（日志里没打）返回空集，永远不会被合并。
     *
     * @param cache 本轮的缓存，eventId → traceId 集合
     */
    public Set<String> tracesOf(InspectEvent event, Map<Long, Set<String>> cache) {
        if (event == null || event.getId() == null) {
            return Collections.emptySet();
        }
        Set<String> cached = cache.get(event.getId());
        if (cached != null) {
            return cached;
        }
        Set<String> traces = new HashSet<>();
        try {
            InspectFingerprint fp = event.getFpId() == null ? null : fingerprintMapper.selectById(event.getFpId());
            if (fp != null && event.getLastSeenTime() != null) {
                long last = event.getLastSeenTime().getTime();
                traces.addAll(slsRepository.queryTraceIds(fp.getAppId(), fp.getLoggerClass(), fp.getBizFrame(),
                        fp.getMsgTpl(), fp.getExcCause(), last - LOOKBACK_MS, last + 2 * 60_000L, TRACE_LIMIT));
            }
        } catch (Exception e) {
            // 查不到就当作没有可比的请求，照常单独推送，不能因此漏推
            log.warn("取事件 #{} 的请求列表失败：{}", event.getId(), e.getMessage());
        }
        cache.put(event.getId(), traces);
        return traces;
    }

    /** 重合的请求数占较小一方的比例；任一方为空时为 0 */
    static double overlap(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> large = small == a ? b : a;
        int common = 0;
        for (String t : small) {
            if (large.contains(t)) {
                common++;
            }
        }
        return (double) common / small.size();
    }

    public static boolean sameRequests(Set<String> a, Set<String> b) {
        return overlap(a, b) >= MIN_OVERLAP;
    }
}
