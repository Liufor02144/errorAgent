package io.github.liufor02144.erroragent.config;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 巡检部署配置：凭据、地址与命名约定，写在 application.yml 或环境变量里，重启生效。
 *
 * <p>调优参数（突增倍数、量级门槛、推送上限等）不在这里 —— 它们在 {@code ai_inspect_config} 表，
 * 由配置页管理，改完即时生效。划分依据：冷启动期要天天调的参数走页面，部署期定死的地址走配置文件。
 *
 * <p>急停开关见 {@link InspectSwitch}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "erroragent")
public class InspectProperties {

    private Naming naming = new Naming();
    private Sls sls = new Sls();
    private Git git = new Git();
    private Llm llm = new Llm();
    private Push push = new Push();

    /**
     * 命名约定：哪些代码算业务代码、服务名怎么展示。
     *
     * <p>业务包前缀决定「首个业务栈帧」—— 指纹按它聚合、代码定位按它找源码。
     * 不能用栈顶：栈顶大量是 JDK、连接池、任务框架的代码，会把不同业务问题聚成一个指纹。
     */
    @Slf4j
    @Data
    public static class Naming {

        /** 包名只允许字母、数字、下划线和点：它会拼进 SLS 查询里的正则 */
        private static final Pattern VALID_PACKAGE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");

        /** 业务代码包前缀，逗号分隔，如 {@code com.example,org.acme} */
        private String appPackages;

        /** 展示服务名时去掉的前缀，逗号分隔，如 {@code mycompany-} */
        private String stripPrefixes;

        @Getter(AccessLevel.NONE)
        @Setter(AccessLevel.NONE)
        private transient List<String> packagesCache;
        @Getter(AccessLevel.NONE)
        @Setter(AccessLevel.NONE)
        private transient String packagesRaw;
        @Getter(AccessLevel.NONE)
        @Setter(AccessLevel.NONE)
        private transient Pattern framePatternCache;

        /** 合法的业务包前缀列表；非法项打 WARN 后忽略 */
        public List<String> packageList() {
            if (packagesCache == null || !String.valueOf(appPackages).equals(packagesRaw)) {
                List<String> valid = new ArrayList<>();
                for (String p : splitTrim(appPackages)) {
                    String trimmed = p.endsWith(".") ? p.substring(0, p.length() - 1) : p;
                    if (VALID_PACKAGE.matcher(trimmed).matches()) {
                        valid.add(trimmed);
                    } else {
                        log.warn("忽略非法的业务包前缀：{}", p);
                    }
                }
                packagesRaw = String.valueOf(appPackages);
                packagesCache = valid;
                framePatternCache = null;
            }
            return packagesCache;
        }

        public boolean ready() {
            return !packageList().isEmpty();
        }

        /** 类全名是否属于业务代码 */
        public boolean isBizClass(String className) {
            if (className == null) {
                return false;
            }
            for (String p : packageList()) {
                if (className.startsWith(p + ".")) {
                    return true;
                }
            }
            return false;
        }

