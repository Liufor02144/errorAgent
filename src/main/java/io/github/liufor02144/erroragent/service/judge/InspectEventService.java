package io.github.liufor02144.erroragent.service.judge;

import io.github.liufor02144.erroragent.dto.EventAggregate;
import io.github.liufor02144.erroragent.dto.JudgeResult;
import io.github.liufor02144.erroragent.entity.InspectEvent;
import io.github.liufor02144.erroragent.entity.InspectFingerprint;
import io.github.liufor02144.erroragent.mapper.InspectEventMapper;
import io.github.liufor02144.erroragent.mapper.InspectStatMapper;
import io.github.liufor02144.erroragent.service.InspectConfigKeys;
import io.github.liufor02144.erroragent.service.InspectConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Calendar;
import java.util.Date;

/**
 * 异常事件的产生与累计。
 *
 * <p>核心规则：<b>同一个异常持续发生，是一条事件在长大，不是很多条事件。</b>
 * Redis 超时连续 3 小时，应该呈现为「已持续 3 小时 20 分、累计 4182 次」的单条事件并逐级升级重推，
 * 而不是 36 条互不相干的记录，也不是「报过一次就再也不吭声」。
 */
@Slf4j
@Service
public class InspectEventService {

    @Resource
    private InspectEventMapper eventMapper;

    @Resource
    private InspectConfigService configService;

    @Resource
    private InspectStatMapper statMapper;

    @Resource
    private io.github.liufor02144.erroragent.config.InspectProperties properties;

    /**
     * 把一个模版在本轮的归并结果落成事件：已有未处理事件就累计，否则新建。
     *
     * <p>聚合键是<b>模版</b>而不是指纹 —— order 和 product 的同一个 Redis 超时
     * 是一件事。涉及哪些服务、哪些代码位置记在事件上，定位信息不丢。
     */
    public InspectEvent record(EventAggregate agg) {
        if (agg == null || agg.getRepresentative() == null) {
            return null;
        }
        InspectFingerprint rep = agg.getRepresentative();
        Date seen = agg.getLastSeen();
        Long templateId = agg.templateId();

        boolean byLocation = agg.isPerLocation() && templateId != null;
        String frame = agg.frameList();
        InspectEvent open;
        if (byLocation) {
            open = eventMapper.selectOpenByLocation(templateId, rep.getAppId(), frame, 0);
        } else {
            open = templateId != null
                    ? eventMapper.selectOpenByTemplateId(templateId)
                    : eventMapper.selectOpenByFpId(rep.getId());
        }

        if (open != null) {
            // 从 stat 表重算事件期间的真实总量，不做加法 —— 采集窗口每轮重叠，
            // 累加会把同一小时的量重复计入。
            // 但重算只覆盖本轮出现的指纹，事件里其他指纹这轮没出现时会少算 ——
            // 实测一个事件从 9 次被「重算」成 7 次。所以只增不减
            long realTotal = Math.max(recountTotal(agg, open.getFirstSeenTime(), seen),
                    open.getTotalCount() == null ? 0L : open.getTotalCount());
            // 服务与代码位置要并集，不能用本轮的覆盖：
            // 覆盖会让「扩散到新服务」永远看不出来，重新分析的条件也就永远不触发
            java.util.Set<String> apps = union(open.getAppList(), agg.appList());
            java.util.Set<String> frames = union(open.getFrameList(), agg.frameList());
            eventMapper.accumulate(open.getId(), realTotal, seen,
                    rep.getSeverity(), agg.getReason(), Math.max(apps.size(), agg.appCount()),
                    joinList(apps), joinList(frames));
            open.setTotalCount(realTotal);
            open.setLastSeenTime(seen);
            open.setTriggerReason(agg.getReason());
            log.info("【事件累计】#{} {} 已持续 {}，累计 {} 次，涉及 {} 个服务",
                    open.getId(), open.getHeadline(), open.durationText(),
                    open.getTotalCount(), agg.appCount());
            return open;
        }

        // 标注处理后是否真的「又出现了」，要拿日志时间和处理时间比，不能看本轮有没有量：
        // 采集窗口约 11 分钟、每 5 分钟一轮，刚标完处理的下一轮，窗口里还是处理前那批日志。
        // 不挡的话，点完「已处理」几分钟内就会冒出一条一模一样的新事件再推一次
        InspectEvent last = byLocation
                ? eventMapper.selectOpenByLocation(templateId, rep.getAppId(), frame, 1)
                : eventMapper.selectLastHandled(templateId, rep.getId());
        if (last != null && last.getHandleTime() != null && !seen.after(last.getHandleTime())) {
            log.info("【事件复发·忽略】{} 本轮最后出现于 {}，早于 #{} 的处理时间 {}，是处理前的存量日志",
                    agg.headline(), seen, last.getId(), last.getHandleTime());
            return null;
        }

        InspectEvent event = new InspectEvent();
        event.setEventType(JudgeResult.SURGE.equals(agg.getJudgeState())
                ? InspectEvent.TYPE_SURGE : InspectEvent.TYPE_NEW);
        event.setScope(byLocation ? InspectEvent.SCOPE_LOCATION : InspectEvent.SCOPE_SINGLE);
        event.setTemplateId(templateId);
        event.setFpId(rep.getId());
        event.setFpHash(rep.getFpHash());
        // 按位置成事件的，标题带上位置：同一个模版下会有好几条，只写「NullPointerException」分不清
        event.setHeadline(truncate(byLocation
                ? agg.headline() + "（" + shortApp(rep.getAppId()) + " · " + frame + "）"
                : agg.headline(), 500));
        event.setAppId(rep.getAppId());
        event.setAppCount(agg.appCount());
        event.setAppList(agg.appList());
        event.setFrameList(agg.frameList());
        event.setFpCount(agg.getFpIds().size());
        event.setExcSimple(rep.getExcSimple());
        event.setExcCause(rep.getExcCause());
        event.setSeverity(rep.getSeverity());
        // 事件从「本轮被判出来」起算，不回溯指纹的历史首现 ——
        // 一个存在半年的异常今天突增，持续时长应该是「今天开始的这段」
        event.setFirstSeenTime(seen);
        event.setLastSeenTime(seen);
        event.setTotalCount(agg.getAddCount());
        event.setTriggerReason(recurReason(last, agg.getReason()));
        eventMapper.insert(event);
        event.setJustCreated(true);

        log.warn("【异常事件·{}】{}（{} 个服务 / {} 个位置）—— {}",
                InspectEvent.TYPE_NEW.equals(event.getEventType()) ? "新增" : "突增",
                event.getHeadline(), agg.appCount(), agg.frameCount(), agg.getReason());
        return event;
    }

