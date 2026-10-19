package io.github.liufor02144.erroragent.service.code;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Java 源文件索引：类名 → 文件路径。
 *
 * <p>一仓多 module 是常态（order-center 下有 order-biz / order-service 等多个
 * module，各有独立的 {@code src/main/java}），所以不能靠单一根路径拼接，必须建索引。
 *
 * <p>索引按仓库缓存。单仓约数千个 java 文件，构建一次几百毫秒，
 * 之后的定位都是内存查找。
 */
@Slf4j
@Component
public class JavaSourceIndex {

    /** 单仓最多索引的文件数，防御异常大仓把内存吃满 */
    private static final int MAX_FILES_PER_REPO = 30000;

    /** repoDir 绝对路径 → (类名简称 → 相对路径列表) */
    private final LoadingCache<String, Map<String, List<String>>> cache = Caffeine.newBuilder()
            .expireAfterWrite(2, TimeUnit.HOURS)
            .maximumSize(64)
            .build(this::buildIndex);

    /** 仓库同步后调用，避免索引停留在旧代码 */
    public void invalidate(File repoDir) {
        cache.invalidate(repoDir.getAbsolutePath());
    }

    /**
     * 按包名 + 类名精确定位（有堆栈路径用）。
     *
     * @return 相对仓库根的路径，未找到返回 null
     */
    public String findByPackage(File repoDir, String packageName, String simpleClassName) {
        List<String> candidates = findBySimpleName(repoDir, simpleClassName);
        if (candidates.isEmpty()) {
            return null;
        }
        if (packageName == null || packageName.isEmpty()) {
            return candidates.get(0);
        }
        String suffix = packageName.replace('.', '/') + "/" + simpleClassName + ".java";
        for (String path : candidates) {
            if (path.replace('\\', '/').endsWith(suffix)) {
                return path;
            }
        }
        return null;
    }

    /**
     * 按类名简称查找全部候选（无堆栈路径用）。
     *
     * <p>无堆栈时只有缩写 logger（如 {@code c.e.o.b.h.OrderHelper}），
     * 拿不到完整包名，只能先按类名找出候选再用首字母校验。
     */
    public List<String> findBySimpleName(File repoDir, String simpleClassName) {
        if (repoDir == null || !repoDir.isDirectory() || simpleClassName == null) {
            return Collections.emptyList();
        }
        Map<String, List<String>> index = cache.get(repoDir.getAbsolutePath());
        if (index == null) {
            return Collections.emptyList();
        }
        List<String> list = index.get(simpleClassName);
        return list == null ? Collections.emptyList() : list;
    }

    /** 读取源文件全部行 */
    public List<String> readLines(File repoDir, String relativePath) {
        try {
            Path path = Paths.get(repoDir.getAbsolutePath(), relativePath);
            if (!Files.exists(path)) {
                return Collections.emptyList();
            }
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 少数文件可能非 UTF-8，退化为忽略错误的读取
            try {
                Path path = Paths.get(repoDir.getAbsolutePath(), relativePath);
                byte[] bytes = Files.readAllBytes(path);
                String text = new String(bytes, StandardCharsets.UTF_8);
                return new ArrayList<>(java.util.Arrays.asList(text.split("\n", -1)));
            } catch (Exception ex) {
                log.warn("读取源文件失败 {} : {}", relativePath, ex.getMessage());
                return Collections.emptyList();
            }
        }
    }

    private Map<String, List<String>> buildIndex(String repoAbsPath) {
        Map<String, List<String>> index = new HashMap<>();
        File repoDir = new File(repoAbsPath);
        if (!repoDir.isDirectory()) {
            return index;
        }
        long began = System.currentTimeMillis();
        final int[] count = {0};
        try {
            Path root = repoDir.toPath();
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    // 跳过构建产物与版本库内部目录：target 里全是编译副本，
                    // 索引到它们会让同名类出现重复候选、干扰包路径校验
                    if (".git".equals(name) || "target".equals(name) || "build".equals(name)
                            || "node_modules".equals(name) || ".idea".equals(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    if (!fileName.endsWith(".java")) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (count[0]++ > MAX_FILES_PER_REPO) {
                        log.warn("仓库 {} 的 java 文件数超过 {}，索引提前结束", repoAbsPath, MAX_FILES_PER_REPO);
                        return FileVisitResult.TERMINATE;
                    }
                    String simple = fileName.substring(0, fileName.length() - 5);
                    String relative = root.relativize(file).toString();
                    index.computeIfAbsent(simple, k -> new ArrayList<>()).add(relative);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            log.error("构建源码索引失败 {}", repoAbsPath, e);
        }
        log.info("源码索引构建完成 repo={} 类={} 文件={} 耗时={}ms",
                repoDir.getName(), index.size(), count[0], System.currentTimeMillis() - began);
        return index;
    }
}
