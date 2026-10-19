package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.entity.InspectEvent;
import io.github.liufor02144.erroragent.service.InspectConfigService;
import io.github.liufor02144.erroragent.service.push.InspectEventPushService;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;

/**
 * 升级重推的验证。
 *
 * <p>核心诉求：<b>异常持续存在时要越叫越响，不是报过一次就沉默。</b>
 * Redis 超时持续一整天却只在第一分钟响过一次，等于没有告警。
 */
public class EventRepushTest {

    private InspectEventPushService pushService;

    @Before
    public void setUp() {
        pushService = new InspectEventPushService();
        InspectConfigService cfg = Mockito.mock(InspectConfigService.class);
        Mockito.when(cfg.getList(ArgumentMatchers.anyString()))
                .thenReturn(Arrays.asList("1", "2", "4", "8", "24"));
        ReflectionTestUtils.setField(pushService, "configService", cfg);
    }

    @Test
    public void 重推间隔逐级递增() {
        Assert.assertEquals(1L, pushService.intervalHoursFor(1));
        Assert.assertEquals(2L, pushService.intervalHoursFor(2));
        Assert.assertEquals(4L, pushService.intervalHoursFor(3));
        Assert.assertEquals(8L, pushService.intervalHoursFor(4));
        Assert.assertEquals(24L, pushService.intervalHoursFor(5));
    }

    @Test
    public void 档位用完后保持最后一档而不是彻底沉默() {
        // 长期没人处理的事件应该维持一天一次的提醒。
        // 若继续拉长间隔，最严重的问题反而会最先消失在视野里
        Assert.assertEquals(24L, pushService.intervalHoursFor(9));
        Assert.assertEquals(24L, pushService.intervalHoursFor(100));
    }

    @Test
    public void 配置异常时退回默认档位而不是不推() {
        InspectConfigService broken = Mockito.mock(InspectConfigService.class);
        Mockito.when(broken.getList(ArgumentMatchers.anyString()))
                .thenReturn(Arrays.asList("abc", "", "-5"));
        ReflectionTestUtils.setField(pushService, "configService", broken);

        Assert.assertEquals("配置写错了也不能让告警彻底不响", 1L, pushService.intervalHoursFor(1));
        Assert.assertEquals(24L, pushService.intervalHoursFor(5));
    }

    @Test
    public void 持续时长按人可读方式呈现() {
        Assert.assertEquals("刚刚发生", event(0).durationText());
        Assert.assertEquals("45 分钟", event(45).durationText());
        Assert.assertEquals("3 小时", event(180).durationText());
        Assert.assertEquals("3 小时 20 分", event(200).durationText());
        Assert.assertEquals("2 天 3 小时", event(60 * 51).durationText());
    }

    @Test
    public void 缺时间时持续时长不抛异常() {
        InspectEvent empty = new InspectEvent();
        Assert.assertEquals(0L, empty.durationMinutes());
        Assert.assertEquals("刚刚发生", empty.durationText());
    }

    private InspectEvent event(int durationMinutes) {
        Calendar cal = Calendar.getInstance();
        Date end = cal.getTime();
        cal.add(Calendar.MINUTE, -durationMinutes);

        InspectEvent e = new InspectEvent();
        e.setFirstSeenTime(cal.getTime());
        e.setLastSeenTime(end);
        return e;
    }
}