    /**
     * 重算事件期间的真实总量。
     *
     * <p>按事件涉及的<b>全部指纹</b>汇总：模版聚合了多个指纹，
     * 只数其中一个会严重低估。
     *
     * <p>两个坑都踩过，别再踩：
     * <ol>
     *   <li>stat 表的 {@code stat_hour} 是<b>整点</b>，而事件的首现时间是墙上时间。
     *       拿 17:52 当下界去筛整点行，17:00 那行会被排除 ——
     *       生命周期在同一小时内的事件会一行都匹配不上。所以下界必须向下取整到整点</li>
     *   <li>重算结果<b>不能低于本轮实测值</b>。SQL 侧的 COALESCE 会把「零行」
     *       变成 0，光靠 null 判断兜不住，必须显式取大值 ——
     *       否则事件量级会被覆盖成 0，页面上看起来像没发生过</li>
     * </ol>
     */
    private long recountTotal(EventAggregate agg, Date from, Date to) {
        if (agg.getFpIds().isEmpty() || from == null) {
            return agg.getAddCount();
        }
        try {
            Long sum = statMapper.sumTotalByFpIds(
                    new java.util.ArrayList<>(agg.getFpIds()), floorToHour(from), to);
            // 取大值：重算只用来补齐历史小时，绝不能让计数倒退
            return sum == null ? agg.getAddCount() : Math.max(sum, agg.getAddCount());
        } catch (Exception e) {
            log.warn("重算事件量级失败，沿用本轮观测值：{}", e.getMessage());
            return agg.getAddCount();
        }
    }

