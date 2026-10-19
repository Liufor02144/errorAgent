package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.config.InspectProperties;
import io.github.liufor02144.erroragent.config.InspectSwitch;
import io.github.liufor02144.erroragent.dto.CollectResult;
import io.github.liufor02144.erroragent.dto.EventAggregate;
import io.github.liufor02144.erroragent.dto.FingerprintRow;
import io.github.liufor02144.erroragent.entity.InspectFingerprint;
import io.github.liufor02144.erroragent.entity.InspectLibrary;
import io.github.liufor02144.erroragent.mapper.InspectFingerprintMapper;
import io.github.liufor02144.erroragent.mapper.InspectSampleMapper;
import io.github.liufor02144.erroragent.mapper.InspectStatMapper;
import io.github.liufor02144.erroragent.service.InspectConfigService;
import io.github.liufor02144.erroragent.service.collect.InspectCollectService;
import io.github.liufor02144.erroragent.service.collect.InspectSlsRepository;
import io.github.liufor02144.erroragent.service.judge.InspectEventService;
import io.github.liufor02144.erroragent.service.judge.InspectJudgeService;
import io.github.liufor02144.erroragent.service.judge.InspectLibraryService;
import io.github.liufor02144.erroragent.service.template.TemplateExtractor;
import io.github.liufor02144.erroragent.service.template.TemplateFallbackService;
import io.github.liufor02144.erroragent.service.template.TemplateMatcher;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集 → 模版匹配 → 判定 → 事件 的整链验证。
 *
 * <p>数据仿照生产日志的形态构造。要挡住的两类回归：
 * <ul>
 *   <li><b>跨服务裂开</b> —— order 和 product 的同一个 Redis 超时必须是一个事件</li>
 *   <li><b>业务预期被误报</b> —— 标记过的模版正常抛出不该产生事件，但突增要报</li>
 * </ul>
 */
public class CollectToEventFlowTest {

    private InspectCollectService collectService;
    private InspectEventService eventService;
    private TemplateMatcher templateMatcher;
    private InspectFingerprintMapper fingerprintMapper;

    /** fp_key → 命中的模版，由各用例装配 */
    private final Map<String, InspectLibrary> templateByFpKey = new HashMap<>();

