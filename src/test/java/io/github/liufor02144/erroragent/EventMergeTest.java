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
 * 事件累计时的合并规则。
 *
 * <p>实测事故：一个新服务的空指针被并进旧事件后，服务列表被本轮的直接覆盖、
 * 累计次数从 9 被「重算」成 7 —— 于是「扩散到新服务」「量级涨到 3 倍」两个
 * 重新分析的条件都看不出来，新问题既没分析也没推送。
 */
public class EventMergeTest {

    private InspectEventService service;
    private InspectEventMapper eventMapper;
    private InspectStatMapper statMapper;

    @Before
    public void setUp() {
        service = new InspectEventService();
        eventMapper = Mockito.mock(InspectEventMapper.class);
        statMapper = Mockito.mock(InspectStatMapper.class);
        ReflectionTestUtils.setField(service, "eventMapper", eventMapper);
        ReflectionTestUtils.setField(service, "statMapper", statMapper);
    }

    private EventAggregate agg(String app, String frame, boolean perLocation) {
        InspectLibrary tpl = new InspectLibrary();
        tpl.setId(624L);
        tpl.setTemplate("NullPointerException");
        JudgeResult j = new JudgeResult();
        j.setJudgeState(JudgeResult.NEW);
        j.setReason("该异常模版未确认为业务预期");
        EventAggregate a = new EventAggregate(tpl, j);
        a.setPerLocation(perLocation);
        InspectFingerprint fp = new InspectFingerprint();
        fp.setId(26025L);
        fp.setAppId(app);
        fp.setBizFrame(frame);
        fp.setExcSimple("NullPointerException");
        fp.setLastSeenTime(new Date());
        a.add(fp, j, 2);
        return a;
    }

    private InspectEvent open(String apps, String frames, long total) {
        InspectEvent e = new InspectEvent();
        e.setId(600L);
        e.setAppList(apps);
        e.setFrameList(frames);
        e.setTotalCount(total);
        e.setFirstSeenTime(new Date(System.currentTimeMillis() - 3600_000L));
        return e;
    }

    @Test
    public void 累计时服务和代码位置取并集且次数不减() {
        Mockito.when(eventMapper.selectOpenByTemplateId(624L))
                .thenReturn(open("api-gateway", "AuthFilter.java:88", 9));
        Mockito.when(statMapper.sumTotalByFpIds(ArgumentMatchers.anyList(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(7L);

        service.record(agg("report-task", "BalanceExportModel.java:154", false));

        ArgumentCaptor<Long> total = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> appCount = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> apps = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> frames = ArgumentCaptor.forClass(String.class);
        Mockito.verify(eventMapper).accumulate(ArgumentMatchers.eq(600L), total.capture(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                appCount.capture(), apps.capture(), frames.capture());
        Assert.assertEquals("重算只覆盖本轮指纹，不能让次数倒退", Long.valueOf(9L), total.getValue());
        Assert.assertEquals("新服务要能被看出来", Integer.valueOf(2), appCount.getValue());
        Assert.assertTrue(apps.getValue().contains("api-gateway"));
        Assert.assertTrue(apps.getValue().contains("report-task"));
        Assert.assertTrue(frames.getValue().contains("AuthFilter.java:88"));
        Assert.assertTrue(frames.getValue().contains("BalanceExportModel.java:154"));
    }

    @Test
    public void 按位置成事件的不会被并进同模版的旧事件() {
        // 同模版下有一个开着的旧事件（网关的），但新空指针在另一个位置：必须新建
        Mockito.when(eventMapper.selectOpenByTemplateId(624L))
                .thenReturn(open("api-gateway", "AuthFilter.java:88", 9));

        InspectEvent created = service.record(agg("report-task",
                "BalanceExportModel.java:154", true));

        Assert.assertNotNull(created);
        Assert.assertTrue("应当新建事件", created.isJustCreated());
        Assert.assertEquals(InspectEvent.SCOPE_LOCATION, created.getScope());
        Assert.assertTrue("标题要带位置，否则同模版的几条分不清：" + created.getHeadline(),
                created.getHeadline().contains("BalanceExportModel.java:154"));
        Mockito.verify(eventMapper, Mockito.never()).accumulate(ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    public void 同一位置再次出现累计到自己的事件上() {
        InspectEvent mine = open("report-task", "BalanceExportModel.java:154", 2);
        Mockito.when(eventMapper.selectOpenByLocation(624L, "report-task",
                "BalanceExportModel.java:154", 0)).thenReturn(mine);

        InspectEvent r = service.record(agg("report-task",
                "BalanceExportModel.java:154", true));

        Assert.assertFalse(r.isJustCreated());
        Mockito.verify(eventMapper, Mockito.never()).insert(ArgumentMatchers.any());
    }
}
