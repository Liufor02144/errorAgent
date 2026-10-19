package io.github.liufor02144.erroragent;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.github.liufor02144.erroragent.entity.InspectAnalysis;
import io.github.liufor02144.erroragent.entity.InspectEvent;
import io.github.liufor02144.erroragent.entity.InspectSample;
import io.github.liufor02144.erroragent.service.InspectConfigService;
import io.github.liufor02144.erroragent.service.push.InspectCardBuilder;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

/**
 * 事件卡片构造的验证。
 *
 * <p>卡片出错不会抛异常——飞书只会返回一个「参数非法」，或者更糟：
 * 渲染出来少了半截内容而没人察觉。所以结构性约束要在这里锁住。
 */
public class InspectCardTest {

    private InspectCardBuilder builder;

    @Before
    public void setUp() {
        builder = withDetailUrl("https://erroragent.example.com");
    }

    /** 造一个把访问地址配成 baseUrl 的构造器；传 null 表示未配置 */
    private InspectCardBuilder withDetailUrl(String baseUrl) {
        InspectCardBuilder b = new InspectCardBuilder();

        InspectConfigService config = Mockito.mock(InspectConfigService.class);
        Mockito.when(config.getString(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenAnswer(inv -> baseUrl == null ? inv.getArgument(1) : baseUrl);
        ReflectionTestUtils.setField(b, "configService", config);

        io.github.liufor02144.erroragent.config.InspectProperties props =
                new io.github.liufor02144.erroragent.config.InspectProperties();
        props.getPush().setDetailBaseUrl(baseUrl);
        ReflectionTestUtils.setField(b, "properties", props);
        return b;
    }

    @Test
    public void 占位符不得出现在标题上() {
        // 模版是给机器匹配用的，{NUM}、^_^ 这类占位和分隔符直接显示会像乱码
        InspectEvent event = event();
        event.setHeadline("ErrorScene^_^{NUM}^_^PAY_TIMEOUT^_^{TIME}");

        String json = builder.buildEvent(event, analysis(), sample());
        String title = JSON.parseObject(json).getJSONObject("header")
                .getJSONObject("title").getString("content");
        Assert.assertFalse(title, title.contains("{NUM}"));
        Assert.assertFalse(title, title.contains("^_^"));
        Assert.assertTrue(title, title.contains("PAY_TIMEOUT"));
    }

    @Test
    public void 卡片必须包含代码片段与原始报错() {
        String json = builder.buildEvent(event(), analysis(), sample());

        Assert.assertTrue("应包含代码片段", json.contains("代码片段"));
        Assert.assertTrue("应包含代码内容", json.contains("throw new IllegalStateException"));
        Assert.assertTrue("应包含原始报错", json.contains("原始报错"));
        Assert.assertTrue("应包含原始堆栈", json.contains("OrderService.cancel"));
    }

    @Test
    public void 卡片应展示累计条数与持续时长() {
        String json = builder.buildEvent(event(), analysis(), sample());
        Assert.assertTrue(json.contains("累计条数"));
        Assert.assertTrue(json.contains("已持续"));
    }

    @Test
    public void 同一请求的其他报错应单独成面板() {
        InspectAnalysis a = analysis();
        a.setTraceChain("同一次请求里的其他报错：\n[pay-service] 下游返回：余额不足\n");

        String json = builder.buildEvent(event(), a, sample());
        Assert.assertTrue(json.contains("同一请求的其他报错"));
        Assert.assertTrue(json.contains("余额不足"));
    }

    @Test
    public void 样本带traceId时应展示链路追踪行() {
        InspectSample s = sample();
        s.setTraceId("TID:abc123");
        Assert.assertTrue(builder.buildEvent(event(), analysis(), s).contains("链路追踪"));

        s.setTraceId("TID:N/A");
        Assert.assertFalse("占位值不展示", builder.buildEvent(event(), analysis(), s).contains("链路追踪"));
    }

    @Test
    public void 卡片不得出现任何处置建议() {
        String json = builder.buildEvent(event(), analysis(), sample());
        // 卡片里出现「建议」会让人以为系统在指导决策，而模型给的建议可能是错的
        Assert.assertFalse(json.contains("建议"));
        Assert.assertFalse(json.contains("应该改"));
        Assert.assertFalse(json.contains("回滚"));
    }

    @Test
    public void 重推时应亮出升级横幅() {
        InspectEvent event = event();
        event.setPushCount(2);
        Assert.assertTrue(builder.buildEvent(event, analysis(), sample()).contains("第 3 次提醒"));
    }

    // ---------------- 结构与健壮性 ----------------

    @Test
    public void 卡片应为合法的2点0结构() {
        JSONObject card = JSON.parseObject(builder.buildEvent(event(), analysis(), sample()));
        Assert.assertEquals("2.0", card.getString("schema"));
        Assert.assertNotNull(card.getJSONObject("header"));
        Assert.assertNotNull(card.getJSONObject("body"));
        Assert.assertFalse(card.getJSONObject("body").getJSONArray("elements").isEmpty());
    }

    @Test
    public void 按钮必须是跳转而不是回调() {
        // 服务没有公网入口，回调按钮点了没有任何反应；
        // 跳转是在使用者自己的浏览器里打开内网页面，不需要对外暴露
        JSONObject card = JSON.parseObject(builder.buildEvent(event(), analysis(), sample()));
        JSONArray actions = findActions(card);
        Assert.assertNotNull("应有交互区", actions);
        Assert.assertEquals(1, actions.size());

        JSONObject behavior = actions.getJSONObject(0).getJSONArray("behaviors").getJSONObject(0);
        Assert.assertEquals("open_url", behavior.getString("type"));
        // 管理页面是单页应用，靠 module/eventId 参数定位
        Assert.assertEquals("https://erroragent.example.com/#/?module=InspectDashboard&eventId=7",
                behavior.getString("default_url"));
        Assert.assertFalse("不应残留任何回调按钮", card.toJSONString().contains("\"callback\""));
    }

    @Test
    public void 未配置访问地址时应明说而不是给个点不动的按钮() {
        String json = withDetailUrl(null).buildEvent(event(), analysis(), sample());
        Assert.assertTrue("应提示缺少的配置项", json.contains("erroragent.push.detail-base-url"));
        Assert.assertFalse("不应出现跳转按钮", json.contains("open_url"));
    }

    @Test
    public void 访问地址结尾多个斜杠应被规整() {
        String json = withDetailUrl("https://erroragent.example.com///")
                .buildEvent(event(), analysis(), sample());
        Assert.assertTrue(json.contains("https://erroragent.example.com/#/?module=InspectDashboard&eventId=7"));
    }

    @Test
    public void 日志中的反引号不得破坏代码块结构() {
        // 日志里出现 ``` 会提前闭合代码块，后面的内容会变成正文渲染
        InspectSample evil = new InspectSample();
        evil.setRawMessage("异常信息 ```\n这段如果没被中和就会逃出代码块");

        String json = builder.buildEvent(event(), analysis(), evil);
        Assert.assertNotNull(JSON.parseObject(json));
        Assert.assertTrue("三连反引号应被中和", json.contains("` ` `"));
    }

    @Test
    public void 缺少分析结果时仍应产出卡片() {
        // AI 调用失败不该导致「什么都收不到」——事实部分本身就有价值
        String json = builder.buildEvent(event(), null, sample());
        JSONObject card = JSON.parseObject(json);
        Assert.assertNotNull(card);
        Assert.assertTrue(json.contains("未分析"));
        Assert.assertNotNull("无分析时仍要能跳转标注", findActions(card));
    }

    @Test
    public void 标注后应移除按钮并保留原有内容() {
        String original = builder.buildEvent(event(), analysis(), sample());
        String handled = builder.buildHandled(original, "确认是缺陷", "张三");

        Assert.assertNotNull(handled);
        JSONObject card = JSON.parseObject(handled);
        Assert.assertNull("按钮应被移除，避免重复标注", findActions(card));
        Assert.assertTrue("应显示标注结论", handled.contains("确认是缺陷"));
        Assert.assertTrue("应显示操作人", handled.contains("张三"));
        Assert.assertTrue("原有证据应保留", handled.contains("代码片段"));
    }

    @Test
    public void 原卡片不可解析时应返回null而不是抛异常() {
        Assert.assertNull(builder.buildHandled("不是JSON", "确认是缺陷", "张三"));
        Assert.assertNull(builder.buildHandled("{}", "确认是缺陷", "张三"));
    }

    // ---------------- fixtures ----------------

    /**
     * 取交互区里的按钮。
     *
     * <p>卡片 2.0 没有 action 容器，按钮装在带 element_id 标记的 column_set 里，
     * 每列一个按钮。
     */
    private JSONArray findActions(JSONObject card) {
        JSONArray elements = card.getJSONObject("body").getJSONArray("elements");
        for (int i = 0; i < elements.size(); i++) {
            JSONObject el = elements.getJSONObject(i);
            if (!"inspect_actions".equals(el.getString("element_id"))) {
                continue;
            }
            JSONArray columns = el.getJSONArray("columns");
            if (columns == null) {
                // 未配置访问地址时交互区退化成一行提示文字
                return null;
            }
            JSONArray buttons = new JSONArray();
            for (int j = 0; j < columns.size(); j++) {
                buttons.add(columns.getJSONObject(j).getJSONArray("elements").getJSONObject(0));
            }
            return buttons;
        }
        return null;
    }

    private InspectEvent event() {
        InspectEvent e = new InspectEvent();
        e.setId(7L);
        e.setFpId(1L);
        e.setEventType(InspectEvent.TYPE_NEW);
        e.setAppId("order-service");
        e.setAppCount(1);
        e.setHeadline("订单取消失败：状态不允许");
        e.setExcSimple("IllegalStateException");
        e.setExcCause("订单取消失败：状态不允许");
        e.setSeverity("P1");
        e.setTotalCount(42L);
        e.setPushCount(0);
        e.setFirstSeenTime(new Date(System.currentTimeMillis() - 3 * 3600_000L));
        e.setLastSeenTime(new Date());
        return e;
    }

    private InspectAnalysis analysis() {
        InspectAnalysis a = new InspectAnalysis();
        a.setFpId(1L);
        a.setCategory("SUSPECTED_BUG");
        a.setSeverity("P1");
        a.setIsFirstSeen(true);
        a.setRootCause("OrderService.cancel 第 57 行在订单已发货时主动抛出");
        a.setBusinessImpact("已发货订单无法取消，用户侧表现为取消按钮报错");
        a.setConfidence("HIGH");
        a.setLocateConfidence("HIGH");
        a.setCodeRef("order-service/src/main/java/com/example/order/OrderService.java:57");
        a.setCodeSnippet("→    57 |     throw new IllegalStateException(\"订单取消失败：状态不允许\");");
        return a;
    }

    private InspectSample sample() {
        InspectSample s = new InspectSample();
        s.setRawMessage("java.lang.IllegalStateException: 订单取消失败：状态不允许\n"
                + "\tat com.example.order.OrderService.cancel(OrderService.java:57)");
        return s;
    }
}
