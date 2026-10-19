package io.github.liufor02144.erroragent;

import org.junit.Assert;
import org.junit.Test;

import java.util.Calendar;
import java.util.Date;

/**
 * 事件累计量重算的边界。
 *
 * <p>线上真实踩到过：237 个待处理事件里，绝大多数 total_count 被写成 0，
 * 而 stat 表里明明记着 4520 次。页面上看起来像「异常没发生过」，
 * 量级排序也彻底失效。
 *
 * <p>根因是两件事叠加：
 * <ol>
 *   <li>stat 表按<b>整点</b>存（stat_hour = 17:00），而查询下界传的是事件首现的
 *       墙上时间（17:52）。窗口 [17:52, 17:57] 里没有任何整点，零行</li>
 *   <li>SQL 写的是 {@code COALESCE(SUM(cnt), 0)}，零行返回 0 而不是 null，
 *       于是 Java 侧 {@code sum == null} 的兜底永远不触发，0 被当成真实值写回</li>
 * </ol>
 *
 * <p>单独看每一处都「说得通」，合起来才致命 —— 这类 bug 只能靠测试守。
 */
public class EventRecountTest {

    /** 复刻 InspectEventService.floorToHour */
    private static Date floorToHour(Date d) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(d);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    private static Date at(int hour, int minute) {
        Calendar cal = Calendar.getInstance();
        cal.set(2026, Calendar.SEPTEMBER, 21, hour, minute, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    /**
     * 重算逻辑：下界取整 + 取大值。
     *
     * @param statSum stat 表返回值，null 表示零行
     */
    private static long recount(Long statSum, long observedThisRound) {
        return statSum == null ? observedThisRound : Math.max(statSum, observedThisRound);
    }

    @Test
    public void 下界必须向下取整到整点否则同小时内的事件一行都匹配不上() {
        Date firstSeen = at(17, 52);
        Date floored = floorToHour(firstSeen);

        Calendar cal = Calendar.getInstance();
        cal.setTime(floored);
        Assert.assertEquals("分钟必须归零", 0, cal.get(Calendar.MINUTE));
        Assert.assertEquals("秒必须归零", 0, cal.get(Calendar.SECOND));
        Assert.assertEquals("小时不能变", 17, cal.get(Calendar.HOUR_OF_DAY));

        // stat 表里 17:00 那行，用原始时间筛不到，取整后能筛到
        Date statHour = at(17, 0);
        Assert.assertTrue("取整前 17:00 < 17:52，会被排除",
                statHour.before(firstSeen));
        Assert.assertFalse("取整后 17:00 不早于下界，能被包含",
                statHour.before(floored));
    }

    @Test
    public void stat返回零时不能把事件量级覆盖成零() {
        // 这就是线上那 206 个事件的情形：本轮实测 4520，stat 查询零行返回 0
        Assert.assertEquals("COALESCE 返回的 0 不能盖掉实测值",
                4520L, recount(0L, 4520L));
        Assert.assertEquals("null 同样要退回实测值",
                4520L, recount(null, 4520L));
    }

    @Test
    public void 重算值大于本轮实测时取重算值() {
        // 事件已持续几小时，stat 表里有历史累计，本轮只观测到最近一轮的量
        Assert.assertEquals("历史累计要补齐进来",
                9000L, recount(9000L, 120L));
    }

    @Test
    public void 计数永不倒退() {
        // 任意组合下，结果都不低于本轮实测 —— 这是不变量
        long[][] cases = {{0, 13}, {5, 13}, {13, 13}, {900, 13}, {0, 0}};
        for (long[] c : cases) {
            long got = recount(c[0], c[1]);
            Assert.assertTrue(
                    "stat=" + c[0] + " 实测=" + c[1] + " 得到 " + got + "，低于实测值",
                    got >= c[1]);
        }
    }

    @Test
    public void 跨小时的事件取整后能覆盖完整区间() {
        Date from = at(14, 23);
        Date to = at(17, 52);
        Date floored = floorToHour(from);
        // 14:00 / 15:00 / 16:00 / 17:00 四行都应落在 [floored, to] 内
        int[] hours = {14, 15, 16, 17};
        for (int h : hours) {
            Date statHour = at(h, 0);
            Assert.assertFalse(h + ":00 应落在窗口内（不早于下界）",
                    statHour.before(floored));
            Assert.assertFalse(h + ":00 应落在窗口内（不晚于上界）",
                    statHour.after(to));
        }
    }
}
