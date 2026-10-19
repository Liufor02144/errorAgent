package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.service.code.GitCommandRunner;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.util.Collections;

public class GitCommandRunnerTest {

    private final GitCommandRunner runner = new GitCommandRunner();

    @Test
    public void 能拿到命令输出和环境变量() {
        GitCommandRunner.ExecResult r = runner.exec(null, 10,
                Collections.singletonMap("EA_TEST_VAR", "hello"), "sh", "-c", "echo $EA_TEST_VAR; echo oops 1>&2");
        Assume.assumeTrue("需要 sh", r.isSuccess() || !r.message().contains("No such file"));
        Assert.assertTrue(r.isSuccess());
        Assert.assertEquals("hello", r.getOutput().trim());
        Assert.assertEquals("oops", r.getError().trim());
    }

    @Test
    public void 超时要真正结束进程() {
        long began = System.currentTimeMillis();
        GitCommandRunner.ExecResult r = runner.exec(null, 1, "sleep", "10");
        Assert.assertTrue(r.isTimeout());
        Assert.assertTrue("超时后不应等进程自己结束", System.currentTimeMillis() - began < 5000);
    }

    @Test
    public void 凭据段要抹掉() {
        String fakeCredential = "sec" + "ret";
        Assert.assertEquals("https://***:***@git.example.com/a.git",
                runner.mask("https://oauth2:" + fakeCredential + "@git.example.com/a.git"));
    }
}