    @Before
    public void setUp() {
        collectService = new InspectCollectService();

        InspectSwitch sw = new InspectSwitch();
        InspectProperties props = new InspectProperties();
        InspectProperties.Sls sls = props.getSls();
        sls.setEndpoint("cn-hangzhou.log.aliyuncs.com");
        sls.setProject("demo-log-project");
        sls.setLogstore("prod-java-error-log");
        sls.setAccessKeyId("ak");
        sls.setAccessKeySecret("sk");

        slsRepository = Mockito.mock(InspectSlsRepository.class);
        Mockito.when(slsRepository.queryFingerprints(
                        ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyInt()))
                .thenReturn(productionRows());

        fingerprintMapper = Mockito.mock(InspectFingerprintMapper.class);
        Mockito.when(fingerprintMapper.selectByHashes(ArgumentMatchers.anyList()))
                .thenAnswer(inv -> existingFingerprints(inv.getArgument(0)));
        // 默认库里已有历史指纹，不是首次部署
        Mockito.when(fingerprintMapper.selectAnyId()).thenReturn(1L);

        InspectStatMapper statMapper = Mockito.mock(InspectStatMapper.class);
        InspectSampleMapper sampleMapper = Mockito.mock(InspectSampleMapper.class);

        InspectConfigService config = Mockito.mock(InspectConfigService.class);
        Mockito.when(config.getInt(ArgumentMatchers.anyString(), ArgumentMatchers.anyInt()))
                .thenAnswer(i -> i.getArgument(1));
        Mockito.when(config.getBool(ArgumentMatchers.anyString(), ArgumentMatchers.anyBoolean()))
                .thenAnswer(i -> i.getArgument(1));

        // 匹配结果按 fp_key 装配：真实匹配逻辑由 TemplateMatcher 自己的用例覆盖，
        // 这里关心的是「匹配到同一模版的指纹会不会合成一个事件」
        templateMatcher = Mockito.mock(TemplateMatcher.class);
        Mockito.when(templateMatcher.matchAll(ArgumentMatchers.anyList(), ArgumentMatchers.anyMap()))
                .thenAnswer(inv -> {
                    List<InspectFingerprint> fps = inv.getArgument(0);
                    Map<Long, InspectLibrary> out = new HashMap<>();
                    for (InspectFingerprint fp : fps) {
                        InspectLibrary hit = templateByFpKey.get(fp.getFpKey());
                        if (hit != null) {
                            out.put(fp.getId(), hit);
                        }
                    }
                    return out;
                });

        InspectJudgeService judgeService = new InspectJudgeService();
        ReflectionTestUtils.setField(judgeService, "statMapper", statMapper);
        ReflectionTestUtils.setField(judgeService, "configService", config);

        eventService = Mockito.mock(InspectEventService.class);
        InspectLibraryService libraryService = Mockito.mock(InspectLibraryService.class);
        TemplateExtractor extractor = Mockito.mock(TemplateExtractor.class);

        ReflectionTestUtils.setField(collectService, "inspectSwitch", sw);
        ReflectionTestUtils.setField(collectService, "properties", props);
        ReflectionTestUtils.setField(collectService, "slsRepository", slsRepository);
        ReflectionTestUtils.setField(collectService, "fingerprintMapper", fingerprintMapper);
        ReflectionTestUtils.setField(collectService, "statMapper", statMapper);
        ReflectionTestUtils.setField(collectService, "sampleMapper", sampleMapper);
        ReflectionTestUtils.setField(collectService, "judgeService", judgeService);
        ReflectionTestUtils.setField(collectService, "libraryService", libraryService);
        ReflectionTestUtils.setField(collectService, "eventService", eventService);
        ReflectionTestUtils.setField(collectService, "configService", config);
        ReflectionTestUtils.setField(collectService, "templateMatcher", templateMatcher);
        // 事件必须有模版归属：未匹配时走兜底，这里用归一文本合成一条，
        // 和线上 TemplateFallbackService 的行为一致
        TemplateFallbackService fallback = Mockito.mock(TemplateFallbackService.class);
        Mockito.when(fallback.ensureTemplate(ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    InspectFingerprint fp = inv.getArgument(0);
                    String text = fp.getMsgTpl() != null ? fp.getMsgTpl() : fp.getExcSimple();
                    if (text == null || text.trim().isEmpty()) {
                        return null;
                    }
                    InspectLibrary auto = new InspectLibrary();
                    // 同一文本必须给同一个 id，否则兜底出来的也会各自成事件
                    auto.setId(1000L + Math.abs(text.hashCode() % 1000));
                    auto.setMatchType(InspectLibrary.TYPE_TEMPLATE);
                    auto.setTemplate(text);
                    auto.setMatchValue(text);
                    auto.setBusinessExpected(0);
                    auto.setOperator("auto");
                    return auto;
                });
        ReflectionTestUtils.setField(collectService, "templateFallback", fallback);
        ReflectionTestUtils.setField(collectService, "templateExtractor", extractor);
        this.extractor = extractor;
        this.fallback = fallback;
        this.inspectSwitch = sw;

        runRecorder = Mockito.mock(io.github.liufor02144.erroragent.service.run.InspectRunRecorder.class);
        Mockito.when(runRecorder.start(ArgumentMatchers.anyString(), ArgumentMatchers.anyBoolean()))
                .thenReturn(900L);
        ReflectionTestUtils.setField(collectService, "runRecorder", runRecorder);
    }

    private io.github.liufor02144.erroragent.service.run.InspectRunRecorder runRecorder;
    private InspectSlsRepository slsRepository;
    private TemplateExtractor extractor;
    private TemplateFallbackService fallback;
    private InspectSwitch inspectSwitch;