        /**
         * logger 名是否属于业务代码。
         *
         * <p>logback 会把包名压缩成首字母缩写（{@code com.example.order.OrderService} →
         * {@code c.e.o.OrderService}），全名和缩写两种形式都要认。
         */
        public boolean isBizLogger(String loggerClass) {
            if (loggerClass == null) {
                return false;
            }
            String lc = loggerClass.trim();
            for (String p : packageList()) {
                if (lc.startsWith(p + ".") || lc.startsWith(abbreviate(p) + ".")) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Java 侧的业务栈帧正则：group(1) 类全名、group(2) 方法名、group(3) 文件名、group(4) 行号。
         *
         * @return 未配置业务包时返回 null
         */
        public Pattern javaFramePattern() {
            List<String> packages = packageList();
            if (packages.isEmpty()) {
                return null;
            }
            if (framePatternCache == null) {
                framePatternCache = Pattern.compile("at\\s+((?:" + alternation(packages)
                        + ")\\.[\\w.$]+)\\.([\\w$<>]+)\\(([\\w$]+\\.java):(\\d+)\\)");
            }
            return framePatternCache;
        }

        /**
         * SLS 侧的业务栈帧正则（RE2 语法）：group(2) 为 {@code File.java:行号}。
         *
         * @return 未配置业务包时返回 null
         */
        public String slsFrameRegex() {
            List<String> packages = packageList();
            if (packages.isEmpty()) {
                return null;
            }
            return "at ((?:" + alternation(packages) + ")\\.[\\w.$]+)\\((\\w+\\.java:\\d+)\\)";
        }

        /** 展示用的服务名：去掉配置的前缀 */
        public String displayApp(String appId) {
            if (appId == null) {
                return null;
            }
            for (String prefix : splitTrim(stripPrefixes)) {
                if (appId.startsWith(prefix) && appId.length() > prefix.length()) {
                    return appId.substring(prefix.length());
                }
            }
            return appId;
        }

        private static String alternation(List<String> packages) {
            StringBuilder sb = new StringBuilder();
            for (String p : packages) {
                if (sb.length() > 0) {
                    sb.append('|');
                }
                sb.append(p.replace(".", "\\."));
            }
            return sb.toString();
        }

        private static String abbreviate(String pkg) {
            StringBuilder sb = new StringBuilder();
            for (String seg : pkg.split("\\.")) {
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(seg.charAt(0));
            }
            return sb.toString();
        }
    }

    /** SLS 日志源 */
    @Data
    public static class Sls {
        /** 服务入口。与 SLS 同 region 部署时用 *-intranet.log.aliyuncs.com（读流量免费） */
        private String endpoint;
        private String project;
        private String logstore;
        private String accessKeyId;
        private String accessKeySecret;

        /** 采集范围：只采集容器名以此开头的日志，为空表示不限 */
        private String containerPrefix = "";

        /** 采集范围：前缀之外需要额外纳入的 app_id，逗号分隔 */
        private String extraAppIds;

        public boolean ready() {
            return notBlank(endpoint) && notBlank(project) && notBlank(logstore)
                    && notBlank(accessKeyId) && notBlank(accessKeySecret);
        }

        /** 额外 app_id 列表 */
        public List<String> extraAppIdList() {
            return splitTrim(extraAppIds);
        }
    }

    /** Git 代码源 */
    @Data
    public static class Git {
        /** 代码托管地址，如 {@code https://git.example.com} */
        private String baseUrl;

        /** HTTP 认证用户名：GitLab 用 oauth2，GitHub 用 x-access-token */
        private String username = "oauth2";

        /** 只读访问令牌，只需读仓库权限 */
        private String token;

        /**
         * 本地克隆根目录。需持久化，单仓浅克隆约几 MB。
         * 目录不可写时代码层会降级到临时目录并打 WARN —— 丢了能自动重新 clone。
         */
        private String localPath = "./data/repos";

        /** 浅克隆深度。不需要 git 历史，depth=1 省磁盘 */
        private int depth = 1;

        /** 代码同步 cron，默认每日 03:00 */
        private String syncCron = "0 0 3 * * ?";

        /** 单仓库同步超时（秒） */
        private int syncTimeoutSeconds = 300;

        public boolean ready() {
            return notBlank(baseUrl) && notBlank(token);
        }
    }

    /** LLM 通道 */
    @Data
    public static class Llm {
        /** OpenAI 兼容端点 */
        private String baseUrl;
        private String apiKey;
        private String model;

        /** 发给大模型前是否脱敏（手机号、身份证号、邮箱、常见密钥格式） */
        private boolean redact = true;

        public boolean ready() {
            return notBlank(baseUrl) && notBlank(apiKey) && notBlank(model);
        }
    }

    /**
     * 飞书推送（应用机器人）。只出不进 —— 卡片发出去，标注在管理页面完成。
     *
     * <p>不收回调，因此不需要 Encrypt Key / Verification Token：
     * 那两项是给「飞书主动打到我们地址」用的，而服务不需要公网入口。
     */
    @Data
    public static class Push {
        private String appId;
        private String appSecret;

        /** 目标群，oc_ 开头 */
        private String chatId;

        /**
         * 管理页面的访问地址，卡片上「去处理」按钮的跳转前缀。
         *
         * <p>放在部署配置而不是配置表里：测试环境和生产环境是两个不同的地址，
         * 配置表却容易被同一份初始化脚本刷成同一个值。地址属于基础设施，和凭据、群 ID 同级。
         *
         * <p>不带末尾斜杠，例如 {@code https://erroragent.example.com}。
         */
        private String detailBaseUrl;

        public boolean ready() {
            return notBlank(appId) && notBlank(appSecret) && notBlank(chatId);
        }
    }

    private static boolean notBlank(String v) {
        return v != null && !v.trim().isEmpty();
    }

    private static List<String> splitTrim(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (String item : Arrays.asList(raw.split(","))) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
