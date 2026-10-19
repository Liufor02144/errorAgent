package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.config.InspectProperties;
import io.github.liufor02144.erroragent.service.code.GitSyncService;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.regex.Matcher;

public class NamingTest {

    private InspectProperties.Naming naming(String packages, String strip) {
        InspectProperties.Naming n = new InspectProperties.Naming();
        n.setAppPackages(packages);
        n.setStripPrefixes(strip);
        return n;
    }

    @Test
    public void 非法包名被忽略() {
        InspectProperties.Naming n = naming("com.example, bad'pkg, org.acme., x)y", null);
        Assert.assertEquals(Arrays.asList("com.example", "org.acme"), n.packageList());
    }

    @Test
    public void logger缩写和全名都算业务代码() {
        InspectProperties.Naming n = naming("com.example.order", null);
        Assert.assertTrue(n.isBizLogger("c.e.o.OrderService"));
        Assert.assertTrue(n.isBizLogger("com.example.order.OrderService"));
        Assert.assertFalse(n.isBizLogger("o.s.w.s.DispatcherServlet"));
        Assert.assertFalse(n.isBizLogger(null));
    }

    @Test
    public void 栈帧正则只认业务包() {
        Matcher m = naming("com.example", null).javaFramePattern().matcher(
                "\tat org.springframework.Foo.bar(Foo.java:1)\n\tat com.example.order.OrderService.cancel(OrderService.java:57)");
        Assert.assertTrue(m.find());
        Assert.assertEquals("com.example.order.OrderService", m.group(1));
        Assert.assertEquals("cancel", m.group(2));
        Assert.assertEquals("57", m.group(4));
        Assert.assertNull("未配置业务包时没有栈帧正则", naming("", null).javaFramePattern());
    }

    @Test
    public void 展示服务名去掉配置的前缀() {
        InspectProperties.Naming n = naming(null, "acme-, svc-");
        Assert.assertEquals("order-service", n.displayApp("acme-order-service"));
        Assert.assertEquals("pay", n.displayApp("svc-pay"));
        Assert.assertEquals("other", n.displayApp("other"));
    }

    @Test
    public void 仓库本地目录名不会重名也不会跳出根目录() {
        Assert.assertEquals("group-a__order", GitSyncService.dirName("group-a/order"));
        Assert.assertNotEquals(GitSyncService.dirName("group-a/order"), GitSyncService.dirName("group-b/order"));
        Assert.assertFalse(GitSyncService.dirName("../../etc").contains(".."));
    }
}