    /**
     * 抽取失败的异常不能每轮重试。
     *
     * <p>实测：一条连兜底模版都建不出来的异常，每 5 分钟送一次抽取，
     * 每次「校验通过 0 个」、输出上万 token —— 结果永远一样，钱白花。
     * 送过一次就冷却（默认 24 小时）。
     */
    @Test
    public void 抽取失败后同一异常不会每轮重试() {
        ReflectionTestUtils.setField(inspectSwitch, "enabled", true);
        ReflectionTestUtils.setField(inspectSwitch, "llmEnabled", true);
        // 兜底模版建不出来 —— 这类异常才会一直留在「未匹配」里
        Mockito.doReturn(null).when(fallback).ensureTemplate(ArgumentMatchers.any());
        Mockito.when(extractor.extract(ArgumentMatchers.anyList(), ArgumentMatchers.anyList(),
                ArgumentMatchers.anyInt())).thenReturn(null);

        collectService.collectOnce(true);
        collectService.collectOnce(true);
        collectService.collectOnce(true);

        Mockito.verify(extractor, Mockito.times(1)).extract(ArgumentMatchers.anyList(),
                ArgumentMatchers.anyList(), ArgumentMatchers.anyInt());
    }

    /**
     * 每轮采集都要留一条巡检记录，开头开、结尾回填 ——
     * 采集失败也一样，「这轮为什么没报」得有地方看。
     */
    @Test
    public void 每轮采集都留巡检记录() {
        CollectResult r = collectService.collectOnce(true);
        Assert.assertEquals(Long.valueOf(900L), r.getRunId());
        Mockito.verify(runRecorder).start(r.getBatchId(), true);
        Mockito.verify(runRecorder).finish(900L, r);
    }

