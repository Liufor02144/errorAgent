package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.config.InspectProperties;
import io.github.liufor02144.erroragent.config.InspectSwitch;
import io.github.liufor02144.erroragent.entity.InspectEvent;
import io.github.liufor02144.erroragent.mapper.InspectAnalysisMapper;
import io.github.liufor02144.erroragent.mapper.InspectEventMapper;
import io.github.liufor02144.erroragent.mapper.InspectSampleMapper;
import io.github.liufor02144.erroragent.service.InspectConfigService;
import io.github.liufor02144.erroragent.service.push.FeishuApiClient;
import io.github.liufor02144.erroragent.service.push.InspectCardBuilder;
import io.github.liufor02144.erroragent.service.push.InspectEventPushService;
import io.github.liufor02144.erroragent.service.push.SameRequestMerger;
import io.github.liufor02144.erroragent.service.run.InspectRunRecorder;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 同一批请求打出的多个事件只推一张卡片。
 *
 * <p>生产实例：物流渠道的计费接口返回为空，每次请求打两条 ERROR ——
 * 「解密物流渠道报文失败」和「查询物流渠道账单出错」，28 次请求完全重合，群里连推两张卡片。
 */
public class SameRequestPushTest {

    private InspectEventPushService service;
    private InspectEventMapper eventMapper;
    private InspectCardBuilder cardBuilder;
    private FeishuApiClient apiClient;
    private InspectRunRecorder recorder;
    private SameRequestMerger merger;
    private final Set<String> sharedTraces = new HashSet<>(Arrays.asList("t1", "t2", "t3", "t4", "t5"));

    @Before
    public void setUp() {
        service = new InspectEventPushService();
        InspectSwitch sw = Mockito.mock(InspectSwitch.class);
        InspectConfigService cfg = Mockito.mock(InspectConfigService.class);
        Mockito.when(cfg.getList(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenReturn(Arrays.asList("P0", "P1", "P2"));
        Mockito.when(cfg.getInt(ArgumentMatchers.anyString(), ArgumentMatchers.anyInt()))
                .thenAnswer(i -> i.getArgument(1));
        Mockito.when(cfg.getBool(ArgumentMatchers.anyString(), ArgumentMatchers.anyBoolean()))
                .thenAnswer(i -> i.getArgument(1));
        eventMapper = Mockito.mock(InspectEventMapper.class);
        cardBuilder = Mockito.mock(InspectCardBuilder.class);
        Mockito.when(cardBuilder.buildEvent(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn("{}");
        apiClient = Mockito.mock(FeishuApiClient.class);
        FeishuApiClient.ApiResult ok = new FeishuApiClient.ApiResult();
        ok.setSuccess(true);
        ok.setMessageId("om_1");
        Mockito.when(apiClient.sendCard(ArgumentMatchers.any(), ArgumentMatchers.anyString())).thenReturn(ok);
        recorder = Mockito.mock(InspectRunRecorder.class);
        merger = Mockito.mock(SameRequestMerger.class);

        ReflectionTestUtils.setField(service, "inspectSwitch", sw);
        ReflectionTestUtils.setField(service, "configService", cfg);
        ReflectionTestUtils.setField(service, "eventMapper", eventMapper);
        ReflectionTestUtils.setField(service, "analysisMapper", Mockito.mock(InspectAnalysisMapper.class));
        ReflectionTestUtils.setField(service, "sampleMapper", Mockito.mock(InspectSampleMapper.class));
        ReflectionTestUtils.setField(service, "cardBuilder", cardBuilder);
        ReflectionTestUtils.setField(service, "apiClient", apiClient);
        ReflectionTestUtils.setField(service, "properties", new InspectProperties());
        ReflectionTestUtils.setField(service, "sameRequestMerger", merger);
        ReflectionTestUtils.setField(service, "runRecorder", recorder);
    }

    private InspectEvent event(long id, String headline, Set<String> traces) {
        InspectEvent e = new InspectEvent();
        e.setId(id);
        e.setFpId(id);
        e.setHeadline(headline);
        e.setSeverity("P2");
        e.setPushCount(0);
        e.setTotalCount(42L);
        e.setFirstSeenTime(new Date());
        e.setLastSeenTime(new Date());
        Mockito.when(merger.tracesOf(ArgumentMatchers.argThat(x -> x != null && x.getId() == id),
                ArgumentMatchers.<Map<Long, Set<String>>>any())).thenReturn(traces);
        return e;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void 同一批请求的两个事件只推一张卡片() {
        InspectEvent outer = event(828L, "查询物流渠道账单出错,降级处理null", sharedTraces);
        InspectEvent inner = event(827L, "解密物流渠道报文失败 -1|计费接口为空,请重试", sharedTraces);
        Mockito.when(eventMapper.selectPushable(ArgumentMatchers.anyLong(), ArgumentMatchers.anyList()))
                .thenReturn(Arrays.asList(outer, inner));

        int pushed = service.scanAndPush();

        Assert.assertEquals(1, pushed);
        Mockito.verify(apiClient, Mockito.times(1)).sendCard(ArgumentMatchers.any(), ArgumentMatchers.anyString());
        // 另一个事件并进卡片，并跟随主事件的推送节奏
        ArgumentCaptor<List<String>> related = ArgumentCaptor.forClass(List.class);
        Mockito.verify(cardBuilder).buildEvent(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), related.capture());
        Assert.assertEquals(1, related.getValue().size());
        Mockito.verify(eventMapper).markMerged(ArgumentMatchers.anyLong(), ArgumentMatchers.any());
    }

    @Test
    public void 和之前推过的事件是同一批请求时不再另起一张() {
        InspectEvent inner = event(827L, "解密物流渠道报文失败", sharedTraces);
        InspectEvent earlier = event(828L, "查询物流渠道账单出错", sharedTraces);
        earlier.setPushCount(1);
        Mockito.when(eventMapper.selectPushable(ArgumentMatchers.anyLong(), ArgumentMatchers.anyList()))
                .thenReturn(Collections.singletonList(inner));
        Mockito.when(eventMapper.selectRecentlyPushedOpen(ArgumentMatchers.any()))
                .thenReturn(Collections.singletonList(earlier));

        Assert.assertEquals(0, service.scanAndPush());
        Mockito.verify(apiClient, Mockito.never()).sendCard(ArgumentMatchers.any(), ArgumentMatchers.anyString());
        Mockito.verify(eventMapper).markMerged(ArgumentMatchers.eq(827L), ArgumentMatchers.any());
    }

    @Test
    public void 请求不重合或拿不到请求时照常各推各的() {
        InspectEvent a = event(1L, "渠道 token 认证失败", new HashSet<>(Arrays.asList("x1", "x2")));
        InspectEvent b = event(2L, "支付网关连接超时", new HashSet<>(Collections.singletonList("y1")));
        InspectEvent c = event(3L, "日志里没有 traceId 的异常", Collections.emptySet());
        Mockito.when(eventMapper.selectPushable(ArgumentMatchers.anyLong(), ArgumentMatchers.anyList()))
                .thenReturn(Arrays.asList(a, b, c));

        Assert.assertEquals(3, service.scanAndPush());
        Mockito.verify(eventMapper, Mockito.never()).markMerged(ArgumentMatchers.anyLong(), ArgumentMatchers.any());
    }
}
