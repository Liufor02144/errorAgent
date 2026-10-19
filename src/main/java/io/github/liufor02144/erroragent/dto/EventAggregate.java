package io.github.liufor02144.erroragent.dto;

import io.github.liufor02144.erroragent.entity.InspectFingerprint;
import io.github.liufor02144.erroragent.entity.InspectLibrary;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 一个模版在本轮采集中的归并结果。
 *
 * <p>模版是事件的聚合键，而同一个模版可能被多个粗指纹命中 ——
 * order 和 product 各自的 Redis 超时是两个指纹、一个模版、一件事。
 * 这个对象就是把它们收拢起来：量级相加，涉及的服务与代码位置各自去重保留。
 *
 * <p>聚合掉的是<b>身份</b>，不是信息：具体哪些服务、哪些代码位置
 * 都记下来在事件详情里展示，否则定位能力就没了。
 */
public class EventAggregate {

    private final InspectLibrary template;
    private final JudgeResult primaryJudge;

    private final Set<String> apps = new LinkedHashSet<>();
    private final Set<String> frames = new LinkedHashSet<>();

    /** 涉及的粗指纹 id，取样本与代码定位时用第一个 */
    private final Set<Long> fpIds = new LinkedHashSet<>();

    private InspectFingerprint representative;
    private long addCount;
    private Date lastSeen;
    private String judgeState;
    private String reason;
    private int appCountFromMetric;

    /**
     * 按代码位置单独成事件（代码缺陷类异常）。
     *
     * <p>为 true 时按代表指纹找已有事件，而不是按模版 ——
     * 同一个模版下，不同代码位置的空指针是不同的 bug
     */
    private boolean perLocation;

    public boolean isPerLocation() {
        return perLocation;
    }

    public void setPerLocation(boolean perLocation) {
        this.perLocation = perLocation;
    }

    public EventAggregate(InspectLibrary template, JudgeResult judge) {
        this.template = template;
        this.primaryJudge = judge;
        this.judgeState = judge.getJudgeState();
        this.reason = judge.getReason();
    }

    /**
     * @param observed 本轮观测到的条数。由调用方算好传入 ——
     *                 judge 里的 currentHourCount 在跨小时窗口下可能为 0，
     *                 只依赖它会让整个事件的量级变成 0
     */
    public void add(InspectFingerprint fp, JudgeResult judge, long observed) {
        if (fp.getId() != null) {
            fpIds.add(fp.getId());
        }
        if (notBlank(fp.getAppId())) {
            apps.add(fp.getAppId());
        }
        if (notBlank(fp.getBizFrame()) && !"NO_STACK".equals(fp.getBizFrame())) {
            frames.add(fp.getBizFrame());
        }
        // 指标型日志本身就是跨服务合并过的，它自带的服务数要带上来
        if (fp.getAppCount() != null && fp.getAppCount() > appCountFromMetric) {
            appCountFromMetric = fp.getAppCount();
        }

        long cnt = observed > 0 ? observed
                : (judge.getCurrentHourCount() > 0 ? judge.getCurrentHourCount() : 0L);
        addCount += cnt;

        if (fp.getLastSeenTime() != null
                && (lastSeen == null || fp.getLastSeenTime().after(lastSeen))) {
            lastSeen = fp.getLastSeenTime();
        }
        // 取量级最大的那个指纹作为代表：标题、异常类、代码定位都从它取，
        // 用第一个遇到的会让展示取决于遍历顺序
        if (representative == null || cnt > representativeCount) {
            representative = fp;
            representativeCount = cnt;
        }
        // 突增比新增更需要被看见，判定状态按更严重的那个走
        if (JudgeResult.SURGE.equals(judge.getJudgeState())) {
            judgeState = JudgeResult.SURGE;
            reason = judge.getReason();
        }
    }

    private long representativeCount;

    public InspectLibrary getTemplate() {
        return template;
    }

    public Long templateId() {
        return template == null ? null : template.getId();
    }

    public InspectFingerprint getRepresentative() {
        return representative;
    }

    public JudgeResult getPrimaryJudge() {
        return primaryJudge;
    }

    public String getJudgeState() {
        return judgeState;
    }

    public String getReason() {
        return reason;
    }

    /** 本轮观测到的条数之和。真正的累计量由 stat 表重算，这里只用于首次建事件 */
    public long getAddCount() {
        return addCount;
    }

    public Date getLastSeen() {
        return lastSeen == null ? new Date() : lastSeen;
    }

    public Set<Long> getFpIds() {
        return fpIds;
    }

    /** 涉及服务数。指标型日志自带的服务数更大时以它为准 */
    public int appCount() {
        return Math.max(apps.size(), appCountFromMetric);
    }

    public int frameCount() {
        return frames.size();
    }

    public String appList() {
        return join(apps, 2000);
    }

    public String frameList() {
        return join(frames, 2000);
    }

    /** 事件标题：模版本身就是归一后的人话，优先用它 */
    public String headline() {
        if (template != null && notBlank(template.getTemplate())) {
            return template.getTemplate();
        }
        if (representative == null) {
            return "（无可用描述）";
        }
        if (notBlank(representative.getExcCause())) {
            return representative.getExcCause();
        }
        if (notBlank(representative.getMsgTpl())) {
            return representative.getMsgTpl();
        }
        return notBlank(representative.getExcSimple())
                ? representative.getExcSimple() : "（无可用描述）";
    }

    private String join(Set<String> set, int maxChars) {
        if (set.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() + s.length() + 1 > maxChars) {
                sb.append(",…共 ").append(set.size()).append(" 个");
                break;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private boolean notBlank(String s) {
        // 统一口径：字面量 "null" 不算内容（空指针的 exc_cause 就是它）
        return io.github.liufor02144.erroragent.service.InspectText.notBlank(s);
    }
}
