package io.github.liufor02144.erroragent.service.push;

import io.github.liufor02144.erroragent.dto.PushResult;
import io.github.liufor02144.erroragent.config.InspectProperties;
import io.github.liufor02144.erroragent.config.InspectSwitch;
import io.github.liufor02144.erroragent.entity.InspectEvent;
import io.github.liufor02144.erroragent.entity.InspectAnalysis;
import io.github.liufor02144.erroragent.service.InspectText;
import io.github.liufor02144.erroragent.mapper.InspectAnalysisMapper;
import io.github.liufor02144.erroragent.mapper.InspectEventMapper;
import io.github.liufor02144.erroragent.mapper.InspectSampleMapper;
import io.github.liufor02144.erroragent.service.InspectConfigKeys;
import io.github.liufor02144.erroragent.service.InspectConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;

/**
 * 异常事件推送，含<b>升级重推</b>。
 *
 * <p>这里刻意不做「推过就不再推」的去重 —— 那会让一个持续一整天的 Redis 超时
 * 在第一次之后彻底沉默。取而代之的是按 1h → 2h → 4h → 8h → 24h 递增间隔重推，
 * 每次都带上「第 N 次提醒、已持续 X」：问题拖得越久，卡片越刺眼。
 * 人一旦在页面上标注（表示已在处理），重推立即停止。
 *
 * <p>重推<b>不计入当日推送上限</b>：它是同一件事的升级，不是新噪音。
 * 把它算进上限会出现「P0 故障的第 3 次提醒把额度耗光，当天真正的新异常反而发不出去」。
 */
@Slf4j
@Service
public class InspectEventPushService {

    /** 配置缺失时的兜底重推间隔 */
    private static final long[] DEFAULT_INTERVALS = {1, 2, 4, 8, 24};

    @Resource
    private InspectSwitch inspectSwitch;

    @Resource
    private InspectConfigService configService;

    @Resource
    private InspectEventMapper eventMapper;

    @Resource
    private InspectAnalysisMapper analysisMapper;

    @Resource
    private InspectSampleMapper sampleMapper;

    @Resource
    private InspectCardBuilder cardBuilder;

    @Resource
    private FeishuApiClient apiClient;

    @Resource
    private InspectProperties properties;

    @Resource
    private SameRequestMerger sameRequestMerger;

    @Resource
    private io.github.liufor02144.erroragent.service.run.InspectRunRecorder runRecorder;

    /**
     * 扫描并推送待推事件。
     *
     * @return 实际推送条数
     */
    public int scanAndPush() {
        String blocked = inspectSwitch.whyBlocked("PUSH");
        if (blocked != null) {
            log.info("【事件推送】已关闭：{}", blocked);
            return 0;
        }

        // 不再设推送门槛：异常库已经过滤过一轮，剩下的新增/突增都该让人知道。
        // 节流由「同一事件的递增重推间隔」承担，而不是「量小就不报」
        List<String> severities = configService.getList(InspectConfigKeys.PUSH_SEVERITY_INCLUDE,
                InspectConfigKeys.PUSH_SEVERITY_DEFAULT);
        List<InspectEvent> candidates = eventMapper.selectPushable(0L, severities);
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }

        int dailyLimit = configService.getInt(InspectConfigKeys.PUSH_DAILY_LIMIT, 10);
        long firstPushedToday = countFirstPushedToday(candidates);

        // 单轮推送上限。升级重推单条看都合理，但一批事件同时到达重推点时
        // 会集中涌出 —— 实测开关刚打开那轮一次推了 22 张卡，全是重推。
        // 超出的不丢，下一轮继续（间隔已过的事件下轮仍然满足条件）
        int perRoundLimit = configService.getInt(
                InspectConfigKeys.PUSH_MAX_PER_ROUND, 5);

