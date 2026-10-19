package io.github.liufor02144.erroragent.service.code;

import io.github.liufor02144.erroragent.config.InspectProperties;
import io.github.liufor02144.erroragent.entity.InspectProject;
import io.github.liufor02144.erroragent.mapper.InspectProjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 代码仓库同步。
 *
 * <p>同步策略是 {@code fetch + reset --hard} 而非 {@code pull}：
 * 本地从不修改代码，reset 能彻底消除 merge 冲突导致「静默停留在旧代码」的风险 ——
 * 那种情况下 AI 会基于几周前的代码给出看似合理的结论，且没有任何报错。
 *
 * <p>凭据通过 {@code GIT_CONFIG_*} 环境变量注入一个 {@code http.extraHeader}（需要 git 2.31+），
 * 不拼进 URL：拼进 URL 会出现在进程参数里，同一台机器上 {@code ps} 就能看到。
 */
@Slf4j
@Service
public class GitSyncService {

    /** 克隆超时。首次克隆大仓库较慢，给足时间 */
    private static final int CLONE_TIMEOUT_SECONDS = 600;

    /** 分支名：不允许以 - 开头，否则会被 git 当成命令行选项 */
    private static final Pattern VALID_BRANCH = Pattern.compile("[A-Za-z0-9._][A-Za-z0-9._/-]*");

    @Resource
    private InspectProperties properties;

    @Resource
    private InspectProjectMapper projectMapper;

    @Resource
    private GitCommandRunner git;

    /**
     * 解析本地根目录，并做可写性检查。
     *
     * <p>配置目录不可写时降级到临时目录而不是直接失败 ——
     * 代码是可重新克隆的派生数据，丢了最多多花两分钟，
     * 但因为一个目录权限就让整个巡检停摆不值得。
     */
    public File resolveRoot() {
        String configured = properties.getGit().getLocalPath();
        File dir = new File(configured);
        if (ensureWritable(dir)) {
            return dir;
        }
        File fallback = new File(System.getProperty("java.io.tmpdir"), "inspect-repos");
        log.warn("代码目录 {} 不可写，降级到临时目录 {}（容器重启后需重新克隆）",
                configured, fallback.getAbsolutePath());
        ensureWritable(fallback);
        return fallback;
    }

