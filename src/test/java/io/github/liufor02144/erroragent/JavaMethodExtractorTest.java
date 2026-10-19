package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.service.code.JavaMethodExtractor;
import io.github.liufor02144.erroragent.service.code.StackFrameParser;
import io.github.liufor02144.erroragent.dto.StackFrame;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * 方法解析与堆栈解析的验证。
 *
 * <p>其中一组用例跑一份完整的业务类源文件（{@link #FULL_SOURCE}）——
 * 合成的几行代码容易把算法写成「只对自己构造的样子有效」，
 * 而业务代码里的泛型、匿名类、lambda、注释里的花括号才是会让括号计数错乱的东西。
 */
public class JavaMethodExtractorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final JavaMethodExtractor extractor = new JavaMethodExtractor();
    private final StackFrameParser parser = StackFrameParser.forPackages("com.example");

    /** 目标行：validateOrderStatus 里的 log.error */
    private static final String TARGET = "log.error(\"校验订单状态失败 orderDetail:{}\"";

    /**
     * 一个完整的业务类：静态块、嵌套泛型、匿名类、lambda、内部类，
     * 注释、字符串、字符字面量里都有不成对的花括号。
     */
    private static final String FULL_SOURCE = """
            package com.example.order.helper;

            import java.util.ArrayList;
            import java.util.Comparator;
            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;
            import java.util.Optional;
            import java.util.function.Function;

            /**
             * 订单辅助逻辑。注释里的花括号 { 不成对 {{ 也不该影响解析。
             */
            @Slf4j
            @Component
            public class OrderHelper {

                private static final Map<String, List<Integer>> CACHE = new HashMap<>();

                static {
                    CACHE.put("{", new ArrayList<>());
                }

                /** 匿名类字段：里面的 compare 也是一个方法 */
                private final Comparator<String> byLength = new Comparator<String>() {
                    @Override
                    public int compare(String a, String b) {
                        return Integer.compare(a.length(), b.length());
                    }
                };

                @Resource
                private OrderClient orderClient;

                public <T extends Comparable<T>> Map<String, List<Map<String, T>>> group(
                        List<T> items,
                        Function<T, String> keyOf) {
                    Map<String, List<Map<String, T>>> out = new HashMap<>();
                    for (T item : items) {
                        out.computeIfAbsent(keyOf.apply(item), k -> new ArrayList<>())
                                .add(Map.of("item", item));
                    }
                    return out;
                }

                /* 块注释 } } } 结束 */
                public boolean validateOrderStatus(Long orderId, String tenantId) {
                    String detail = null;
                    try {
                        OrderDetail order = orderClient.detail(orderId, tenantId);
                        detail = String.valueOf(order);
                        List<OrderItem> items = Optional.ofNullable(order.getItems()).orElse(new ArrayList<>());
                        items.forEach(it -> {
                            if (it.getStock() == null) {
                                throw new IllegalStateException("库存为空 } " + it.getSkuId());
                            }
                        });
                        char open = '{';
                        return order.getStatus() == 1 && open == '{';
                    } catch (Exception e) {
                        log.error("校验订单状态失败 orderDetail:{}", detail, e);
                        return false;
                    }
                }

                public String describe(Long orderId) {
                    return "订单 {" + orderId + "}";
                }

                static class Holder {
                    private final Runnable task = () -> log.info("holder {}", "}");

                    void run() {
                        task.run();
                    }
                }
            }
            """;

    private List<String> fullSourceLines() throws Exception {
        File file = tmp.newFile("OrderHelper.java");
        Files.write(file.toPath(), FULL_SOURCE.getBytes(StandardCharsets.UTF_8));
        return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    }

    private static int lineOf(List<String> lines, String marker) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(marker)) {
                return i + 1;
            }
        }
        throw new AssertionError("源码里没有目标行：" + marker);
    }

    @Test
    public void 完整源文件_行号应落在正确的方法内() throws Exception {
        List<String> lines = fullSourceLines();
        int target = lineOf(lines, TARGET);

        // 目标行是 log.error("校验订单状态失败 ...")，位于 validateOrderStatus 方法内
        JavaMethodExtractor.MethodRange m = extractor.findMethodAt(lines, target);
        Assert.assertNotNull(target + " 行应能解析出所属方法", m);
        Assert.assertEquals("validateOrderStatus", m.getName());
        Assert.assertTrue("方法范围应包含 " + target + " 行", m.contains(target));

        // 反向：按方法名也应找到同一个方法
        JavaMethodExtractor.MethodRange byName = extractor.findMethodByName(lines, "validateOrderStatus");
        Assert.assertNotNull(byName);
        Assert.assertEquals(m.getStartLine(), byName.getStartLine());
    }

    @Test
    public void 完整源文件_方法解析数量应合理() throws Exception {
        List<String> lines = fullSourceLines();
        List<JavaMethodExtractor.MethodRange> methods = extractor.parseMethods(lines);

        Assert.assertFalse("应解析出方法", methods.isEmpty());
        for (JavaMethodExtractor.MethodRange m : methods) {
            Assert.assertTrue("方法起止行应有效: " + m.getName(), m.getEndLine() >= m.getStartLine());
            Assert.assertFalse("方法名不应是控制关键字: " + m.getName(),
                    Arrays.asList("if", "for", "while", "catch", "switch", "try").contains(m.getName()));
        }
    }

    @Test
    public void 注释与字符串中的花括号不应影响方法边界() {
        List<String> lines = Arrays.asList(
                "public class Demo {",                       // 1
                "    // 注释里的花括号 { { {",                  // 2
                "    /* 块注释 } } } */",                      // 3
                "    public void foo() {",                    // 4
                "        String s = \"字符串里的 } 花括号\";",   // 5
                "        char c = '{';",                      // 6
                "        log.error(\"boom\");",               // 7
                "    }",                                      // 8
                "    public void bar() {",                    // 9
                "        return;",                            // 10
                "    }",                                      // 11
                "}"                                           // 12
        );
        JavaMethodExtractor.MethodRange atSeven = extractor.findMethodAt(lines, 7);
        Assert.assertNotNull(atSeven);
        Assert.assertEquals("foo", atSeven.getName());

        JavaMethodExtractor.MethodRange atTen = extractor.findMethodAt(lines, 10);
        Assert.assertNotNull(atTen);
        Assert.assertEquals("bar", atTen.getName());
    }

    @Test
    public void 堆栈应取第一个业务栈帧而非栈顶() {
        String stack = "java.io.IOException: No such file or directory\n"
                + "\tat java.io.UnixFileSystem.createFileExclusively(Native Method)\n"
                + "\tat com.xxl.job.core.log.XxlJobFileAppender.appendLog(XxlJobFileAppender.java:104)\n"
                + "\tat com.example.order.util.JobLogUtil.info(JobLogUtil.java:17)\n"
                + "\tat com.example.order.job.OrderCompensationJob"
                + ".queryOrdersByTenant(OrderCompensationJob.java:400)\n";

        StackFrame frame = parser.firstBizFrame(stack);
        Assert.assertNotNull(frame);
        // 栈顶是 JDK 与 xxl-job，第一个业务帧应是 JobLogUtil
        Assert.assertEquals("com.example.order.util.JobLogUtil", frame.getClassName());
        Assert.assertEquals("info", frame.getMethodName());
        Assert.assertEquals("JobLogUtil.java", frame.getFileName());
        Assert.assertEquals(17, frame.getLineNumber());
    }

    @Test
    public void lambda帧应还原出宿主方法名() {
        String stack = "\tat com.example.order.job.OrderCompensationJob"
                + ".lambda$null$2(OrderCompensationJob.java:332)\n"
                + "\tat com.example.order.job.OrderCompensationJob"
                + ".lambda$queryOrders$3(OrderCompensationJob.java:340)\n";

        // lambda$null$2 拿不到宿主名，应跳过并取下一帧
        StackFrame frame = parser.firstBizFrame(stack);
        Assert.assertNotNull(frame);
        Assert.assertEquals("queryOrders", frame.hostMethodName());
    }

    @Test
    public void 内部类应映射到外部类文件() {
        String stack = "\tat com.example.common.trace.MDCTaskDecorator$1"
                + ".run(MDCTaskDecorator.java:22)\n";
        StackFrame frame = parser.firstBizFrame(stack);
        Assert.assertNotNull(frame);
        Assert.assertEquals("MDCTaskDecorator", frame.simpleClassName());
        Assert.assertEquals("com.example.common.trace", frame.packageName());
    }

    // ================= 以下场景是换用 AST 的直接动因 =================
    // 早期「括号计数 + 正则」实现在这些情形下会静默给出错误结果，
    // 而错误的代码片段喂给 AI 就等于错误的根因。

    @Test
    public void 跨行方法签名应能识别() {
        // 旧实现要求 '{' 与方法名同行，长参数列表换行后整个方法会被漏掉
        List<String> lines = Arrays.asList(
                "public class Demo {",                  // 1
                "    public void cancelDelivery(",      // 2
                "            Long orderId,",            // 3
                "            String reason) {",         // 4
                "        log.error(\"boom\");",         // 5
                "    }",                                // 6
                "}"                                     // 7
        );
        JavaMethodExtractor.MethodRange m = extractor.findMethodAt(lines, 5);
        Assert.assertNotNull("跨行签名的方法应被识别", m);
        Assert.assertEquals("cancelDelivery", m.getName());
    }

    @Test
    public void 重载方法应全部返回且签名可区分() {
        // 旧实现的 findMethodByName 只返回第一个，可能正是错的那个重载
        List<String> lines = Arrays.asList(
                "public class Demo {",                      // 1
                "    public void cancel(Long id) {",        // 2
                "        log.error(\"a\");",                // 3
                "    }",                                    // 4
                "    public void cancel(Long id, String reason) {",  // 5
                "        log.error(\"b\");",                // 6
                "    }",                                    // 7
                "}"                                         // 8
        );
        List<JavaMethodExtractor.MethodRange> all = extractor.findMethodsByName(lines, "cancel");
        Assert.assertEquals("应返回全部重载", 2, all.size());
        Assert.assertNotEquals("签名应可区分重载",
                all.get(0).getSignature(), all.get(1).getSignature());

        // 行号有效时仍应精确命中对应的那个重载
        Assert.assertEquals(5, extractor.findMethodAt(lines, 6).getStartLine());
    }

    @Test
    public void 复杂泛型返回值不应导致漏识别() {
        List<String> lines = Arrays.asList(
                "public class Demo {",                                          // 1
                "    public Map<String, List<Map<String, Object>>> query() {",   // 2
                "        log.error(\"boom\");",                                 // 3
                "    }",                                                        // 4
                "}"                                                             // 5
        );
        JavaMethodExtractor.MethodRange m = extractor.findMethodAt(lines, 3);
        Assert.assertNotNull("嵌套泛型返回值的方法应被识别", m);
        Assert.assertEquals("query", m.getName());
    }

    @Test
    public void 匿名内部类中的行应归属最内层方法() {
        // 异常实际发生在匿名类的 run() 里，归到外层 submit() 会给出错误的代码片段
        List<String> lines = Arrays.asList(
                "public class Demo {",                          // 1
                "    public void submit() {",                   // 2
                "        executor.execute(new Runnable() {",    // 3
                "            @Override",                        // 4
                "            public void run() {",              // 5
                "                log.error(\"boom\");",         // 6
                "            }",                                // 7
                "        });",                                  // 8
                "    }",                                        // 9
                "}"                                             // 10
        );
        JavaMethodExtractor.MethodRange m = extractor.findMethodAt(lines, 6);
        Assert.assertNotNull(m);
        Assert.assertEquals("应取范围最小的方法，即匿名类的 run", "run", m.getName());
    }
}
