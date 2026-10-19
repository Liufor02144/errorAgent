package io.github.liufor02144.erroragent.service.push;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.github.liufor02144.erroragent.entity.InspectAnalysis;
import io.github.liufor02144.erroragent.entity.InspectEvent;
import io.github.liufor02144.erroragent.entity.InspectSample;
import io.github.liufor02144.erroragent.service.template.LogKeyText;
import io.github.liufor02144.erroragent.service.InspectConfigKeys;
import io.github.liufor02144.erroragent.service.InspectConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 异常卡片构造（飞书卡片 2.0）。
 *
 * <p>卡片只呈现三类内容：<b>系统算出来的事实</b>（量级、首现、持续时长）、
 * <b>模型的判断</b>（根因、影响，标注来源）、<b>原始证据</b>（代码、报错）。
 * 没有「建议」区域——要不要改、怎么改由人决定，模型给的建议一旦错了，
 * 反而会把判断带偏。
 *
 * <p>证据区默认折叠。堆栈和代码都很长，展开会把结论顶出屏幕，
 * 而群里第一眼要看到的是「哪个服务、多严重、持续多久」。
 */
@Slf4j
@Component
public class InspectCardBuilder {

    /** 飞书单条卡片有体积上限，证据区按字符数兜底截断 */
    private static final int MAX_SNIPPET_CHARS = 2000;
    private static final int MAX_RAW_CHARS = 3000;

    /** 交互区标识，用于标注后精确摘除按钮 */
    private static final String ACTION_ELEMENT_ID = "inspect_actions";

    @Resource
    private InspectConfigService configService;

    @Resource
    private io.github.liufor02144.erroragent.config.InspectProperties properties;

    /**
     * 构造异常事件卡片。
     *
     * <p>与旧的单指纹卡片相比，这张卡片把<b>「已持续多久、累计多少次」放在最显眼处</b> ——
     * 因为事件不做幂等压制：一个 Redis 超时持续到第 3 小时还没人处理时，
     * 卡片应该比第一次更刺眼，而不是安静下去。重推次数与持续时长就是这个升级信号。
     *
     * <p>不出现「分类」：分类对判断没有帮助，真正决定要不要看的是严重度和量级。
     *
     * @param analysis 可为 null（事件产生时 AI 还没分析完，不该因此不发）
     */
    public String buildEvent(InspectEvent event, InspectAnalysis analysis,
                             InspectSample sample) {
        return buildEvent(event, analysis, sample, null);
    }

