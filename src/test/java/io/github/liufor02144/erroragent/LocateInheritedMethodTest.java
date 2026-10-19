package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.dto.CodeLocateResult;
import io.github.liufor02144.erroragent.entity.InspectProject;
import io.github.liufor02144.erroragent.service.code.CodeLocator;
import io.github.liufor02144.erroragent.service.code.GitSyncService;
import io.github.liufor02144.erroragent.service.code.InspectProjectService;
import io.github.liufor02144.erroragent.service.code.JavaMethodExtractor;
import io.github.liufor02144.erroragent.service.code.JavaSourceIndex;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

/**
 * 调用链记的是运行时的类，方法可能是从父类继承来的。
 *
 * <p>实例：链路指向 GroupCouponHandler.buildDetailDto，但方法体写在
 * BaseActivityHandler 里。之前只在子类文件里找，找不到方法体也当成「已定位」，
 * 空片段把按堆栈定位到的代码顶掉，AI 只能说「本次未附源码」。
 */
public class LocateInheritedMethodTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private CodeLocator locator;
    private File repo;

    @Before
    public void setUp() throws Exception {
        repo = tmp.newFolder("marketing-service");
        write("biz/src/main/java/com/example/coupon/handler/GroupCouponHandler.java",
                "package com.example.coupon.handler;\n"
                        + "import com.example.coupon.base.BaseActivityHandler;\n"
                        + "public class GroupCouponHandler extends BaseActivityHandler {\n"
                        + "    public String type() {\n"
                        + "        return \"GROUP\";\n"
                        + "    }\n"
                        + "}\n");
        write("biz/src/main/java/com/example/coupon/base/BaseActivityHandler.java",
                "package com.example.coupon.base;\n"
                        + "public abstract class BaseActivityHandler {\n"
                        + "    public Object buildDetailDto(Object activity) {\n"
                        + "        Object limitType = null;\n"
                        + "        return Enum.valueOf(null, (String) limitType);\n"
                        + "    }\n"
                        + "}\n");

        InspectProject project = new InspectProject();
        project.setId(1L);
        project.setRepoName("marketing-service");
        InspectProjectService projects = Mockito.mock(InspectProjectService.class);
        Mockito.when(projects.findByAppId(ArgumentMatchers.anyString())).thenReturn(project);
        Mockito.when(projects.listEnabled()).thenReturn(Collections.singletonList(project));
        GitSyncService git = Mockito.mock(GitSyncService.class);
        Mockito.when(git.cloned(ArgumentMatchers.any())).thenReturn(true);
        Mockito.when(git.repoDir(ArgumentMatchers.any())).thenReturn(repo);
        Mockito.when(git.headCommit(ArgumentMatchers.any())).thenReturn("abc1234");

        locator = new CodeLocator();
        ReflectionTestUtils.setField(locator, "projectService", projects);
        ReflectionTestUtils.setField(locator, "gitSyncService", git);
        ReflectionTestUtils.setField(locator, "sourceIndex", new JavaSourceIndex());
        ReflectionTestUtils.setField(locator, "methodExtractor", new JavaMethodExtractor());
    }

    private void write(String relative, String content) throws Exception {
        File f = new File(repo, relative);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void 子类里没有的方法去父类找() {
        CodeLocateResult r = locator.locateMethod("marketing-service",
                "com.example.coupon.handler.GroupCouponHandler", "buildDetailDto");
        Assert.assertTrue(r.getNote(), r.located());
        Assert.assertTrue(r.getFilePath(), r.getFilePath().endsWith("BaseActivityHandler.java"));
        Assert.assertTrue(r.getSnippet(), r.getSnippet().contains("Enum.valueOf"));
    }

    @Test
    public void 哪里都找不到方法体就算未定位() {
        CodeLocateResult r = locator.locateMethod("marketing-service",
                "com.example.coupon.handler.GroupCouponHandler", "noSuchMethod");
        Assert.assertFalse("找不到方法体不能当成已定位，否则会顶掉按堆栈定位的代码", r.located());
    }
}
