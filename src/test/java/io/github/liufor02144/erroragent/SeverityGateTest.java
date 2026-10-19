package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.entity.InspectAnalysis;
import io.github.liufor02144.erroragent.entity.InspectEvent;
import io.github.liufor02144.erroragent.mapper.InspectAnalysisMapper;
import io.github.liufor02144.erroragent.mapper.InspectEventMapper;
import io.github.liufor02144.erroragent.service.push.InspectEventPushService;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

/**
 * 推进群里的事件<b>必须有定级</b>。
 *
 * <p>推送意味着「需要你现在关注」。连严重到什么程度都还说不出来时，
 * 没有资格占用别人的注意力 —— 等 AI 分析出定级了下一轮再推。
 *
 * <p><b>实测事故</b>：原来 selectPushable 的 SQL 写的是
 * {@code severity IS NULL OR severity IN (...)}，空定级被当成「放行」。
 * 结果 36 条待处理事件<b>全部未定级</b>、其中 34 条已经推出去了 ——
 * {@code push.severity.include = P0,P1} 这道门槛从上线起就没生效过，
 * 群里每张卡的标题都写着「未定级」。
 *
 * <p>根因是指纹侧根本不产出 severity，只有 AI 分析会给。
 * 所以闸门要做两件事：从分析结论取定级并回写事件，取不到就不推。
 */
public class SeverityGateTest {

    private InspectEventPushService service;
    private InspectEventMapper eventMapper;
    private InspectAnalysisMapper analysisMapper;

    private static final List<String> ALLOWED = Arrays.asList("P0", "P1");

    @Before
    public void setUp() {
        service = new InspectEventPushService();
        eventMapper = Mockito.mock(InspectEventMapper.class);
        analysisMapper = Mockito.mock(InspectAnalysisMapper.class);
        ReflectionTestUtils.setField(service, "eventMapper", eventMapper);
        ReflectionTestUtils.setField(service, "analysisMapper", analysisMapper);
    }

    private String gate(InspectEvent event) {
        return (String) ReflectionTestUtils.invokeMethod(
                service, "severityGate", event, ALLOWED);
    }

    private InspectEvent event(Long id, Long fpId, String severity) {
        InspectEvent e = new InspectEvent();
        e.setId(id);
        e.setFpId(fpId);
        e.setSeverity(severity);
        return e;
    }

    private void stubAnalysis(Long fpId, String severity) {
        InspectAnalysis a = new InspectAnalysis();
        a.setSeverity(severity);
        Mockito.when(analysisMapper.selectLatestByFpId(fpId)).thenReturn(a);
    }

    @Test
    public void 事件自带定级且达标时放行() {
        Assert.assertNull(gate(event(1L, 10L, "P1")));
        Assert.assertNull(gate(event(2L, 20L, "P0")));
    }

    @Test
    public void 未定级且分析也没给时不推() {
        Mockito.when(analysisMapper.selectLatestByFpId(ArgumentMatchers.anyLong()))
                .thenReturn(null);
        String skip = gate(event(3L, 30L, null));
        Assert.assertNotNull("没有定级就不该推", skip);
        Assert.assertTrue("要说明是因为还没定级：" + skip, skip.contains("尚未定级"));
    }

    @Test
    public void 未定级时从分析结论取并回写事件() {
        stubAnalysis(40L, "P1");
        InspectEvent e = event(4L, 40L, null);

        Assert.assertNull("分析给了 P1，应当放行", gate(e));
        Assert.assertEquals("定级要落到事件上，页面和卡片才有得显示", "P1", e.getSeverity());
        Mockito.verify(eventMapper).updateSeverity(4L, "P1");
    }

    @Test
    public void 定级未达门槛时不推() {
        stubAnalysis(50L, "P3");
        String skip = gate(event(5L, 50L, null));
        Assert.assertNotNull(skip);
        Assert.assertTrue("要说明是定级不够：" + skip, skip.contains("P3"));
    }

    @Test
    public void 空串定级等同于未定级() {
        // severity 存成空串而不是 null 时，不能被当成「有定级」蒙混过关
        Mockito.when(analysisMapper.selectLatestByFpId(ArgumentMatchers.anyLong()))
                .thenReturn(null);
        String skip = gate(event(6L, 60L, "   "));
        Assert.assertNotNull(skip);
        Assert.assertTrue(skip.contains("尚未定级"));
    }

    @Test
    public void 查分析失败时不推而不是崩掉() {
        Mockito.when(analysisMapper.selectLatestByFpId(ArgumentMatchers.anyLong()))
                .thenThrow(new RuntimeException("db down"));
        String skip = gate(event(7L, 70L, null));
        Assert.assertNotNull("查不到就按未定级处理，不能抛异常打断整轮推送", skip);
    }

    @Test
    public void 没有指纹的事件也要有定级才推() {
        // 没有指纹的事件（fp_id 为空）同样不能靠「查不到分析」蒙混过去
        String skip = gate(event(8L, null, null));
        Assert.assertNotNull(skip);
        Assert.assertTrue(skip.contains("尚未定级"));
    }
}
