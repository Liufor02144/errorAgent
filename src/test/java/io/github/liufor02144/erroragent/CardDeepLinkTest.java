package io.github.liufor02144.erroragent;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 卡片深链必须能真的打开详情页。
 *
 * <p>管理页面是单页应用，全站只有一个路由地址，靠查询参数决定打开什么。
 * 前端只解析 <b>eventId</b> 这一个参数，但卡片曾经拼出两种不同的链接：
 * <ul>
 *   <li>事件卡片 → {@code eventId=}　✅</li>
 *   <li>指纹卡片 → {@code fpId=}　　　❌ 点过去只是列表首页</li>
 * </ul>
 *
 * <p>这种 bug 在本地看不出来 —— 卡片渲染正常、链接也能打开网站，
 * 只是详情没展开，而「没展开」很容易被当成网络慢。
 * 真实后果是：告警推到群里，点进去还得自己在列表里翻，
 * 那按钮就等于没有。
 *
 * <p>同时钉住域名来源：<b>必须能从部署配置读</b>。
 * 测试与生产是两个域名，属于基础设施，不能只放在页面可改的配置表里 ——
 * 那张表按环境各有一份，但容易被同一份初始化脚本刷成同一个值，
 * 结果生产的告警卡片把人引到测试环境。
 */
public class CardDeepLinkTest {

    private static final Path CARD_BUILDER = Paths.get(
            "src/main/java/io/github/liufor02144/erroragent/service/push/InspectCardBuilder.java");
    private static final Path WEB_DASHBOARD = Paths.get(
            "web/src/components/InspectDashboard.vue");

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    public void 卡片拼出的参数前端必须认得() throws IOException {
        String card = read(CARD_BUILDER);

        // 找出卡片里所有 module=InspectDashboard&xxx= 形式的参数名
        Matcher m = Pattern.compile("module=InspectDashboard&(\\w+)=").matcher(card);
        List<String> params = new ArrayList<>();
        while (m.find()) {
            if (!params.contains(m.group(1))) {
                params.add(m.group(1));
            }
        }
        Assert.assertFalse("没找到任何深链，卡片构造可能被改坏了", params.isEmpty());

        if (!Files.exists(WEB_DASHBOARD)) {
            // 前端不在同级目录时跳过交叉校验，但上面的解析仍然有意义
            return;
        }
        String web = read(WEB_DASHBOARD);
        List<String> unsupported = new ArrayList<>();
        for (String p : params) {
            // 前端要么直接解析这个参数名，要么通过通用取参函数处理
            if (!web.contains("'" + p + "'") && !web.contains("\"" + p + "\"")) {
                unsupported.add(p);
            }
        }
        Assert.assertTrue(
                "卡片拼了前端不认的参数 " + unsupported
                        + "：点过去只会停在列表首页，详情不会展开。"
                        + "\n要么把链接改成 eventId=，要么让前端也解析这些参数。",
                unsupported.isEmpty());
    }

    @Test
    public void 域名优先从部署配置读() throws IOException {
        String card = read(CARD_BUILDER);
        Assert.assertTrue(
                "域名必须能从部署配置（InspectProperties.push.detailBaseUrl）读取："
                        + "测试与生产域名不同，只依赖页面配置表会把生产告警引到测试环境",
                card.contains("getPush().getDetailBaseUrl()"));
    }

    @Test
    public void 域名解析只有一处实现() throws IOException {
        String card = read(CARD_BUILDER);
        // 三个链接构造器曾各自读配置、各自去斜杠，改一处漏两处
        int reads = count(card, "PUSH_DETAIL_BASE_URL");
        Assert.assertTrue(
                "域名读取出现了 " + reads + " 处。应收敛到 webBaseUrl() 一处 —— "
                        + "之前三个链接构造器各读一遍配置，改成支持部署配置时漏改了两处",
                reads <= 1);
    }

    private static int count(String s, String sub) {
        int n = 0;
        int i = s.indexOf(sub);
        while (i >= 0) {
            n++;
            i = s.indexOf(sub, i + sub.length());
        }
        return n;
    }
}
