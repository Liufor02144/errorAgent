package io.github.liufor02144.erroragent.service.code;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 系统 git 命令执行器。
 *
 * <p>不引入 JGit 的原因：仅为几条 git 操作增加一个依赖不划算，且 JGit 不支持浅克隆。
 *
 * <p><b>凭据安全</b>：token 通过环境变量传给 git（见 {@code GitSyncService}），不出现在进程参数和
 * {@code .git/config} 里。日志输出前仍用 {@link #mask} 兜底抹掉 URL 中可能出现的凭据段。
 *
 * <p><b>输出读取</b>：stdout、stderr 重定向到临时文件，进程结束后再读。边跑边读时，
 * 先把一个流读到 EOF 再等进程，超时形同虚设；两个流串行读还可能因管道缓冲区写满而互相卡死。
 */
@Slf4j
@Component
public class GitCommandRunner {

    /** 匹配 URL 中的凭据段 {@code //user:token@} */
    private static final String CREDENTIAL_PATTERN = "//[^/@\\s]+:[^/@\\s]+@";

    @Data
    public static class ExecResult {
        private boolean success;
        private int exitCode;
        private String output;
        private String error;
        private boolean timeout;

        public String message() {
            if (timeout) {
                return "命令执行超时";
            }
            String e = error == null ? "" : error.trim();
            return e.isEmpty() ? ("exit=" + exitCode) : e;
        }
    }

    /** git 是否可用。不可用时代码定位整体失效，需在启动自检中暴露 */
    public boolean gitAvailable() {
        ExecResult r = exec(null, 10, "git", "--version");
        if (r.isSuccess()) {
            log.info("git 可用：{}", r.getOutput().trim());
            return true;
        }
        log.error("系统 git 不可用，代码定位功能将无法工作：{}", r.message());
        return false;
    }

    /**
     * 执行 git 命令。
     *
     * @param workDir        工作目录，null 表示当前目录
     * @param timeoutSeconds 超时秒数，超时强制销毁进程
     */
    public ExecResult exec(File workDir, int timeoutSeconds, String... command) {
        return exec(workDir, timeoutSeconds, Collections.emptyMap(), command);
    }

    /**
     * 执行 git 命令，附加环境变量（用于传递凭据，不进进程参数）。
     */
    public ExecResult exec(File workDir, int timeoutSeconds, Map<String, String> env, String... command) {
        ExecResult result = new ExecResult();
        Process process = null;
        File out = null;
        File err = null;
        try {
            out = File.createTempFile("git-out", ".log");
            err = File.createTempFile("git-err", ".log");
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workDir != null) {
                pb.directory(workDir);
            }
            // 禁止 git 弹交互式凭据框：无凭据时应立即失败，而不是让进程挂起到超时
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.environment().put("GIT_ASKPASS", "echo");
            pb.environment().putAll(env);
            pb.redirectOutput(out);
            pb.redirectError(err);

            process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.setTimeout(true);
                result.setSuccess(false);
                log.warn("git 命令超时({}s)：{}", timeoutSeconds, mask(join(command)));
                return result;
            }
            String stdout = read(out);
            String stderr = read(err);
            result.setExitCode(process.exitValue());
            result.setSuccess(process.exitValue() == 0);
            result.setOutput(stdout);
            result.setError(stderr);
            if (!result.isSuccess()) {
                log.warn("git 命令失败 exit={} cmd={} err={}",
                        result.getExitCode(), mask(join(command)), mask(stderr));
            }
            return result;
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            result.setSuccess(false);
            result.setError(e.getMessage());
            log.warn("git 命令异常 cmd={} err={}", mask(join(command)), e.getMessage());
            return result;
        } finally {
            deleteQuietly(out);
            deleteQuietly(err);
        }
    }

    /** 输出最多保留 20000 字符 */
    private String read(File file) {
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return text.length() > 20000 ? text.substring(0, 20000) : text;
        } catch (Exception e) {
            // 读取失败不影响退出码判断
            return "";
        }
    }

    private void deleteQuietly(File file) {
        if (file != null && !file.delete()) {
            file.deleteOnExit();
        }
    }

    private String join(String... command) {
        List<String> list = new ArrayList<>(Arrays.asList(command));
        return String.join(" ", list);
    }

    /** 抹掉 URL 中的凭据，任何对外输出（日志、错误信息、页面）都必须先过这一层 */
    public String mask(String text) {
        return text == null ? null : text.replaceAll(CREDENTIAL_PATTERN, "//***:***@");
    }
}