    private boolean ensureWritable(File dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            return dir.isDirectory() && dir.canWrite();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 仓库的本地目录。
     *
     * <p>目录名由完整仓库路径转换而来：只取末段时，不同 group 下的同名仓库会互相覆盖。
     * 非字母数字字符一律换成下划线，因此不会出现 {@code ..} 这类跳出根目录的名字。
     */
    public File repoDir(InspectProject project) {
        return new File(resolveRoot(), dirName(project.getRepoPath()));
    }

    public static String dirName(String repoPath) {
        String name = (repoPath == null ? "" : repoPath).replace("/", "__").replaceAll("[^A-Za-z0-9_-]", "_");
        return name.isEmpty() ? "_" : name;
    }

    /** 该仓库是否已克隆 */
    public boolean cloned(InspectProject project) {
        File dir = repoDir(project);
        return new File(dir, ".git").exists();
    }

    /**
     * 同步单个仓库：未克隆则克隆，已克隆则 fetch + reset。
     *
     * @return 是否成功
     */
    public boolean sync(InspectProject project) {
        if (!properties.getGit().ready()) {
            log.warn("Git 配置不完整，跳过同步 repo={}", project.getRepoPath());
            return false;
        }
        String invalid = validate(project);
        if (invalid != null) {
            log.warn("仓库配置不合法，跳过同步 repo={}：{}", project.getRepoPath(), invalid);
            projectMapper.markSyncError(project.getId(), invalid);
            return false;
        }
        File dir = repoDir(project);
        try {
            if (!insideRoot(dir)) {
                projectMapper.markSyncError(project.getId(), "本地目录不在代码根目录之内");
                return false;
            }
            boolean ok = cloned(project) ? fetchAndReset(project, dir) : cloneRepo(project, dir);
            if (ok) {
                projectMapper.markSyncSuccess(project.getId(), dir.getAbsolutePath(), new Date());
            }
            return ok;
        } catch (Exception e) {
            String err = git.mask(e.getMessage());
            log.error("仓库同步异常 repo={} err={}", project.getRepoPath(), err);
            projectMapper.markSyncError(project.getId(), truncate(err, 500));
            return false;
        }
    }

    private boolean cloneRepo(InspectProject project, File dir) {
        String branch = branchOf(project);
        log.info("开始克隆 {} 分支 {} → {}", project.getRepoPath(), branch, dir.getAbsolutePath());

        GitCommandRunner.ExecResult r = git.exec(dir.getParentFile(), CLONE_TIMEOUT_SECONDS, authEnv(),
                "git", "clone", "--single-branch", "--depth", String.valueOf(depth()), "--branch", branch,
                plainUrl(project), dir.getAbsolutePath());
        if (!r.isSuccess()) {
            projectMapper.markSyncError(project.getId(), truncate(r.message(), 500));
            return false;
        }
        log.info("克隆完成 {}", project.getRepoPath());
        return true;
    }

    /**
     * fetch 时显式传入仓库地址和分支，用 FETCH_HEAD 而非 origin/branch 做 reset，
     * 不依赖本地 remote 配置是否被改过。
     */
    private boolean fetchAndReset(InspectProject project, File dir) {
        int timeout = properties.getGit().getSyncTimeoutSeconds();
        String branch = branchOf(project);

        GitCommandRunner.ExecResult fetch = git.exec(dir, timeout, authEnv(),
                "git", "fetch", "--prune", "--depth", String.valueOf(depth()), plainUrl(project), branch);
        if (!fetch.isSuccess()) {
            projectMapper.markSyncError(project.getId(), truncate(fetch.message(), 500));
            return false;
        }

        GitCommandRunner.ExecResult reset = git.exec(dir, 60, "git", "reset", "--hard", "FETCH_HEAD");
        if (!reset.isSuccess()) {
            projectMapper.markSyncError(project.getId(), truncate(reset.message(), 500));
            return false;
        }
        return true;
    }

    /** 同步全部启用的仓库，返回成功数 */
    public int syncAll() {
        List<InspectProject> projects = projectMapper.selectEnabled();
        if (projects == null || projects.isEmpty()) {
            log.warn("未配置任何代码仓库，代码定位不可用。请先在 ai_inspect_project 中登记仓库");
            return 0;
        }
        int ok = 0;
        for (InspectProject p : projects) {
            if (sync(p)) {
                ok++;
            }
        }
        log.info("代码仓库同步完成 成功 {}/{}", ok, projects.size());
        return ok;
    }

    /** 当前 HEAD 的 commit，用于在分析结论中标注代码版本 */
    public String headCommit(InspectProject project) {
        GitCommandRunner.ExecResult r = git.exec(repoDir(project), 15,
                "git", "rev-parse", "--short", "HEAD");
        return r.isSuccess() ? r.getOutput().trim() : null;
    }

    private String branchOf(InspectProject project) {
        return project.getBranch() == null || project.getBranch().trim().isEmpty()
                ? "master" : project.getBranch().trim();
    }

    /** 仓库路径与分支校验，不合法时返回原因 */
    private String validate(InspectProject project) {
        String repoPath = project.getRepoPath();
        if (repoPath == null || repoPath.trim().isEmpty() || repoPath.startsWith("-")
                || repoPath.contains("..") || repoPath.contains("\\")) {
            return "仓库路径不合法：" + repoPath;
        }
        if (!VALID_BRANCH.matcher(branchOf(project)).matches()) {
            return "分支名不合法：" + project.getBranch();
        }
        return null;
    }

    private boolean insideRoot(File dir) throws IOException {
        String root = resolveRoot().getCanonicalPath() + File.separator;
        return dir.getCanonicalPath().startsWith(root);
    }

    /**
     * 认证信息走环境变量：等价于 {@code git -c http.extraHeader=...}，但不进进程参数。
     */
    private Map<String, String> authEnv() {
        String user = properties.getGit().getUsername();
        String credential = (user == null || user.trim().isEmpty() ? "oauth2" : user.trim())
                + ":" + properties.getGit().getToken();
        Map<String, String> env = new HashMap<>();
        env.put("GIT_CONFIG_COUNT", "1");
        env.put("GIT_CONFIG_KEY_0", "http.extraHeader");
        env.put("GIT_CONFIG_VALUE_0", "Authorization: Basic "
                + Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8)));
        return env;
    }

    private int depth() {
        return Math.max(1, properties.getGit().getDepth());
    }

    private String plainUrl(InspectProject project) {
        return trimSlash(properties.getGit().getBaseUrl()) + "/" + project.getRepoPath() + ".git";
    }

    private String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String truncate(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() > max ? v.substring(0, max) : v;
    }
}
