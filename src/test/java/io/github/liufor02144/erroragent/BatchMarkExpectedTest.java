package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.entity.InspectLibrary;
import io.github.liufor02144.erroragent.mapper.InspectEventMapper;
import io.github.liufor02144.erroragent.mapper.InspectLibraryMapper;
import io.github.liufor02144.erroragent.service.judge.InspectLibraryService;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Map;

/**
 * 页面勾选批量标业务预期。
 *
 * <p>一次勾几十条时没人会逐条看。夹在里面的空指针 / SQL 异常 / 超时被顺手带进去，
 * 就永久静默了 —— 所以批量入口和「基线归零」守同一条规则：技术故障不随批量放行，
 * 要标只能点开单条标。
 */
public class BatchMarkExpectedTest {

    private InspectLibraryService service;
    private InspectLibraryMapper libraryMapper;
    private InspectEventMapper eventMapper;

    @Before
    public void setUp() {
        service = Mockito.spy(new InspectLibraryService());
        libraryMapper = Mockito.mock(InspectLibraryMapper.class);
        eventMapper = Mockito.mock(InspectEventMapper.class);
        ReflectionTestUtils.setField(service, "libraryMapper", libraryMapper);
        ReflectionTestUtils.setField(service, "eventMapper", eventMapper);
        Mockito.doNothing().when(service).refresh();
        Mockito.when(libraryMapper.updateBusinessExpected(
                ArgumentMatchers.anyLong(), ArgumentMatchers.eq(1),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString())).thenReturn(1);

        stub(1L, "业务异常 BusinessException: 商品已下架", 0);
        stub(2L, "java.lang.NullPointerException: null", 0);
        stub(3L, "java.net.SocketTimeoutException: Read timed out", 0);
        stub(4L, "券码不存在", 1);
    }

    private void stub(Long id, String template, int expected) {
        InspectLibrary l = new InspectLibrary();
        l.setId(id);
        l.setTemplate(template);
        l.setBusinessExpected(expected);
        Mockito.when(libraryMapper.selectById(id)).thenReturn(l);
    }

    @Test
    public void 技术故障不随批量放行() {
        Map<String, Object> r = service.markExpectedBatch(Arrays.asList(1L, 2L, 3L, 4L, 99L),
                "批量确认", "tester");

        Assert.assertEquals("只有商品已下架该被标", 1, r.get("marked"));
        Assert.assertEquals("空指针和超时要被保护", 2, r.get("protectedCount"));
        Assert.assertEquals("本来就是业务预期的不重复标", 1, r.get("already"));
        Assert.assertEquals(1, r.get("missing"));

        Mockito.verify(libraryMapper).updateBusinessExpected(1L, 1, "批量确认", "tester");
        Mockito.verify(libraryMapper, Mockito.never()).updateBusinessExpected(
                ArgumentMatchers.eq(2L), ArgumentMatchers.anyInt(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.verify(libraryMapper, Mockito.never()).updateBusinessExpected(
                ArgumentMatchers.eq(3L), ArgumentMatchers.anyInt(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    public void 标记后关闭该模版下的未处理事件() {
        service.markExpectedBatch(Arrays.asList(1L), "批量确认", "tester");
        Mockito.verify(eventMapper).closeByTemplateId(
                ArgumentMatchers.eq(1L), ArgumentMatchers.anyString(), ArgumentMatchers.eq("tester"));
    }
}