    /**
     * @param related 同一批请求里的其他报错（已合并进这张卡片、不再单独推送），可为 null
     */
    public String buildEvent(InspectEvent event, InspectAnalysis analysis,
                             InspectSample sample, List<String> related) {
        JSONObject card = new JSONObject(true);
        card.put("schema", "2.0");

        JSONObject config = new JSONObject(true);
        config.put("wide_screen_mode", true);
        card.put("config", config);

        card.put("header", eventHeader(event));

        JSONObject body = new JSONObject(true);
        JSONArray elements = new JSONArray();

        // 重推时先亮升级横幅：这不是新问题，是旧问题一直没人管
        int pushCount = nzi(event.getPushCount());
        if (pushCount > 0) {
            elements.add(markdown(String.format(
                    "🔁 **第 %d 次提醒** —— 这个异常已持续 **%s**，累计 **%s** 次，仍未标注处理",
                    pushCount + 1, event.durationText(), fmtNum(nz(event.getTotalCount())))));
        }

        elements.add(eventIdentity(event));
        // 同一个故障在一次请求里常常打好几条不同的 ERROR（内层原因 + 外层包装），
        // 它们已合并进这张卡片，这里列出来，免得有人以为另外那几条没人管
        if (related != null && !related.isEmpty()) {
            StringBuilder rel = new StringBuilder("<font color='grey'>🔗 同一批请求里还有：</font>");
            for (String r : related) {
                rel.append("\n<font color='grey'>· ").append(escape(r)).append("</font>");
            }
            elements.add(markdown(rel.toString()));
        }
        elements.add(eventMetrics(event));

        String traces = traceLine(sample);
        if (traces != null) {
            elements.add(markdown(traces));
        }

        if (notBlank(event.getTriggerReason())) {
            elements.add(markdown("<font color='grey'>**判定依据**　"
                    + escape(truncate(event.getTriggerReason(), 200)) + "</font>"));
        }

        String conclusion = conclusion(analysis);
        if (conclusion != null) {
            elements.add(divider());
            elements.add(markdown(conclusion));
        } else {
            elements.add(divider());
            elements.add(markdown("<font color='grey'>AI 分析尚未完成或未达分析门槛，"
                    + "以上为系统直接观测到的事实。</font>"));
        }

        // 同一请求的其他报错是核对根因的依据：真正的原因常常打在另一条日志里
        JSONObject siblingPanel = collapsible("同一请求的其他报错",
                analysis == null ? null : analysis.getTraceChain(), "text", MAX_RAW_CHARS, null, true);
        if (siblingPanel != null) {
            elements.add(siblingPanel);
        }
        JSONObject codePanel = collapsible("代码片段", analysis == null ? null : analysis.getCodeSnippet(),
                "java", MAX_SNIPPET_CHARS, codeRefLine(analysis));
        if (codePanel != null) {
            elements.add(codePanel);
        }
        JSONObject rawPanel = collapsible("原始报错", sample == null ? null : sample.getRawMessage(),
                "text", MAX_RAW_CHARS, null);
        if (rawPanel != null) {
            elements.add(rawPanel);
        }

        elements.add(divider());
        elements.add(eventActions(event));

        body.put("elements", elements);
        card.put("body", body);
        return card.toJSONString();
    }

    /**
     * 链路追踪行：代表样本上的 traceId。
     *
     * <p>看到卡片后的下一步几乎一定是拿 traceId 查整条链路，有一个总比让人回日志平台自己捞强。
     */
    String traceLine(InspectSample sample) {
        if (sample == null || !validTraceId(sample.getTraceId())) {
            return null;
        }
        return "**链路追踪**　`" + escape(sample.getTraceId().trim()) + "`";
    }

    /** 没接链路追踪的服务会打出占位值 */
    private static boolean validTraceId(String id) {
        if (id == null) {
            return false;
        }
        String t = id.trim();
        if (t.toUpperCase().startsWith("TID:")) {
            t = t.substring(4).trim();
        }
        return !t.isEmpty() && !"N/A".equalsIgnoreCase(t) && !"-".equals(t)
                && !"null".equalsIgnoreCase(t) && !"unknown".equalsIgnoreCase(t);
    }