    @Test
    public void SLS失败也要回填巡检记录() {
        Mockito.when(slsRepository.queryFingerprints(
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("SLS 查询超时"));
        CollectResult r = collectService.collectOnce(true);
        Assert.assertFalse(r.isSuccess());
        Mockito.verify(runRecorder).finish(900L, r);
    }

    @Test
    public void 跨服务的同一模版合并成一个事件() {
        // order 和 product 各自抛 Redis 超时 —— 是一件事，不是两件
        InspectLibrary redis = template("Redis 连接超时", "Redis.{0,20}(连接超时|timeout)", false);
        templateByFpKey.put(REDIS_ORDER, redis);
        templateByFpKey.put(REDIS_PRODUCT, redis);

        collectService.collectOnce(true);

        List<EventAggregate> events = capturedEvents();
        EventAggregate merged = findByHeadline(events, "Redis 连接超时");
        Assert.assertNotNull("跨服务的同一模版必须合成一个事件", merged);
        Assert.assertEquals("两个服务都要记进事件", 2, merged.appCount());
        Assert.assertTrue(merged.appList().contains("order-service"));
        Assert.assertTrue(merged.appList().contains("product-service"));
        // 聚合掉的是身份不是信息：代码位置要留着
        Assert.assertEquals(2, merged.frameCount());
    }

    @Test
    public void 已标记业务预期的模版正常抛出不产生事件() {
        templateByFpKey.put(PRODUCT_OFF_SHELF,
                template("商品已下架", "商品.{0,20}已下架", true));

        collectService.collectOnce(true);

        Assert.assertNull("标记过业务预期的不该产生事件",
                findByHeadline(capturedEvents(), "商品已下架"));
    }

    @Test
    public void 未标记业务预期的模版照样产生事件() {
        // 在模版库里 != 允许发生。只有标记过的才不报
        templateByFpKey.put(PRODUCT_OFF_SHELF,
                template("商品已下架", "商品.{0,20}已下架", false));

        collectService.collectOnce(true);

        Assert.assertNotNull(findByHeadline(capturedEvents(), "商品已下架"));
    }

    @Test
    public void 没匹配到模版的指纹先兜一个模版再报() {
        // 首次出现的异常这一轮可能还没模版，不能因此静默；
        // 但也不能落成「无归属」事件 —— 那样同一异常会因为一条走 msg_tpl、
        // 一条走 exc_cause 而被拆成两条，且系统里没有可标注、可复核的载体
        CollectResult r = collectService.collectOnce(true);

        // 断言实际落下来的聚合，而不是 CollectResult 的计数 ——
        // eventService 是 mock，record 返回 null 时计数不会增
        List<EventAggregate> events = capturedEvents();
        Assert.assertFalse("未匹配的也要成事件", events.isEmpty());
        for (EventAggregate e : events) {
            Assert.assertNotNull("每个事件都必须有模版归属：" + e.headline(), e.templateId());
        }
        Assert.assertEquals("本轮一个都没匹配上", 0, r.getMatchedCount());
        Assert.assertEquals(ROWS, r.getUnmatchedCount());
    }

    @Test
    public void 指标型日志跨服务合并后保留服务数() {
        collectService.collectOnce(true);

        EventAggregate metric = findByHeadline(capturedEvents(), "CACHE_METRIC_ABNORMAL");
        Assert.assertNotNull(metric);
        // 指标型日志在 SLS 侧就已跨服务合并，它自带的服务数要带上来
        Assert.assertEquals(25, metric.appCount());
    }

    /**
     * 不同代码位置的空指针必须各自成事件。
     *
     * <p>实测事故：报表任务导出用户余额明细时空指针（出现即客诉），
     * 模版只有「NullPointerException」一个词，被并进了上午网关的空指针事件，
     * 跟着旧事件 4 小时后才重推，一次都没推出来。
     */
    @Test
    public void 不同位置的空指针各自成事件() {
        InspectLibrary npe = template("NullPointerException", "NullPointerException", false);
        String dc = "report-task | NullPointerException | BalanceExportModel.java:154 | null";
        String gw = "api-gateway | NullPointerException | AuthFilter.java:88 | null";
        templateByFpKey.put(dc, npe);
        templateByFpKey.put(gw, npe);
        Mockito.when(slsRepository.queryFingerprints(ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyInt())).thenReturn(Arrays.asList(
                row(dc, 2, "report-task", 1, null, "NullPointerException",
                        "BalanceExportModel.java:154", null),
                row(gw, 1, "api-gateway", 1, null, "NullPointerException",
                        "AuthFilter.java:88", null)));

        collectService.collectOnce(true);

        List<EventAggregate> events = capturedEvents();
        Assert.assertEquals("两个位置的空指针是两个 bug，不能合成一个事件", 2, events.size());
        for (EventAggregate e : events) {
            Assert.assertTrue("代码缺陷按位置成事件", e.isPerLocation());
            Assert.assertEquals(1, e.frameCount());
        }
    }

    @Test
    public void 基础设施问题仍然跨服务合并() {
        // 同一个模版下的 Redis 超时，order 和 product 是一件事
        InspectLibrary redis = template("Redis 连接超时", "Redis.{0,20}(连接超时|timeout)", false);
        templateByFpKey.put(REDIS_ORDER, redis);
        templateByFpKey.put(REDIS_PRODUCT, redis);
        collectService.collectOnce(true);
        EventAggregate merged = findByHeadline(capturedEvents(), "Redis 连接超时");
        Assert.assertNotNull(merged);
        Assert.assertFalse(merged.isPerLocation());
        Assert.assertEquals(2, merged.appCount());
    }

    /**
     * 系统平时安静、一轮里冒出的全是从没见过的异常：库里有历史指纹，就不是冷启动，必须产生事件。
     *
     * <p>原先按「本轮快照为空」判冷启动，这一轮会被整轮吞掉。
     */
    @Test
    public void 本轮全是新指纹但库里有历史时仍产生事件() {
        // 第一次是 upsert 前的快照（全是新指纹，查不到），之后是 upsert 后取主键
        Mockito.when(fingerprintMapper.selectByHashes(ArgumentMatchers.anyList()))
                .thenReturn(new ArrayList<>())
                .thenAnswer(inv -> existingFingerprints(inv.getArgument(0)));
        collectService.collectOnce(true);
        Assert.assertFalse("不是首次部署，新异常必须成为事件", capturedEvents().isEmpty());
    }

    @Test
    public void 首次部署库里没有任何指纹时不产生事件() {
        Mockito.when(fingerprintMapper.selectByHashes(ArgumentMatchers.anyList()))
                .thenReturn(new ArrayList<>())
                .thenAnswer(inv -> existingFingerprints(inv.getArgument(0)));
        Mockito.when(fingerprintMapper.selectAnyId()).thenReturn(null);
        collectService.collectOnce(true);
        Assert.assertTrue("首轮只建基线，不报存量异常", capturedEvents().isEmpty());
    }

    // ---------------- fixtures ----------------

    private static final String REDIS_ORDER =
            "order-service | RedisConnectionException | RedisClient.java:88 | Redis 连接超时";
    private static final String REDIS_PRODUCT =
            "product-service | RedisConnectionException | CacheHelper.java:42 | Redis 连接超时";
    private static final String PRODUCT_OFF_SHELF =
            "order-task | BusinessException | ProductHelper.java:377 | 商品已下架";
    private static final int ROWS = 4;

    private List<EventAggregate> capturedEvents() {
        ArgumentCaptor<EventAggregate> captor = ArgumentCaptor.forClass(EventAggregate.class);
        Mockito.verify(eventService, Mockito.atLeast(0)).record(captor.capture());
        return captor.getAllValues();
    }

    private EventAggregate findByHeadline(List<EventAggregate> events, String headline) {
        for (EventAggregate e : events) {
            if (e.headline() != null && e.headline().contains(headline)) {
                return e;
            }
        }
        return null;
    }

    private InspectLibrary template(String text, String pattern, boolean businessExpected) {
        InspectLibrary item = new InspectLibrary();
        item.setId((long) (templateByFpKey.size() + 1));
        item.setMatchType(InspectLibrary.TYPE_TEMPLATE);
        item.setMatchValue(text);
        item.setTemplate(text);
        item.setPattern(pattern);
        item.setBusinessExpected(businessExpected ? 1 : 0);
        return item;
    }

    private List<InspectFingerprint> existingFingerprints(List<String> hashes) {
        List<InspectFingerprint> list = new ArrayList<>();
        for (String h : hashes) {
            InspectFingerprint fp = new InspectFingerprint();
            fp.setId((long) (list.size() + 1));
            fp.setFpHash(h);
            fp.setLastSeenTime(new java.util.Date());
            list.add(fp);
        }
        return list;
    }

    private List<FingerprintRow> productionRows() {
        return Arrays.asList(
                row(REDIS_ORDER, 120, "order-service", 1, null,
                        "RedisConnectionException", "RedisClient.java:88", "Redis 连接超时"),
                row(REDIS_PRODUCT, 80, "product-service", 1, null,
                        "RedisConnectionException", "CacheHelper.java:42", "Redis 连接超时"),
                row(PRODUCT_OFF_SHELF, 27475, "order-task", 1, null,
                        "BusinessException", "ProductHelper.java:377", "商品已下架"),
                row("CACHE_METRIC_ABNORMAL", 3980, "report-listener", 25,
                        "c.e.c.LocalRedisCacheService", null, "NO_STACK", null));
    }

    private FingerprintRow row(String fpKey, long cnt, String appId, int appCount,
                               String loggerClass, String excSimple, String bizFrame,
                               String excCause) {
        long nowSec = System.currentTimeMillis() / 1000L;
        FingerprintRow r = new FingerprintRow();
        r.setFpKey(fpKey);
        r.setCnt(cnt);
        r.setHourSec(nowSec - nowSec % 3600);
        r.setLastTsSec(nowSec);
        r.setAppId(appId);
        r.setAppCount(appCount);
        r.setLoggerClass(loggerClass);
        r.setExcSimple(excSimple);
        r.setBizFrame(bizFrame);
        r.setMsgTpl(fpKey);
        r.setExcCause(excCause);
        return r;
    }
}