        // 一、过闸门：到了推送时机、定级够、AI 没判业务预期
        List<InspectEvent> eligible = new ArrayList<>();
        for (InspectEvent event : candidates) {
            if (!shouldPush(event)) {
                continue;
            }
            // AI 判为业务预期的不推。指纹推送早就有这道闸门，事件推送漏了 ——
            // 实测一轮里 3 张卡是 BIZ_EXPECTED，纯属稀释注意力
            String skip = severityGate(event, severities);
            if (skip == null) {
                skip = aiGate(event);
            }
            if (skip != null) {
                log.debug("【事件推送】跳过 #{}：{}", event.getId(), skip);
                runRecorder.pushSkipped(event, skip);
                continue;
            }
            eligible.add(event);
        }

        // 二、同一批请求合并成一张卡片（见 SameRequestMerger）。
        // 排在前面的当主卡片：定级高的优先，其次是已经推过的（保持同一张卡片持续提醒），再其次先出现的
        eligible.sort(PUSH_PRIORITY);
        Map<Long, Set<String>> traceCache = new HashMap<>();
        List<PushGroup> groups = new ArrayList<>();
        List<InspectEvent> pushedBefore = null;
        for (InspectEvent event : eligible) {
            Set<String> traces = sameRequestMerger.tracesOf(event, traceCache);
            PushGroup joined = null;
            for (PushGroup g : groups) {
                if (SameRequestMerger.sameRequests(g.traces, traces)) {
                    joined = g;
                    break;
                }
            }
            if (joined != null) {
                joined.members.add(event);
                continue;
            }
            // 和之前已经推过、还没处理的事件是同一批请求：不再另起一张
            InspectEvent twin = null;
            if (!traces.isEmpty()) {
                if (pushedBefore == null) {
                    pushedBefore = eventMapper.selectRecentlyPushedOpen(
                            new Date(System.currentTimeMillis() - 3600_000L));
                }
                for (InspectEvent p : pushedBefore) {
                    if (!p.getId().equals(event.getId()) && SameRequestMerger.sameRequests(
                            sameRequestMerger.tracesOf(p, traceCache), traces)) {
                        twin = p;
                        break;
                    }
                }
            }
            if (twin != null) {
                markMergedInto(event, twin, "之前推过的那张卡片");
                continue;
            }
            groups.add(new PushGroup(event, traces));
        }

