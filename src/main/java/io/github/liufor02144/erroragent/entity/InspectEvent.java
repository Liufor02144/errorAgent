package io.github.liufor02144.erroragent.entity;

import lombok.Data;

import java.util.Date;

/**
 * 异常事件：唯一需要人看的东西。
 *
 * <p>只有两种成因 ——
 * <ul>
 *   <li>{@link #TYPE_NEW}：不在异常库里的异常出现了（Redis 超时、SQL 异常、从没见过的）</li>
 *   <li>{@link #TYPE_SURGE}：在异常库里，但量级突增</li>
 * </ul>
 *
 * <p><b>事件不做幂等压制。</b>异常持续存在时，事件持续累计、持续升级重推 ——
 * Redis 超时持续一整天却只报过一次，那才是事故。所以这里记的是
 * 「已持续多久、累计多少次」，而不是「报过没报过」。
 */
@Data
public class InspectEvent {

    /** 不在异常库 */
    public static final String TYPE_NEW = "NEW";
    /** 在异常库但突增 */
    public static final String TYPE_SURGE = "SURGE";

    public static final String SCOPE_SINGLE = "SINGLE";

    /** 按代码位置单独成事件的代码缺陷（空指针等）：同模版不同位置是不同的 bug */
    public static final String SCOPE_LOCATION = "LOCATION";

    private Long id;

    private String eventType;

    private String scope;

    private Long fpId;

    private String fpHash;

    /** 所属异常模版，事件的聚合主键。跨服务的同一异常共用一个模版 */
    private Long templateId;

    /** 模版文本，列表联表带出，仅展示用 */
    private String templateText;

    /** 模版是否已标记业务预期，仅展示用 */
    private Integer templateExpected;

    /** 取信息量最大的那句：异常原因 > 消息内容 > 异常类名 */
    private String headline;

    private String appId;

    /** >1 时页面显示「多服务」，因为跨服务合并的指纹 app_id 只是任取的一个 */
    private Integer appCount;

    private String appList;

    /** 涉及的代码位置列表。模版跨服务聚合后，单个 biz_frame 说明不了范围 */
    private String frameList;

    private Integer fpCount;

    private String excSimple;

    private String excCause;

    private String severity;

    private Date firstSeenTime;

    /** 最近一次仍在发生的时间 */
    private Date lastSeenTime;

    private Long totalCount;

    private String triggerReason;

    /** 最近一次送 AI 分析的时间。事件维度限流用 —— 持续存在的事件每 5 分钟都会被扫到 */
    private Date lastAnalyzeTime;

    /** 上次分析成功时的累计次数。量级涨到它的 N 倍才重新分析 */
    private Long analyzedTotal;

    /** 上次分析成功时涉及的服务数。扩散到新服务就重新分析 */
    private Integer analyzedAppCount;

    private Integer handled;

    private String handleNote;

    private String handler;

    private Date handleTime;

    private Integer pushCount;

    /** 本次是新建还是累计到已有事件上。只在登记时有意义，不落库 */
    private transient boolean justCreated;

    private Date lastPushTime;

    private String lastMessageId;

    private Date createTime;

    private Date updateTime;

    private Integer deleted;

    /** 持续时长（分钟）。缺时间时返回 0，不抛异常 */
    public long durationMinutes() {
        if (firstSeenTime == null || lastSeenTime == null) {
            return 0L;
        }
        long ms = lastSeenTime.getTime() - firstSeenTime.getTime();
        return ms <= 0 ? 0L : ms / 60_000L;
    }

    /**
     * 人可读的持续时长。事件卡片的核心信息 ——
     * 「已持续 3 小时 20 分」比「10:15 发生」更能说明严重程度。
     */
    public String durationText() {
        long minutes = durationMinutes();
        if (minutes < 1) {
            return "刚刚发生";
        }
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        if (hours < 24) {
            return rest == 0 ? hours + " 小时" : hours + " 小时 " + rest + " 分";
        }
        long days = hours / 24;
        return days + " 天 " + (hours % 24) + " 小时";
    }

    public boolean isHandled() {
        return handled != null && handled == 1;
    }

}
