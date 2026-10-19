package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.dto.EventAggregate;
import io.github.liufor02144.erroragent.dto.JudgeResult;
import io.github.liufor02144.erroragent.entity.InspectEvent;
import io.github.liufor02144.erroragent.entity.InspectFingerprint;
import io.github.liufor02144.erroragent.entity.InspectLibrary;
import io.github.liufor02144.erroragent.mapper.InspectEventMapper;
import io.github.liufor02144.erroragent.mapper.InspectStatMapper;
import io.github.liufor02144.erroragent.service.judge.InspectEventService;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

/**
 * 标注「已处理」之后又出现了怎么办。
 *
 * <p>两件事：
 * <ol>
 *   <li><b>处理前的存量日志不算复发。</b>采集窗口约 11 分钟、每 5 分钟一轮，
 *       刚点完「已处理」的下一轮，窗口里还是处理前那批日志。不挡的话，
 *       几分钟内就会冒出一条一模一样的新事件再推一次，「已处理」形同虚设</li>
 *   <li><b>处理之后真出现了，就是复发，要重新报。</b>并且要告诉看卡片的人：
 *       上次谁处理的、备注是什么 —— 修复没生效还是漏了分支，得从这接着查</li>
 * </ol>
 */
public class HandledRecurTest {

    private InspectEventService service;
    private InspectEventMapper eventMapper;

    private static final long T_HANDLE = 1_000_000_000_000L;

    @Before
    public void setUp() {
        service = new InspectEventService();
        eventMapper = Mockito.mock(InspectEventMapper.class);
        ReflectionTestUtils.setField(service, "eventMapper", eventMapper);
        ReflectionTestUtils.setField(service, "statMapper", Mockito.mock(InspectStatMapper.class));
        // 没有未处理事件 —— 上一条已被标注处理
        Mockito.when(eventMapper.selectOpenByTemplateId(ArgumentMatchers.anyLong())).thenReturn(null);
    }

    private EventAggregate agg(long lastSeenMs) {
        InspectLibrary tpl = new InspectLibrary();
        tpl.setId(77L);
        JudgeResult judge = new JudgeResult();
        judge.setJudgeState(JudgeResult.NEW);
        judge.setReason("该异常模版未确认为业务预期");
        EventAggregate a = new EventAggregate(tpl, judge);
        InspectFingerprint fp = new InspectFingerprint();
        fp.setId(9L);
        fp.setMsgTpl("【优惠券发放】异常");
        fp.setLastSeenTime(new Date(lastSeenMs));
        a.add(fp, judge, 3);
        return a;
    }

    private InspectEvent handled(String handler, String note) {
        InspectEvent e = new InspectEvent();
        e.setId(100L);
        e.setHandled(1);
        e.setHandler(handler);
        e.setHandleNote(note);
        e.setHandleTime(new Date(T_HANDLE));
        return e;
    }

    @Test
    public void 处理前的存量日志不产生新事件() {
        Mockito.when(eventMapper.selectLastHandled(77L, 9L)).thenReturn(handled("张三", "已修复"));

        Assert.assertNull("最后一条日志早于处理时间，是窗口里的旧日志",
                service.record(agg(T_HANDLE - 60_000)));
        Assert.assertNull("恰好等于处理时间也不算",
                service.record(agg(T_HANDLE)));
        Mockito.verify(eventMapper, Mockito.never()).insert(ArgumentMatchers.any());
    }

    @Test
    public void 处理后真出现了就是复发要重新建事件() {
        Mockito.when(eventMapper.selectLastHandled(77L, 9L)).thenReturn(handled("张三", "已修复，v1772 上线"));

        Assert.assertNotNull(service.record(agg(T_HANDLE + 60_000)));

        ArgumentCaptor<InspectEvent> c = ArgumentCaptor.forClass(InspectEvent.class);
        Mockito.verify(eventMapper).insert(c.capture());
        String reason = c.getValue().getTriggerReason();
        Assert.assertTrue("要标明是复发：" + reason, reason.startsWith("复发"));
        Assert.assertTrue("要说清谁处理的：" + reason, reason.contains("张三"));
        Assert.assertTrue("要带上处理备注：" + reason, reason.contains("v1772"));
        Assert.assertTrue("原判定依据不能丢：" + reason, reason.contains("未确认为业务预期"));
    }

    @Test
    public void 系统自动收敛关掉的不算复发() {
        // 静默 6 小时自动关闭 ≠ 有人修过，再出现只是沉寂后再现
        Mockito.when(eventMapper.selectLastHandled(77L, 9L))
                .thenReturn(handled("system", "系统自动收敛"));

        Assert.assertNotNull(service.record(agg(T_HANDLE + 60_000)));
        ArgumentCaptor<InspectEvent> c = ArgumentCaptor.forClass(InspectEvent.class);
        Mockito.verify(eventMapper).insert(c.capture());
        Assert.assertFalse(c.getValue().getTriggerReason().startsWith("复发"));
    }

    @Test
    public void 从没处理过的照常新建() {
        Mockito.when(eventMapper.selectLastHandled(77L, 9L)).thenReturn(null);
        Assert.assertNotNull(service.record(agg(T_HANDLE)));
        Mockito.verify(eventMapper).insert(ArgumentMatchers.any());
    }
}
