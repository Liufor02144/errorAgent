package io.github.liufor02144.erroragent;

import org.junit.Assert;
import org.junit.Test;

/**
 * 分桶粒度必须匹配扫描节奏 —— 这是采集窗口能做小的唯一前提。
 *
 * <p><b>原来的问题</b>：stat 按整小时分桶（3600 秒），而扫描 5 分钟一次。
 * 桶只有被窗口<b>完整包含</b>过，覆盖写进去的才是终值，于是窗口被迫一路回退
 * 到整点，跨度 60~120 分钟。实测 68 分钟的窗口跑 258 秒，
 * 逼近 300 秒的扫描间隔 —— 每小时后半段巡检会静默停摆。
 *
 * <p>根因不是「窗口选大了」，是「桶选大了」。桶 = 5 分钟之后，
 * 10 分钟的窗口就够，且每个桶都必然被完整覆盖一次。
 *
 * <p><b>为什么不改成累加</b>：窗口重叠就会重复计数 ——
 * 事件量级曾因此虚高 2~3 倍。覆盖写入不怕重叠，代价只是窗口要盖住整个桶。
 */
public class StatBucketTest {

    private static final int BUCKET_SECONDS = 300;
    private static final int WINDOW_BUCKETS = 2;

    /** 复刻 InspectCollectService.resolveWindow */
    private static long[] resolveWindow(long now) {
        long bucketMs = BUCKET_SECONDS * 1000L;
        long alignedNow = now - (now % bucketMs);
        return new long[]{alignedNow - WINDOW_BUCKETS * bucketMs, now};
    }

    /** 复刻 SLS 侧的分桶：__time__ - __time__ % BUCKET_SECONDS */
    private static long bucketOf(long epochMs) {
        long sec = epochMs / 1000;
        return sec - sec % BUCKET_SECONDS;
    }

    private static long ms(long epochSec) {
        return epochSec * 1000L;
    }

    @Test
    public void 窗口跨度就是两个桶() {
        long now = ms(1_758_000_000L + 137);
        long[] w = resolveWindow(now);
        long span = (w[1] - w[0]) / 1000;
        Assert.assertTrue("跨度应在 10~15 分钟之间，实际 " + span + " 秒",
                span >= WINDOW_BUCKETS * BUCKET_SECONDS
                        && span < (WINDOW_BUCKETS + 1) * BUCKET_SECONDS);
    }

    @Test
    public void 每个桶都会被某一轮完整包含() {
        // 这是覆盖写入正确性的核心不变量：
        // 桶只被数了一半时写入的是偏小值，必须有后续某轮把它完整盖一次
        long start = ms(1_758_000_000L);
        for (int offsetSec = 0; offsetSec < 3600; offsetSec += 37) {
            long now = start + ms(offsetSec);
            long[] w = resolveWindow(now);

            // 窗口内最后一个【已经收口】的桶
            long closedBucket = bucketOf(w[1]) - BUCKET_SECONDS;
            Assert.assertTrue(
                    "已收口的桶必须被窗口完整包含：bucket=" + closedBucket + " from=" + w[0] / 1000,
                    w[0] / 1000 <= closedBucket);
            Assert.assertTrue(
                    "桶的右边界也要在窗口内",
                    (closedBucket + BUCKET_SECONDS) * 1000L <= w[1]);
        }
    }

    @Test
    public void 漏跑一轮后下一轮仍能补上() {
        // 窗口给了一个桶的余量：14:27 这轮没跑成，14:32 这轮的窗口
        // 仍然盖得住 14:20 那个桶
        long t1 = ms(1_758_000_000L);
        long[] missed = resolveWindow(t1);
        long[] next = resolveWindow(t1 + ms(BUCKET_SECONDS));

        long bucketAtMissed = bucketOf(missed[1]) - BUCKET_SECONDS;
        Assert.assertTrue("漏跑那轮本该覆盖的桶，下一轮仍要盖到",
                next[0] / 1000 <= bucketAtMissed);
    }

    @Test
    public void 窗口至少两个桶否则边界桶永远只有一半() {
        // 钉住这个常量：改成 1 会让每个桶都只被数到调度时刻为止
        Assert.assertTrue("WINDOW_BUCKETS 必须 >= 2", WINDOW_BUCKETS >= 2);
    }

    @Test
    public void 桶粒度应当小于等于窗口的一半() {
        // 桶 > 窗口/2 时，存在永远不被完整包含的桶
        int windowSeconds = WINDOW_BUCKETS * BUCKET_SECONDS;
        Assert.assertTrue("桶必须 <= 窗口的一半",
                BUCKET_SECONDS * 2 <= windowSeconds);
    }

    @Test
    public void 相比整小时分桶窗口显著变小() {
        long now = ms(1_758_000_000L);
        long[] w = resolveWindow(now);
        long span = (w[1] - w[0]) / 1000;

        // 旧实现最坏情况：回退到上一整点 = 最长 7200 秒
        Assert.assertTrue("必须远小于旧实现的最坏情况 7200 秒", span < 7200 / 4);
    }
}