        // 三、每组推一张
        int pushed = 0;
        for (PushGroup g : groups) {
            if (pushed >= perRoundLimit) {
                log.warn("【事件推送】本轮已推 {} 条达上限，其余顺延到下一轮", pushed);
                break;
            }
            InspectEvent event = g.master;
            boolean isFirstPush = nzi(event.getPushCount()) == 0;
            // 日上限只管「新问题」，升级重推不受限
            if (isFirstPush && firstPushedToday >= dailyLimit) {
                log.error("【事件推送】当日新事件推送已达上限 {}/{}，后续新事件不再进群。"
                        + "要么线上确实在出事，要么判定阈值偏松，请立即确认", firstPushedToday, dailyLimit);
                runRecorder.pushSkipped(event, "当日新事件推送已达上限 " + dailyLimit + " 条");
                continue;
            }
            if (doPush(event, relatedLines(g))) {
                pushed++;
                if (isFirstPush) {
                    firstPushedToday++;
                }
                for (InspectEvent m : g.members) {
                    markMergedInto(m, event, "这张卡片");
                }
            }
        }
        return pushed;
    }

    /**
     * 定级闸门：<b>没有定级就不推</b>。
     *
     * <p>原来 selectPushable 的 SQL 写的是 {@code severity IS NULL OR severity IN (...)}，
     * 空定级被当成「放行」—— 实测 36 条待处理事件<b>全部未定级</b>、其中 34 条已推出去，
     * 也就是说 {@code push.severity.include = P0,P1} 这道门槛从来没生效过，
     * 群里每张卡的标题都写着「未定级」。
     *
     * <p>根因：指纹侧不产出 severity，只有 AI 分析会给。所以这里做两件事：
     * <ol>
     *   <li>从分析结论取定级，回写到事件上 —— 页面和卡片才有得显示</li>
     *   <li>取不到就不推。<b>推送意味着「需要你现在关注」，而连严重到什么程度
     *       都还说不出来时，没有资格占用别人的注意力</b>，等分析出来下一轮再说</li>
     * </ol>
     *
     * @return 跳过原因；可以推则返回 null
     */
    private String severityGate(InspectEvent event, List<String> allowed) {
        String severity = event.getSeverity();

        if (!InspectText.notBlank(severity) && event.getFpId() != null) {
            try {
                InspectAnalysis a = analysisMapper.selectLatestByFpId(event.getFpId());
                if (a != null && InspectText.notBlank(a.getSeverity())) {
                    severity = a.getSeverity();
                    eventMapper.updateSeverity(event.getId(), severity);
                    event.setSeverity(severity);
                }
            } catch (Exception e) {
                log.warn("取事件 {} 的定级失败：{}", event.getId(), e.getMessage());
            }
        }

        if (!InspectText.notBlank(severity)) {
            return "尚未定级（AI 分析未完成），等有定级再推";
        }
        if (allowed != null && !allowed.isEmpty() && !allowed.contains(severity)) {
            return "定级 " + severity + " 未达推送门槛 " + allowed;
        }
        return null;
    }

    /**
     * AI 结论闸门。
     *
     * <p>业务预期内的拒绝不是故障，推了只会稀释注意力 —— 群里每多一张
     * 「其实一切正常」的卡片，下一张真故障被认真看的概率就低一分。
     *
     * <p>分析还没做完的照推：事实部分（量级、发版关联、代码位置）本身就有价值，
     * 不能因为模型慢就把告警压住。
     *
     * @return 跳过原因；应该推则返回 null
     */
    private String aiGate(InspectEvent event) {
        // 突增不看 AI 的分类：突增的异常本来就是业务预期类的，AI 多半仍判「业务预期」，
        // 但要报的是「量不正常」，不是「这个异常是不是故障」
        if (InspectEvent.TYPE_SURGE.equals(event.getEventType())
                && !configService.getBool(InspectConfigKeys.JUDGE_SURGE_ENABLED, false)) {
            // 关掉之前已经开着的突增事件，不再推送和重推
            return "突增判定已关闭";
        }
        if (event.getFpId() == null || InspectEvent.TYPE_SURGE.equals(event.getEventType())) {
            return null;
        }
        try {
            InspectAnalysis analysis = analysisMapper.selectLatestByFpId(event.getFpId());
            if (analysis != null && "BIZ_EXPECTED".equals(analysis.getCategory())) {
                return "AI 判定为业务预期内";
            }
        } catch (Exception e) {
            // 查不到分析不该挡住推送
            log.warn("查事件 {} 的分析结论失败：{}", event.getId(), e.getMessage());
        }
        return null;
    }

    /**
     * 该不该推：首次必推，之后看距上次推送是否已超过当前档位的间隔。
     */
    /** 同一批请求的一组事件：主事件推卡片，其余并进这张卡片 */
    private static class PushGroup {
        final InspectEvent master;
        final Set<String> traces;
        final List<InspectEvent> members = new ArrayList<>();

        PushGroup(InspectEvent master, Set<String> traces) {
            this.master = master;
            this.traces = traces;
        }
    }

    /** 主卡片的优先级：定级高 → 已推过 → 先出现 → id 小 */
    private static final Comparator<InspectEvent> PUSH_PRIORITY = Comparator
            .comparing((InspectEvent e) -> e.getSeverity() == null ? "P9" : e.getSeverity())
            .thenComparing(e -> e.getPushCount() == null ? 0 : -e.getPushCount())
            .thenComparing(e -> e.getFirstSeenTime() == null ? new Date(Long.MAX_VALUE) : e.getFirstSeenTime())
            .thenComparing(InspectEvent::getId);

    /** 卡片上「同一批请求里还有」的每一行 */
    private List<String> relatedLines(PushGroup g) {
        List<String> lines = new ArrayList<>();
        for (InspectEvent m : g.members) {
            String h = m.getHeadline() == null ? "#" + m.getId() : m.getHeadline();
            lines.add((h.length() > 60 ? h.substring(0, 60) + "…" : h)
                    + "（累计 " + m.getTotalCount() + " 次）");
        }
        return lines;
    }

    /**
     * 记为已合并：推送次数照常推进，重推节奏就跟主事件一致，不会每轮都被重新比对一遍；
     * 不记消息ID，标注它时不会去改主事件的卡片。
     */
    private void markMergedInto(InspectEvent member, InspectEvent master, String where) {
        String reason = "与 #" + master.getId() + " 是同一批请求，已合并到" + where;
        runRecorder.pushSkipped(member, reason);
        eventMapper.markMerged(member.getId(), new Date());
        log.info("【事件推送】#{} {}", member.getId(), reason);
    }

    private boolean shouldPush(InspectEvent event) {
        int pushCount = nzi(event.getPushCount());
        if (pushCount == 0) {
            return true;
        }
        if (!configService.getBool(InspectConfigKeys.EVENT_REPUSH_ENABLED, true)) {
            return false;
        }
        Date last = event.getLastPushTime();
        if (last == null) {
            return true;
        }
        long intervalHours = intervalHoursFor(pushCount);
        long elapsedMs = System.currentTimeMillis() - last.getTime();
        return elapsedMs >= intervalHours * 3600_000L;
    }

    /**
     * 第 n 次推送之后要等多久。档位用完后固定用最后一档，
     * 这样长期不处理的事件会保持一天一次的提醒，而不是无限拉长到再也不响。
     */
    public long intervalHoursFor(int pushCount) {
        long[] intervals = configuredIntervals();
        int idx = Math.min(pushCount - 1, intervals.length - 1);
        return intervals[Math.max(idx, 0)];
    }

    private long[] configuredIntervals() {
        List<String> raw = configService.getList(InspectConfigKeys.EVENT_REPUSH_HOURS);
        if (raw == null || raw.isEmpty()) {
            return DEFAULT_INTERVALS;
        }
        List<Long> parsed = new ArrayList<>(raw.size());
        for (String s : raw) {
            try {
                long v = Long.parseLong(s.trim());
                if (v > 0) {
                    parsed.add(v);
                }
            } catch (NumberFormatException ignored) {
                // 配置里混进非数字不该让整条推送链路瘫掉，跳过即可
            }
        }
        if (parsed.isEmpty()) {
            return DEFAULT_INTERVALS;
        }
        long[] out = new long[parsed.size()];
        for (int i = 0; i < parsed.size(); i++) {
            out[i] = parsed.get(i);
        }
        return out;
    }

    /**
     * 按指纹推送它所属的事件卡片 —— 手动验证与补推用。
     *
     * <p>人要看的是「这件事」，而指纹只是它的一个来源。
     * 以前这个入口推的是另一套「指纹卡片」版式，和线上自动推的不是同一张，
     * 于是手动验证一直在验一张永远不会发出去的卡片。
     */
    public PushResult pushByFingerprint(Long fpId, boolean force) {
        PushResult result = new PushResult();
        if (fpId == null) {
            result.setSkipReason("指纹不存在");
            return result;
        }
        InspectEvent event = eventMapper.selectLatestByFpId(fpId);
        if (event == null) {
            result.setSkipReason("该指纹还没有对应的事件，下一轮采集后再试");
            return result;
        }
        if (!force) {
            String blocked = inspectSwitch.whyBlocked("PUSH");
            if (blocked != null) {
                result.setSkipReason(blocked);
                return result;
            }
            String skip = aiGate(event);
            if (skip != null) {
                result.setSkipReason(skip);
                return result;
            }
        }
        boolean ok = doPush(event, null);
        result.setPushed(ok);
        if (!ok) {
            result.setErrorMsg("卡片发送失败，详见日志");
        } else {
            result.setMessageId(event.getLastMessageId());
        }
        return result;
    }

    private boolean doPush(InspectEvent event, List<String> related) {
        String card = null;
        try {
            card = cardBuilder.buildEvent(event,
                    event.getFpId() == null ? null : analysisMapper.selectLatestByFpId(event.getFpId()),
                    event.getFpId() == null ? null : sampleMapper.selectLatestByFpId(event.getFpId()),
                    related);
            FeishuApiClient.ApiResult api =
                    apiClient.sendCard(properties.getPush().getChatId(), card);
            if (api == null || !api.isSuccess()) {
                String err = api == null ? "无响应" : api.getMsg();
                log.warn("【事件推送】#{} 发送失败：{}", event.getId(), err);
                runRecorder.push(event, card, null, "飞书发送失败：" + err);
                return false;
            }
            // 先记再改推送次数：记录里的「第几次提醒」要按推送前的次数算
            runRecorder.push(event, card, api.getMessageId(), null);
            eventMapper.markPushed(event.getId(), api.getMessageId(), new Date());
            log.info("【事件推送】#{} {} 第 {} 次推送，已持续 {}，累计 {} 次",
                    event.getId(), event.getHeadline(), nzi(event.getPushCount()) + 1,
                    event.durationText(), event.getTotalCount());
            return true;
        } catch (Exception e) {
            log.error("【事件推送】#{} 失败：{}", event.getId(), e.getMessage());
            runRecorder.push(event, card, null, e.getMessage());
            return false;
        }
    }

    /**
     * 把群里那张卡片同步成「已标注」。
     *
     * <p>必须用<b>事件自己的</b> messageId：事件卡片是由本服务推的，
     * messageId 记在事件上；指纹的 lastMessageId 属于旧的单指纹卡片链路，
     * 拿它去 patch 会改错消息，或者（更常见）压根找不到而静默失败。
     *
     * @return 是否同步成功
     */
    public boolean syncHandledCard(Long eventId, String operatorName) {
        try {
            InspectEvent event = eventMapper.selectById(eventId);
            if (event == null || event.getLastMessageId() == null) {
                // 从未推送过卡片，没有需要同步的对象
                return false;
            }
            String current = cardBuilder.buildEvent(event,
                    event.getFpId() == null ? null : analysisMapper.selectLatestByFpId(event.getFpId()),
                    event.getFpId() == null ? null : sampleMapper.selectLatestByFpId(event.getFpId()));
            String handled = cardBuilder.buildHandled(current, "已标注", operatorName);
            if (handled == null) {
                return false;
            }
            FeishuApiClient.ApiResult api = apiClient.patchCard(event.getLastMessageId(), handled);
            if (api.isSuccess()) {
                // 成功也要留痕：飞书的消息查询接口对卡片只返回降级占位内容，
                // 事后无法从飞书侧确认同步是否发生过，只能靠这条日志
                log.info("已把群里的卡片同步为「已标注」 eventId={} messageId={}",
                        eventId, event.getLastMessageId());
            } else {
                log.warn("同步卡片为已标注失败 eventId={} messageId={} err={}",
                        eventId, event.getLastMessageId(), api.getMsg());
            }
            return api.isSuccess();
        } catch (Exception e) {
            log.warn("同步卡片为已标注异常 eventId={}：{}", eventId, e.getMessage());
            return false;
        }
    }

    /** 今天已经首推过的事件数。只数首推，升级重推不占额度 */
    private long countFirstPushedToday(List<InspectEvent> candidates) {
        Date start = todayStart();
        long n = 0;
        for (InspectEvent e : candidates) {
            if (nzi(e.getPushCount()) == 1 && e.getLastPushTime() != null
                    && !e.getLastPushTime().before(start)) {
                n++;
            }
        }
        return n;
    }

    private Date todayStart() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    private int nzi(Integer v) {
        return v == null ? 0 : v;
    }
}