    /**
     * 把模版文本整理成人能读的一句话。
     *
     * <p>模版是<b>给机器匹配用的</b>，里面有占位符和分隔符 ——
     * {@code ErrorScene^_^{NUM}^_^aop.ACQ.SYSTEM_ERROR^_^{NUM}.{NUM}^_^Linux^_^{TIME}^_^Proto}
     * 直接当标题显示，一眼看过去像程序报错乱码了。
     *
     * <p>清理三件事：
     * <ol>
     *   <li>{@code ^_^} 这类机器分隔符换成中点</li>
     *   <li>纯占位符的段整段丢掉 —— {@code {NUM}}、{@code {TIME}}、{@code {NUM}.{NUM}}
     *       对读的人没有任何意义</li>
     *   <li>末尾被截断的残词（{@code Proto}、{@code Str}）去掉</li>
     * </ol>
     * 上面那串清理完是「ErrorScene · aop.ACQ.SYSTEM_ERROR · Linux」。
     */
    private String readable(String headline) {
        if (!notBlank(headline)) {
            return "无可用描述";
        }
        String text = headline
                // 机器分隔符 → 中点
                .replace("^_^", " · ")
                .replace("|", " · ");
        String[] parts = text.split(" · ");
        StringBuilder sb = new StringBuilder();
        for (String raw : parts) {
            String seg = raw.trim();
            if (seg.isEmpty() || isPlaceholderOnly(seg)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(seg);
        }
        String out = sb.length() > 0 ? sb.toString() : text.trim();
        // 占位符留在句中时（如「订单 {NUM} 同步失败」）保留，它还起着语序作用；
        // 但连续多个挤在一起就没必要了
        return out.replaceAll("(?:\\{[A-Z一-龥]+\\}[.\\s]*){2,}", "… ").trim();
    }

    /** 整段都是占位符 / 纯符号，对读的人没有信息量 */
    private boolean isPlaceholderOnly(String seg) {
        String stripped = seg.replaceAll("\\{[A-Z一-龥]+\\}", "").replaceAll("[\\s.,;:\\-_]", "");
        return stripped.isEmpty();
    }

    /**
     * 标题正文：一句话说清「出了什么事」。
     *
     * <p>优先级：业务影响面 &gt; 异常本身。
     * 「12 个租户受影响」比「BusinessException」有用得多 ——
     * 后者要点开代码片段才知道意味着什么，而标题是给扫一眼的人看的。
     */
    private String eventTitle(InspectEvent event, boolean surge) {
        String prefix = surge ? "突增" : "";
        // 标题的位置用来说「出了什么事」，用清理过的异常描述
        return prefix + truncate(readable(event.getHeadline()), 40);
    }

    private JSONObject eventHeader(InspectEvent event) {
        String severity = event.getSeverity();
        JSONObject header = new JSONObject(true);

        JSONObject title = new JSONObject(true);
        title.put("tag", "plain_text");
        boolean surge = InspectEvent.TYPE_SURGE.equals(event.getEventType());
        // 标题要回答「出了什么事」，而不是「这条告警属于哪一类」。
        // 「P1 疑似缺陷 · 新增异常」这种写法对看的人毫无信息量 ——
        // 推到群里就意味着需要有人关注，那第一眼就该看到影响了谁。
        // 不会出现「未定级」：推送前的 severityGate 已经保证有定级，
        // 没定级的根本不会走到这里 —— 连严重到什么程度都说不出来的东西，
        // 没有资格占用别人的注意力
        title.put("content", severityIcon(severity) + " " + nv(severity, "P2")
                + " · " + eventTitle(event, surge));
        header.put("title", title);

        JSONObject subtitle = new JSONObject(true);
        subtitle.put("tag", "plain_text");
        // 跨服务合并的指纹 app_id 只是任取的一个，直接写出来会指错服务
        String where = nzi(event.getAppCount()) > 1
                ? event.getAppCount() + " 个服务"
                : nv(event.getAppId(), "未知服务");
        // 副标题补充「在哪、多久、多少」，不和标题重复
        subtitle.put("content", where + "　已持续 " + event.durationText()
                + "　累计 " + fmtNum(nz(event.getTotalCount())) + " 次");
        header.put("subtitle", subtitle);

        header.put("template", templateColor(severity));
        return header;
    }

    private JSONObject eventIdentity(InspectEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(escape(truncate(readable(event.getHeadline()), 120))).append("**\n");

        StringBuilder sub = new StringBuilder();
        if (notBlank(event.getExcSimple())) {
            sub.append(event.getExcSimple());
        }
        if (notBlank(event.getExcCause()) && !event.getExcCause().equals(event.getHeadline())) {
            if (sub.length() > 0) {
                sub.append(" · ");
            }
            sub.append(truncate(event.getExcCause(), 120));
        }
        if (sub.length() > 0) {
            sb.append("<font color='grey'>").append(escape(sub.toString())).append("</font>");
        }
        return markdown(sb.toString());
    }

    /**
     * 两列指标：累计量级 / 持续时长。
     *
     * <p>持续时长单独占一列 —— 「4182 次」不够说明问题，
     * 「3 小时里 4182 次」才说明问题还在继续。
     */
    private JSONObject eventMetrics(InspectEvent event) {
        JSONArray columns = new JSONArray();
        columns.add(metricColumn("累计条数",
                fmtNum(nz(event.getTotalCount())) + " 次",
                "自 " + fmtTime(event.getFirstSeenTime())));
        columns.add(metricColumn("已持续",
                event.durationText(),
                "最近 " + fmtTime(event.getLastSeenTime())));

        JSONObject set = new JSONObject(true);
        set.put("tag", "column_set");
        set.put("horizontal_spacing", "8px");
        set.put("columns", columns);
        return set;
    }

    private JSONObject eventActions(InspectEvent event) {
        String detailUrl = eventDetailUrl(event.getId());
        if (detailUrl == null) {
            return markdownWithId(ACTION_ELEMENT_ID,
                    "⚠️ 未配置访问地址（erroragent.push.detail-base-url），无法跳转标注");
        }
        JSONArray columns = new JSONArray();
        columns.add(column(linkButton("去处理 →", detailUrl)));

        JSONObject set = new JSONObject(true);
        set.put("tag", "column_set");
        set.put("element_id", ACTION_ELEMENT_ID);
        set.put("horizontal_spacing", "8px");
        set.put("columns", columns);
        return set;
    }

    /**
     * 管理页面的访问域名。
     *
     * <p><b>部署配置优先</b>：测试与生产是两个域名，域名属于基础设施，
     * 和凭据、群 ID 同级，必须按环境隔离。配置表只作为兼容兜底 ——
     * 那张表按环境各有一份，但容易被同一份初始化脚本刷成同一个值。
     *
     * @return 去掉末尾斜杠的前缀；两处都没配时返回 null
     */
    private String webBaseUrl() {
        String base = properties.getPush().getDetailBaseUrl();
        if (!notBlank(base)) {
            base = configService.getString(InspectConfigKeys.PUSH_DETAIL_BASE_URL, "");
        }
        if (!notBlank(base)) {
            return null;
        }
        String trimmed = base.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * 详情页深链。
     *
     * <p>管理页面是单页应用，全站只有一个路由地址，靠 {@code module} 指定
     * 打开哪个模块、{@code eventId} 指定自动展开哪条详情。不能拼成
     * {@code /inspect/detail/{id}} 那种路径式 URL —— 前端没有对应路由，
     * 点过去只会停在使用者上次看的页面。
     *
     * <p>参数名<b>只能是 eventId</b>：前端只解析这一个。曾经指纹卡片拼的是
     * {@code fpId=}，点过去只是打开列表首页，详情不会展开。
     */
    private String eventDetailUrl(Long eventId) {
        String base = webBaseUrl();
        if (base == null || eventId == null) {
            return null;
        }
        return base + "/#/?module=InspectDashboard&eventId=" + eventId;
    }

    private int nzi(Integer v) {
        return v == null ? 0 : v;
    }

    /**
     * 构造反馈后的卡片：保留全部信息，按钮换成处理结论。
     *
     * <p>不删内容只换按钮，是为了让群里回看时仍能看到当时的证据；
     * 只把可点的部分收掉，避免重复标注。
     */
    public String buildHandled(String originalCardJson, String actionLabel, String operatorName) {
        JSONObject card;
        try {
            card = JSON.parseObject(originalCardJson);
        } catch (Exception e) {
            log.warn("原卡片解析失败，无法保留原内容：{}", e.getMessage());
            return null;
        }
        JSONObject body = card.getJSONObject("body");
        if (body == null) {
            return null;
        }
        JSONArray elements = body.getJSONArray("elements");
        if (elements == null) {
            return null;
        }
        // 去掉交互区，换成结论行
        JSONArray kept = new JSONArray();
        for (int i = 0; i < elements.size(); i++) {
            JSONObject el = elements.getJSONObject(i);
            if (el != null && ACTION_ELEMENT_ID.equals(el.getString("element_id"))) {
                continue;
            }
            kept.add(el);
        }
        String who = operatorName == null || operatorName.trim().isEmpty() ? "某位同学" : operatorName;
        kept.add(markdown("✅ **已标注为「" + actionLabel + "」** · " + who + " · "
                + new SimpleDateFormat("MM-dd HH:mm").format(new Date())));
        body.put("elements", kept);
        return card.toJSONString();
    }

    // ---------------- 各区块 ----------------

    /** 行首的异常类名 + 冒号 */
    private static final java.util.regex.Pattern EXC_PREFIX =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9_.$]+(?:Exception|Error)\\s*:\\s*");


    /** 单个指标：标签在上（小字灰色），数值居中（加粗），补充说明在下 */
    private JSONObject metricColumn(String label, String value, String sub) {
        JSONObject col = new JSONObject(true);
        col.put("tag", "column");
        col.put("width", "weighted");
        col.put("weight", 1);
        col.put("background_style", "grey");
        col.put("padding", "8px");
        col.put("vertical_spacing", "2px");

        StringBuilder sb = new StringBuilder();
        sb.append("<font color='grey'>").append(label).append("</font>\n");
        sb.append("**").append(value).append("**");
        if (sub != null && !sub.trim().isEmpty()) {
            sb.append("\n<font color='grey'>").append(sub).append("</font>");
        }
        JSONArray elements = new JSONArray();
        elements.add(markdown(sb.toString()));
        col.put("elements", elements);
        return col;
    }



    /** 模型结论。标注来源，与上面的事实区分开 */
    private String conclusion(InspectAnalysis analysis) {
        if (analysis == null) {
            return "**AI 分析**　未分析（可能被闸门拦截或调用失败），请人工判断";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("**AI 判断**（置信度 ").append(nv(analysis.getConfidence(), "-"));
        if (notBlank(analysis.getLocateConfidence())) {
            sb.append("，代码定位 ").append(analysis.getLocateConfidence());
        }
        sb.append("）\n");
        if (notBlank(analysis.getRootCause())) {
            sb.append("**根因**　").append(escape(truncate(analysis.getRootCause(), 500))).append('\n');
        }
        if (notBlank(analysis.getBusinessImpact())) {
            sb.append("**影响**　").append(escape(truncate(analysis.getBusinessImpact(), 500)));
        }
        return sb.toString();
    }

    /** 代码片段上方标注文件与行号，便于直接跳去看 */
    private String codeRefLine(InspectAnalysis analysis) {
        if (analysis == null || !notBlank(analysis.getCodeRef())) {
            return null;
        }
        return "`" + analysis.getCodeRef() + "`";
    }

    /** 折叠面板。内容为空时返回 null，由调用方跳过，避免出现空面板 */
    private JSONObject collapsible(String title, String content, String lang, int maxChars, String prefix) {
        return collapsible(title, content, lang, maxChars, prefix, false);
    }

    /**
     * @param expanded 默认是否展开。调用链展开 —— 哪一步失败、哪一步慢，是核对根因的依据；
     *                 代码片段和原始堆栈默认折叠，需要时再点开。
     */
    private JSONObject collapsible(String title, String content, String lang, int maxChars,
                                   String prefix, boolean expanded) {
        if (!notBlank(content)) {
            return null;
        }
        JSONObject panel = new JSONObject(true);
        panel.put("tag", "collapsible_panel");
        panel.put("expanded", expanded);

        JSONObject header = new JSONObject(true);
        JSONObject titleObj = new JSONObject(true);
        titleObj.put("tag", "markdown");
        titleObj.put("content", "**" + title + "**");
        header.put("title", titleObj);
        header.put("vertical_align", "center");
        JSONObject icon = new JSONObject(true);
        icon.put("tag", "standard_icon");
        icon.put("token", "down-small-ccm_outlined");
        header.put("icon", icon);
        header.put("icon_position", "right");
        header.put("icon_expanded_angle", -180);
        panel.put("header", header);

        JSONArray elements = new JSONArray();
        if (prefix != null) {
            elements.add(markdown(prefix));
        }
        // 出错行只靠块内的 → 标记指示，不在块外另起一行重复：
        // 块外那行会被截断，信息反而不如块内完整
        elements.add(markdown(codeBlock(content, lang, maxChars)));
        panel.put("elements", elements);
        return panel;
    }

    private JSONObject column(JSONObject element) {
        JSONObject col = new JSONObject(true);
        col.put("tag", "column");
        col.put("width", "auto");
        JSONArray elements = new JSONArray();
        elements.add(element);
        col.put("elements", elements);
        return col;
    }

    private JSONObject linkButton(String text, String url) {
        JSONObject btn = new JSONObject(true);
        btn.put("tag", "button");
        JSONObject t = new JSONObject(true);
        t.put("tag", "plain_text");
        t.put("content", text);
        btn.put("text", t);
        btn.put("type", "default");

        JSONObject behavior = new JSONObject(true);
        behavior.put("type", "open_url");
        behavior.put("default_url", url);
        JSONArray behaviors = new JSONArray();
        behaviors.add(behavior);
        btn.put("behaviors", behaviors);
        return btn;
    }

    // ---------------- 基础元素与格式化 ----------------

    private JSONObject markdown(String content) {
        JSONObject el = new JSONObject(true);
        el.put("tag", "markdown");
        el.put("content", content == null ? "" : content);
        return el;
    }

    private JSONObject markdownWithId(String elementId, String content) {
        JSONObject el = markdown(content);
        el.put("element_id", elementId);
        return el;
    }

    private JSONObject divider() {
        JSONObject el = new JSONObject(true);
        el.put("tag", "hr");
        return el;
    }

    /**
     * 包成代码块。
     *
     * <p>内容里若自带 ``` 会提前闭合代码块，把后面的文本变成正文——
     * 堆栈和日志都是外部内容，必须先中和。
     */
    private String codeBlock(String content, String lang, int maxChars) {
        String safe = truncate(content, maxChars).replace("```", "` ` `");
        return "```" + lang + "\n" + safe + "\n```";
    }

    /** markdown 正文里的内容要中和格式字符，否则商品名里的 * 会把整段变成粗体 */
    private String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_");
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n...（已截断，完整内容见详情页）";
    }

    private String severityIcon(String severity) {
        if ("P0".equals(severity) || "P1".equals(severity)) {
            return "🔴";
        }
        if ("P2".equals(severity)) {
            return "🟡";
        }
        return "⚪";
    }

    /**
     * 卡片主色。
     *
     * <p>P1 也用红 —— 推送门槛本来就是 P0/P1，凡是能推到群里的都意味着
     * 「需要有人现在看一眼」。用橙色会让人误以为可以缓一缓。
     */
    private String templateColor(String severity) {
        if ("P0".equals(severity) || "P1".equals(severity)) {
            return "red";
        }
        if ("P2".equals(severity)) {
            return "yellow";
        }
        return "grey";
    }

    private String fmtNum(long n) {
        return String.format("%,d", n);
    }


    private long nz(Long v) {
        return v == null ? 0L : v;
    }

    private String fmtTime(Date d) {
        return d == null ? "-" : new SimpleDateFormat("MM-dd HH:mm").format(d);
    }

    private String nv(String v, String fallback) {
        return notBlank(v) ? v : fallback;
    }

    /**
     * 判空统一走 {@link io.github.liufor02144.erroragent.service.InspectText} ——
     * 这里曾经自己实现一份，不排除字面量 "null"，
     * 结果空指针异常的卡片标题渲染成了 **null**。
     */
    private boolean notBlank(String v) {
        return io.github.liufor02144.erroragent.service.InspectText.notBlank(v);
    }
}