    /**
     * 人工标注处理过的异常再次出现，判定依据里要写明「这是复发」。
     *
     * <p>看卡片的人需要知道：这件事上次谁处理的、怎么说的 —— 修复没生效、
     * 修复漏了分支、还是另一个入口触发的，都要从这里接着查。
     * 系统自动收敛（handler=system）关掉的不算复发，那只是沉寂后再现。
     */
    static String recurReason(InspectEvent last, String reason) {
        if (last == null || last.getHandleTime() == null || "system".equals(last.getHandler())) {
            return reason;
        }
        StringBuilder sb = new StringBuilder("复发：已于 ")
                .append(new java.text.SimpleDateFormat("MM-dd HH:mm").format(last.getHandleTime()))
                .append(" 由 ").append(last.getHandler() == null ? "未知" : last.getHandler())
                .append(" 标注已处理");
        if (last.getHandleNote() != null && !last.getHandleNote().trim().isEmpty()) {
            String note = last.getHandleNote().trim();
            sb.append("（").append(note.length() > 60 ? note.substring(0, 60) + "…" : note).append("）");
        }
        sb.append("，处理后再次出现");
        if (reason != null && !reason.isEmpty()) {
            sb.append("；").append(reason);
        }
        return sb.toString();
    }

    private static java.util.Set<String> union(String existing, String added) {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        for (String list : new String[]{existing, added}) {
            if (list == null) {
                continue;
            }
            for (String s : list.split(",")) {
                // 超长截断时拼的「…共 N 个」不是成员
                if (!s.trim().isEmpty() && !s.startsWith("…")) {
                    set.add(s.trim());
                }
            }
        }
        return set;
    }

    private static String joinList(java.util.Set<String> set) {
        if (set.isEmpty()) {
            return null;
        }
        String s = String.join(",", set);
        return s.length() <= 2000 ? s : s.substring(0, 2000);
    }

    private String shortApp(String appId) {
        if (appId == null) {
            return "-";
        }
        return properties == null ? appId : properties.getNaming().displayApp(appId);
    }

    /** 向下取整到整点，对齐 stat 表的 stat_hour 粒度 */
    private static Date floorToHour(Date d) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTime(d);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 关闭模版库建立前产生的错位事件，让它们按模版重新聚合。
     *
     * @return 关闭的事件数
     */
    public int closeStaleWithoutTemplate() {
        int closed = eventMapper.closeStaleWithoutTemplate();
        if (closed > 0) {
            log.info("【事件重建】关闭 {} 个模版库建立前产生的事件，"
                    + "异常若仍在发生会按正确模版重新聚合", closed);
        }
        return closed;
    }

    /**
     * 自动收敛：连续静默超过阈值的事件关闭。
     *
     * <p>不做这一步，一个偶发异常会让事件的「已持续」无限膨胀 ——
     * 上周出现过一次、今天又出现一次，会被算成持续了 7 天。
     */
    public int autoCloseQuiet() {
        // 先清自相矛盾的：模版已确认业务预期、事件却还挂在待处理
        closeExpected();

        int quietHours = configService.getInt(InspectConfigKeys.EVENT_CLOSE_QUIET_HOURS, 6);
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR_OF_DAY, -quietHours);
        int closed = eventMapper.autoCloseQuiet(cal.getTime());
        if (closed > 0) {
            log.info("【事件收敛】{} 个事件连续 {} 小时未再出现，自动关闭", closed, quietHours);
        }
        return closed;
    }

    /**
     * 关闭「模版已标业务预期、事件却还开着」的事件。
     *
     * <p>标记业务预期的入口有四处：模版页、事件页、AI 复核、批量基线归零。
     * 每处都记得同步关闭事件是不可靠的（实测就漏了模版页那条，
     * 留下 12 条「模版说这正常、事件还在催你处理」的矛盾记录）。
     * 所以这里按状态做一次无条件清扫，每轮采集自愈。
     */
    public int closeExpected() {
        try {
            int closed = eventMapper.closeByExpectedTemplate();
            if (closed > 0) {
                log.info("【事件收敛】{} 个事件的模版已确认为业务预期，自动关闭", closed);
            }
            return closed;
        } catch (Exception e) {
            log.warn("清扫业务预期事件失败：{}", e.getMessage());
            return 0;
        }
    }

    public InspectEvent getById(Long id) {
        return eventMapper.selectById(id);
    }

    public boolean markHandled(Long id, String note, String handler) {
        return eventMapper.markHandled(id, note, handler) > 0;
    }
}
