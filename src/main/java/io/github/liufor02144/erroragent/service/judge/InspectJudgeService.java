package io.github.liufor02144.erroragent.service.judge;

import io.github.liufor02144.erroragent.dto.BaselineStat;
import io.github.liufor02144.erroragent.dto.JudgeResult;
import io.github.liufor02144.erroragent.entity.InspectFingerprint;
import io.github.liufor02144.erroragent.entity.InspectLibrary;
import io.github.liufor02144.erroragent.mapper.InspectStatMapper;
import io.github.liufor02144.erroragent.service.InspectConfigKeys;
import io.github.liufor02144.erroragent.service.InspectConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Calendar;
import java.util.Date;

/**
 * 判定：这条异常要不要爆出来。
 *
 * <p>只有两条规则 ——
 * <ul>
 *   <li><b>未确认为业务预期</b> → 爆（Redis 超时、SQL 异常、没见过的异常）</li>
 *   <li><b>已确认业务预期但突增</b> → 爆（「商品已下架」平时 50 次、现在 5000 次）</li>
 * </ul>
 * 已确认业务预期且量级正常 → 不爆，只计数。
 *
 * <p>判据是模版上的 {@code business_expected} 标记，<b>不是「在不在模版库里」</b> ——
 * 模版库收录全部模版，在库只代表「这个异常有身份了」，不代表允许发生。
 *
 * <p>这一层刻意<b>不使用 AI</b>：「在不在库里」「有没有突增」是能用数据确定性回答的问题，
 * 交给 AI 既不稳定也更贵。AI 只负责它不可替代的部分 —— 读代码解释根因。
 *
 * <p><b>不在库的异常不做幂等压制</b>：只要还在出现就一直判 NEW，
 * 由事件层累计成一条持续升级的事件。「报过了就不报」会让持续一整天的
 * Redis 超时在第一次之后彻底沉默。
 *
 * <p><b>也不再按次数筛</b>：异常库已经承担了过滤职责，能走到这里的都是
 * 新增或突增，一律送 AI 分析。用次数当门槛会让「每小时 1 次、连续 5 小时」的
 * Redis 超时永远拿不到严重度，也就永远推不出去 —— 次数少不等于不严重。
 * 防刷 token 改由事件维度限流承担（同一事件 1 小时只分析一次）。
 */
@Slf4j
@Service
public class InspectJudgeService {

    /**
     * 基线最少样本数。低于此值不判突增 ——
     * 冷启动初期历史不足时 AVG 仍会返回值，但不具统计意义，据此判突增会大量误报。
     */
    private static final int MIN_BASELINE_SAMPLES = 3;

    @Resource
    private InspectStatMapper statMapper;

    @Resource
    private InspectConfigService configService;

    /** 突增基线取自 SLS；取不到时退回统计表 */
    @Resource
    private SlsBaselineService slsBaseline;

    /**
     * 判定单个指纹。
     *
     * @param current     本轮写入后的指纹（含 id）
     * @param matched     该指纹命中的模版，null 表示还没匹配上任何模版。
     *                    由采集层统一匹配后传入 —— 匹配要读原始样例，
     *                    在这里重新匹配会丢掉样例、只能拿归一后的文本去撞
     * @param coldStart   是否首轮（库中尚无历史）
     * @param hourCount   该指纹在当前小时的次数
     * @param currentHour 当前整点小时
     */
    public JudgeResult judge(InspectFingerprint current, InspectLibrary matched,
                             boolean coldStart, long hourCount, Date currentHour) {
        return judge(current, matched, coldStart, hourCount, 60, currentHour);
    }

