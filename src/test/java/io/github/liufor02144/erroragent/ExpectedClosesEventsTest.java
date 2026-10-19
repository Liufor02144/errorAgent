package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.entity.InspectLibrary;
import io.github.liufor02144.erroragent.mapper.InspectEventMapper;
import io.github.liufor02144.erroragent.mapper.InspectLibraryMapper;
import io.github.liufor02144.erroragent.service.judge.InspectLibraryService;
import io.github.liufor02144.erroragent.service.template.TemplateMatcher;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 标记业务预期，必须同时关闭该模版下已经报出来的事件。
 *
 * <p>不做这一步会出现自相矛盾的界面：<b>模版上写着「这本来就该发生」，
 * 事件却还挂在待处理里催你去处理</b>。实测留下了 12 条这样的记录 ——
 * 用户标完之后发现列表没变，只能怀疑标记到底生效了没有。
 *
 * <p>根因是「标记」只改变<b>以后怎么判</b>，而存量事件是<b>已经落库的结果</b>，
 * 两者没有联动。这类「配置变了但历史数据没跟着变」的问题在本项目里反复出现
 * （事件的模版归属、指纹的 template_id 回绑都是同一个形状）。
 *
 * <p>标记入口有四处：模版页、事件页、AI 复核、批量基线归零。
 * 靠每处都记得调用是不可靠的，所以除了逐处调用，还有一道按状态的无条件清扫。
 */
public class ExpectedClosesEventsTest {

    private InspectLibraryService service;
    private InspectEventMapper eventMapper;
    private InspectLibraryMapper libraryMapper;

    @Before
    public void setUp() {
        service = new InspectLibraryService();
        libraryMapper = Mockito.mock(InspectLibraryMapper.class);
        eventMapper = Mockito.mock(InspectEventMapper.class);
        TemplateMatcher matcher = Mockito.mock(TemplateMatcher.class);

        ReflectionTestUtils.setField(service, "libraryMapper", libraryMapper);
        ReflectionTestUtils.setField(service, "eventMapper", eventMapper);
        ReflectionTestUtils.setField(service, "templateMatcher", matcher);
    }

    private InspectLibrary tpl(long id, String text) {
        InspectLibrary item = new InspectLibrary();
        item.setId(id);
        item.setTemplate(text);
        item.setMatchType(InspectLibrary.TYPE_TEMPLATE);
        item.setBusinessExpected(0);
        return item;
    }

    @Test
    public void 单条标记业务预期时关闭该模版下的未处理事件() {
        Mockito.when(libraryMapper.updateBusinessExpected(
                ArgumentMatchers.eq(33L), ArgumentMatchers.eq(1),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString())).thenReturn(1);
        Mockito.when(libraryMapper.selectById(33L))
                .thenReturn(tpl(33L, "[渠道订单补单]补单处理异常，orderId: {订单ID} {异常类}: 补单处理失败"));

        boolean ok = service.markBusinessExpected(33L, "补单失败属于业务状态", "alice");

        Assert.assertTrue(ok);
        Mockito.verify(eventMapper).closeByTemplateId(
                ArgumentMatchers.eq(33L),
                ArgumentMatchers.contains("业务预期"),
                ArgumentMatchers.eq("alice"));
    }

    @Test
    public void 标记失败时不动事件() {
        // 模版不存在 / 已经是业务预期，更新影响 0 行 —— 不该顺手关掉别人的事件
        Mockito.when(libraryMapper.updateBusinessExpected(
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyInt(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(0);

        Assert.assertFalse(service.markBusinessExpected(999L, "x", "alice"));
        Mockito.verify(eventMapper, Mockito.never()).closeByTemplateId(
                ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    public void 批量基线归零时逐条关闭事件() {
        List<InspectLibrary> pending = new ArrayList<>(Arrays.asList(
                tpl(1L, "商品已下架"),
                tpl(2L, "券码不存在"),
                // 技术故障不该被放行，也就不该关它的事件
                tpl(3L, "java.lang.NullPointerException: null")));
        Mockito.when(libraryMapper.selectList(ArgumentMatchers.eq(0), ArgumentMatchers.any()))
                .thenReturn(pending);

        service.markAllExpected("系统平稳期基线归零", "alice");

        Mockito.verify(eventMapper).closeByTemplateId(
                ArgumentMatchers.eq(1L), ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.verify(eventMapper).closeByTemplateId(
                ArgumentMatchers.eq(2L), ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.verify(eventMapper, Mockito.never()).closeByTemplateId(
                ArgumentMatchers.eq(3L), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    public void 关闭事件失败不影响标记本身() {
        // 事件表写失败不该让标记回滚 —— 标记是主操作，关事件是附带的，
        // 而且有兜底清扫会在下一轮补上
        Mockito.when(libraryMapper.updateBusinessExpected(
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyInt(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(1);
        Mockito.when(libraryMapper.selectById(ArgumentMatchers.anyLong()))
                .thenReturn(tpl(7L, "商品已下架"));
        Mockito.doThrow(new RuntimeException("db down"))
                .when(eventMapper).closeByTemplateId(
                        ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.any());

        Assert.assertTrue("标记必须成功", service.markBusinessExpected(7L, "x", "alice"));
    }
}
