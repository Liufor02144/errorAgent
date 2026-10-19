package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.dto.BaselineStat;
import io.github.liufor02144.erroragent.dto.JudgeResult;
import io.github.liufor02144.erroragent.entity.InspectFingerprint;
import io.github.liufor02144.erroragent.entity.InspectLibrary;
import io.github.liufor02144.erroragent.mapper.InspectStatMapper;
import io.github.liufor02144.erroragent.service.InspectConfigKeys;
import io.github.liufor02144.erroragent.service.InspectConfigService;
import io.github.liufor02144.erroragent.service.judge.InspectJudgeService;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

/**
 * 判定逻辑的验证。
 *
 * <p>判据是模版上的「业务预期」标记，<b>不是「在不在模版库里」</b> ——
 * 模版库收录全部模版，在库只代表这个异常有身份了，不代表允许发生。
 */
public class InspectJudgeTest {

    private InspectJudgeService judgeService;
    private InspectStatMapper statMapper;
    /** 本轮命中的模版，由采集层匹配后传入判定 */
    private InspectLibrary matched;
    private InspectConfigService cfg;

    @Before
    public void setUp() {
        judgeService = new InspectJudgeService();

        statMapper = Mockito.mock(InspectStatMapper.class);
        Mockito.when(statMapper.sumCount(ArgumentMatchers.anyLong(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(5000L);

        cfg = Mockito.mock(InspectConfigService.class);
        Mockito.when(cfg.getInt(ArgumentMatchers.anyString(), ArgumentMatchers.anyInt()))
                .thenAnswer(i -> i.getArgument(1));
        // 突增相关用例默认打开突增判定；关闭后的行为单独验证
        Mockito.when(cfg.getBool(ArgumentMatchers.eq(InspectConfigKeys.JUDGE_SURGE_ENABLED),
                ArgumentMatchers.anyBoolean())).thenReturn(true);

        matched = null;

        ReflectionTestUtils.setField(judgeService, "statMapper", statMapper);
        ReflectionTestUtils.setField(judgeService, "configService", cfg);
    }

    /** 判定入口。matched 为 null 表示这条还没匹配到任何模版 */
    private JudgeResult judge(long hourCount) {
        return judgeService.judge(fp(), matched, false, hourCount, new Date());
    }

    @Test
    public void 还没匹配到模版的异常要爆() {
        JudgeResult r = judge(100L);

        Assert.assertEquals(JudgeResult.NEW, r.getJudgeState());
        Assert.assertTrue(r.isActionable());
        Assert.assertTrue(r.getReason().contains("尚未匹配到异常模版"));
    }

    @Test
    public void 有模版但未确认业务预期的异常同样要爆() {
        // 在模版库里 != 允许发生。模版库收录全部模版，
        // 只有标记过业务预期的才不报
        template(false);

        JudgeResult r = judge(100L);

        Assert.assertEquals(JudgeResult.NEW, r.getJudgeState());
        Assert.assertTrue(r.isActionable());
        Assert.assertTrue(r.getReason().contains("未确认为业务预期"));
    }

    @Test
    public void 未确认的异常不因为见过就闭嘴() {
        // 这是 Redis 超时持续一整天的场景：第一轮判 NEW，第二轮、第 N 轮仍然判 NEW。
        // 「报过一次就沉默」会让持续故障在最需要被看见的时候消失
        template(false);
        for (int round = 0; round < 5; round++) {
            Assert.assertEquals("第 " + (round + 1) + " 轮仍应判 NEW",
                    JudgeResult.NEW, judge(100L).getJudgeState());
        }
    }

    @Test
    public void 业务预期内且量级正常时不爆() {
        template(true);
        baseline(50.0, 7);

        JudgeResult r = judge(60L);

        Assert.assertEquals(JudgeResult.QUIET, r.getJudgeState());
        Assert.assertFalse(r.isActionable());
        Assert.assertTrue(r.isInLibrary());
        Assert.assertTrue(r.getReason().contains("允许发生"));
    }

    @Test
    public void 业务预期内但突增时仍要爆() {
        // 「商品已下架」平时 50 次是正常的，5000 次就不是了 ——
        // 标记业务预期不等于屏蔽，否则这里会变成看不见的盲区
        template(true);
        baseline(50.0, 7);

        JudgeResult r = judge(5000L);

        Assert.assertEquals(JudgeResult.SURGE, r.getJudgeState());
        Assert.assertTrue(r.isActionable());
        Assert.assertTrue(r.getReason().contains("突增"));
    }

    @Test
    public void 基线样本不足时不判突增() {
        // 冷启动初期 AVG 仍会返回值但不具统计意义，据此判突增会大量误报
        template(true);
        baseline(2.0, 1);

        JudgeResult r = judge(500L);

        Assert.assertEquals(JudgeResult.QUIET, r.getJudgeState());
        Assert.assertTrue(r.getReason().contains("样本不足"));
    }

    @Test
    public void 首轮只建基线不产生任何事件() {
        // 首轮库里一个条目都没有，照常判会把全部存量判成库外，实测炸出 800+ 事件
        JudgeResult r = judgeService.judge(fp(), matched, true, 100_000L, new Date());

        Assert.assertEquals(JudgeResult.QUIET, r.getJudgeState());
        Assert.assertFalse("首轮量级再大也不该产生事件", r.isActionable());
        Assert.assertFalse(r.isNeedAi());
        Assert.assertTrue(r.getReason().contains("首轮"));
    }

    @Test
    public void 低频异常同样要分析和上报() {
        // 「每小时 1 次、连续 5 小时」的 Redis 超时：次数少不等于不严重。
        // 旧实现按 24h 次数设门槛，会让这类异常永远拿不到严重度，也就永远推不出去
        Mockito.when(statMapper.sumCount(ArgumentMatchers.anyLong(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(5L);

        JudgeResult r = judge(1L);

        Assert.assertEquals(JudgeResult.NEW, r.getJudgeState());
        Assert.assertTrue("事件照记", r.isActionable());
        Assert.assertTrue("也要送 AI —— 防刷 token 靠事件维度限流，不靠「小事不问」", r.isNeedAi());
    }

    /** @param businessExpected 是否已被人标记为业务预期内 */
    /**
     * 当前量和基线要比同样长度的时间。
     *
     * <p>实测 bug：拿「本小时到现在」比「整小时均值」，16:05 时当前量只有 5 分钟，
     * 天然不可能超过整小时均值的 N 倍。现在比最近 10 分钟，基线折算成 10 分钟。
     */
    @Test
    public void 突增按同样长度的时间段比较() {
        template(true);
        baseline(60.0, 7);   // 平时每小时 60 次，即每 10 分钟 10 次

        JudgeResult r = judgeService.judge(fp(), matched, false, 40L, 10, new Date());

        Assert.assertEquals("10 分钟 40 次是平时的 4 倍，应判突增", JudgeResult.SURGE, r.getJudgeState());
        Assert.assertTrue(r.getReason(), r.getReason().contains("最近 10 分钟"));
    }

    @Test
    public void 零星波动不算突增() {
        // 平时每小时 0.5 次，这 10 分钟来了 5 次 —— 倍数很大，但只是零星几条
        template(true);
        baseline(0.5, 7);

        JudgeResult r = judgeService.judge(fp(), matched, false, 5L, 10, new Date());

        Assert.assertEquals(JudgeResult.QUIET, r.getJudgeState());
    }

    /**
     * 统计表样本不够时，基线从 SLS 取。
     *
     * <p>实测 bug：统计表只记录巡检服务开着时的数据，本地间歇运行 3 天，
     * 同一小时最多 2 天样本、够不到 3 天 —— 所有异常都「基线样本不足」，突增一次都判不出来。
     */
    @Test
    public void 统计表样本不足时用SLS基线() {
        template(true);
        baseline(2.0, 2);    // 统计表只有 2 天：够不到门槛
        io.github.liufor02144.erroragent.service.judge.SlsBaselineService sls =
                Mockito.mock(io.github.liufor02144.erroragent.service.judge.SlsBaselineService.class);
        BaselineStat fromSls = new BaselineStat();
        fromSls.setAvgCnt(12.0);
        fromSls.setSamples(7);
        Mockito.when(sls.baselineOf(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(fromSls);
        ReflectionTestUtils.setField(judgeService, "slsBaseline", sls);

        JudgeResult r = judgeService.judge(fp(), matched, false, 50L, 10, new Date());

        Assert.assertEquals("SLS 基线 7 天可用，50 次远超每 10 分钟 2 次", JudgeResult.SURGE, r.getJudgeState());
        Assert.assertEquals(Integer.valueOf(7), r.getBaselineSamples());
    }

    @Test
    public void 突增判定关闭后业务预期内的异常量再大也不报() {
        // 突增噪音太多时整体关掉，只报新增
        Mockito.when(cfg.getBool(ArgumentMatchers.eq(InspectConfigKeys.JUDGE_SURGE_ENABLED),
                ArgumentMatchers.anyBoolean())).thenReturn(false);
        template(true);
        baseline(50.0, 7);

        JudgeResult r = judge(5000L);

        Assert.assertEquals(JudgeResult.QUIET, r.getJudgeState());
        Assert.assertFalse(r.isActionable());
        Assert.assertTrue(r.getReason(), r.getReason().contains("突增判定已关闭"));
    }

    @Test
    public void 突增判定关闭不影响新增() {
        Mockito.when(cfg.getBool(ArgumentMatchers.eq(InspectConfigKeys.JUDGE_SURGE_ENABLED),
                ArgumentMatchers.anyBoolean())).thenReturn(false);
        template(false);

        Assert.assertEquals(JudgeResult.NEW, judge(100L).getJudgeState());
    }

    private void template(boolean businessExpected) {
        InspectLibrary item = new InspectLibrary();
        item.setId(9L);
        item.setMatchType(InspectLibrary.TYPE_TEMPLATE);
        item.setMatchValue("商品{ID}已下架");
        item.setTemplate("商品{ID}已下架");
        item.setPattern("商品.{1,20}已下架");
        item.setBusinessExpected(businessExpected ? 1 : 0);
        matched = item;
    }

    private void baseline(double avg, int samples) {
        BaselineStat stat = new BaselineStat();
        stat.setAvgCnt(avg);
        stat.setSamples(samples);
        Mockito.when(statMapper.avgBaseline(ArgumentMatchers.anyLong(), ArgumentMatchers.anyInt(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(stat);
    }

    private InspectFingerprint fp() {
        InspectFingerprint f = new InspectFingerprint();
        f.setId(1L);
        f.setAppId("order-service");
        f.setFpHash("abc");
        f.setFirstSeenTime(new Date());
        f.setLastSeenTime(new Date());
        return f;
    }
}
