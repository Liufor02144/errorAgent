package io.github.liufor02144.erroragent.service.judge;

import io.github.liufor02144.erroragent.dto.BaselineStat;
import io.github.liufor02144.erroragent.dto.FingerprintRow;
import io.github.liufor02144.erroragent.service.InspectConfigKeys;
import io.github.liufor02144.erroragent.service.InspectConfigService;
import io.github.liufor02144.erroragent.service.collect.InspectSlsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 突增判定的基线：直接从 SLS 取过去 N 天同一小时的量。
 *
 * <p><b>为什么不用自己的统计表</b>：统计表只记录巡检服务开着时采到的数据。
 * 实测本地服务间歇运行 3 天，同一小时最多只攒到 2 天的样本，
 * 而判突增至少要 3 天 —— 结果所有异常都落到「基线样本不足」，突增一次都判不出来。
 * 生产上发版、重启、停机同样会留下空洞。
 *
 * <p>SLS 里的日志是完整的：某天那个小时没出现，就是 0 次，照样算一个样本。
 * 查询与采集用同一条聚合 SQL，指纹键口径完全一致。
 *
 * <p>每个小时只在第一次判定时查一遍（N 天各查 1 小时窗口），结果缓存到这个小时结束。
 */
@Slf4j
@Service
public class SlsBaselineService {

    /** 1 小时窗口的聚合行数上限：指纹 × 12 个 5 分钟桶 */
    private static final int LIMIT = 5000;

    @Resource
    private InspectSlsRepository slsRepository;

    @Resource
    private InspectConfigService configService;

    private String cachedHour;

    /** fpKey → 过去 N 天同一小时每天的次数 */
    private Map<String, long[]> cached;

    /** 成功查到的天数：查询失败的那天不算样本 */
    private int cachedDays;

    /**
     * 某指纹在指定小时的基线。
     *
     * @param hourStart 当前比较窗口所在的整点
     * @return 取不到时返回 null，调用方退回统计表
     */
    public BaselineStat baselineOf(String fpKey, Date hourStart) {
        if (fpKey == null || hourStart == null) {
            return null;
        }
        Map<String, long[]> daily = load(hourStart);
        if (daily == null || cachedDays == 0) {
            return null;
        }
        long[] counts = daily.get(fpKey);
        long sum = 0L;
        if (counts != null) {
            for (long c : counts) {
                sum += c;
            }
        }
        BaselineStat stat = new BaselineStat();
        stat.setAvgCnt((double) sum / cachedDays);
        stat.setSamples(cachedDays);
        return stat;
    }

    private synchronized Map<String, long[]> load(Date hourStart) {
        String key = new SimpleDateFormat("yyyyMMddHH").format(hourStart);
        if (key.equals(cachedHour)) {
            return cached;
        }
        int days = Math.max(1, configService.getInt(InspectConfigKeys.JUDGE_BASELINE_DAYS, 7));
        long began = System.currentTimeMillis();
        Map<String, long[]> daily = new HashMap<>();
        int ok = 0;
        for (int d = 1; d <= days; d++) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(hourStart);
            cal.add(Calendar.DAY_OF_YEAR, -d);
            long from = cal.getTimeInMillis();
            long to = from + 3600_000L;
            try {
                List<FingerprintRow> rows = slsRepository.queryFingerprints(from, to, LIMIT);
                if (rows.size() >= LIMIT) {
                    log.warn("【突增基线】{} 前第 {} 天的聚合结果达到上限 {}，长尾指纹的基线会偏低", key, d, LIMIT);
                }
                for (FingerprintRow r : rows) {
                    // 与指纹入库时的截断规则一致，否则超长指纹对不上
                    String fpKey = r.getFpKey() == null || r.getFpKey().length() <= 1000
                            ? r.getFpKey() : r.getFpKey().substring(0, 1000);
                    daily.computeIfAbsent(fpKey, k -> new long[days])[d - 1] += r.getCnt();
                }
                ok++;
            } catch (Exception e) {
                log.warn("【突增基线】查询前第 {} 天 {} 点失败，该天不计入样本：{}",
                        d, key.substring(8), e.getMessage());
            }
        }
        cachedHour = key;
        cached = daily;
        cachedDays = ok;
        log.info("【突增基线】{} 点基线已从 SLS 加载：{} 天可用、{} 个指纹，耗时 {}ms",
                key.substring(8), ok, daily.size(), System.currentTimeMillis() - began);
        return daily;
    }
}