    /**
     * 判定单个指纹。
     *
     * <p><b>当前量与基线必须是同样长度的时间段</b>。之前拿「本小时到现在」的累计
     * 去比「整小时」均值：16:05 时当前量只有 5 分钟，几乎不可能超过整小时均值的 N 倍，
     * 整点刚过的那段时间量真暴涨也判不出来。现在比的是最近 N 分钟，基线同样折算成 N 分钟。
     *
     * @param recentCount   最近一段时间的次数
     * @param recentMinutes 这段时间的长度（分钟）
     * @param windowHour    这段时间所在的整点，决定取哪个小时的基线
     */
    public JudgeResult judge(InspectFingerprint current, InspectLibrary matched,
                             boolean coldStart, long recentCount, int recentMinutes, Date windowHour) {
        long hourCount = recentCount;
        Date currentHour = windowHour;
        JudgeResult r = new JudgeResult();
        r.setFpId(current.getId());
        r.setCurrentHourCount(hourCount);

        // 首轮：模版库还是空的，全部异常都会被判成未确认。
        // 这一轮只建基线并抽取模版，从下一轮起才有可用的判据
        if (coldStart) {
            r.setJudgeState(JudgeResult.QUIET);
            r.setNeedAi(false);
            r.setReason("首轮采集，正在建立基线并抽取异常模版，本轮不产生事件");
            return r;
        }

        InspectLibrary item = matched;

        // 1. 未确认（含尚未匹配到模版）：每次出现都要爆，不看是不是第一次见
        if (item == null || !item.expected()) {
            r.setJudgeState(JudgeResult.NEW);
            r.setReason(item == null
                    ? "尚未匹配到异常模版，待抽取"
                    : "该异常模版未确认为业务预期");
            r.setNeedAi(true);
            r.setCount24h(count24h(current.getId()));
            if (item != null) {
                r.setLibraryItemId(item.getId());
            }
            return r;
        }

        // 2. 已确认业务预期：只有突增才爆
        r.setInLibrary(true);
        r.setLibraryItemId(item.getId());

        // 突增判定可整体关闭：业务预期内的异常量级波动大，噪音多时只盯新增
        if (!configService.getBool(InspectConfigKeys.JUDGE_SURGE_ENABLED, false)) {
            r.setJudgeState(JudgeResult.QUIET);
            r.setNeedAi(false);
            r.setReason("业务预期内，允许发生（突增判定已关闭）");
            return r;
        }

        // 基线优先取 SLS：日志完整，不受巡检服务停机、重启留下的空洞影响
        BaselineStat baseline = null;
        if (slsBaseline != null) {
            try {
                baseline = slsBaseline.baselineOf(current.getFpKey(), currentHour);
            } catch (Exception e) {
                log.warn("从 SLS 取突增基线失败，退回统计表：{}", e.getMessage());
            }
        }
        if (baseline == null) {
            baseline = loadBaseline(current.getId(), currentHour);
        }
        r.setBaseline(baseline.getAvgCnt());
        r.setBaselineSamples(baseline.getSamples());

        int multiple = configService.getInt(InspectConfigKeys.JUDGE_SURGE_MULTIPLE, 3);
        int minCount = configService.getInt(InspectConfigKeys.JUDGE_SURGE_MIN_COUNT, 10);
        // 基线是每小时均值，折算成同样长度的时间段再比
        double expected = baseline.avg() * recentMinutes / 60.0;
        String span = recentMinutes == 60 ? "当前小时" : "最近 " + recentMinutes + " 分钟";
        if (baseline.reliable(MIN_BASELINE_SAMPLES)) {
            // 最低量：基线 0.3、当前 2 次也是 6 倍，但那只是零星波动
            if (hourCount >= minCount && hourCount > expected * multiple) {
                r.setJudgeState(JudgeResult.SURGE);
                r.setReason(String.format("业务预期内的异常突增：%s %d 次，同时段基线 %.1f 次，超过 %d 倍（近 %d 天）",
                        span, hourCount, expected, multiple, baseline.getSamples()));
                r.setNeedAi(true);
                r.setCount24h(count24h(current.getId()));
                return r;
            }
        }

        r.setJudgeState(JudgeResult.QUIET);
        r.setNeedAi(false);
        r.setReason(baseline.reliable(MIN_BASELINE_SAMPLES)
                ? String.format("业务预期内，允许发生。%s %d 次，同时段基线 %.1f 次，未超 %d 倍或未达 %d 次",
                        span, hourCount, expected, multiple, minCount)
                : String.format("业务预期内，允许发生。基线样本不足（%d < %d）暂不判突增",
                        baseline.getSamples() == null ? 0 : baseline.getSamples(), MIN_BASELINE_SAMPLES));
        return r;
    }

    /**
     * 基线：近 N 天同一小时刻度的均值，排除当前小时本身。
     */
    private BaselineStat loadBaseline(Long fpId, Date currentHour) {
        int baselineDays = configService.getInt(InspectConfigKeys.JUDGE_BASELINE_DAYS, 7);
        Calendar cal = Calendar.getInstance();
        cal.setTime(currentHour);
        int hourOfDay = cal.get(Calendar.HOUR_OF_DAY);
        cal.add(Calendar.DAY_OF_YEAR, -baselineDays);
        Date since = cal.getTime();

        BaselineStat stat = statMapper.avgBaseline(fpId, hourOfDay, since, currentHour);
        return stat == null ? new BaselineStat() : stat;
    }

    private long count24h(Long fpId) {
        Calendar cal = Calendar.getInstance();
        Date end = cal.getTime();
        cal.add(Calendar.HOUR_OF_DAY, -24);
        Long sum = statMapper.sumCount(fpId, cal.getTime(), end);
        return sum == null ? 0L : sum;
    }
}
