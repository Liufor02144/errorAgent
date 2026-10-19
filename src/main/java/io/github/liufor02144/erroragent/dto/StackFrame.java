package io.github.liufor02144.erroragent.dto;

import lombok.Data;

/**
 * 堆栈中的一帧。
 */
@Data
public class StackFrame {

    /** 类的完整限定名，内部类形如 Outer$Inner */
    private String className;

    /** 方法名。lambda 形如 lambda$realMethod$0 */
    private String methodName;

    /** 源文件名，内部类/lambda 均指向外部类文件 */
    private String fileName;

    private int lineNumber;

    /**
     * lambda 帧的真实宿主方法名。
     *
     * <p>{@code lambda$queryOrders$2} 的宿主是 {@code queryOrders} ——
     * 方法名自校验时必须用宿主名，否则 lambda 帧会全部判为「方法不存在」而降级。
     */
    public String hostMethodName() {
        if (methodName == null) {
            return null;
        }
        if (methodName.startsWith("lambda$")) {
            String[] parts = methodName.split("\\$");
            // lambda$host$0 → host；lambda$null$2（嵌套 lambda）无宿主信息
            if (parts.length >= 2 && !"null".equals(parts[1]) && !parts[1].isEmpty()) {
                return parts[1];
            }
            return null;
        }
        return methodName;
    }

    /** 类名末段，用于定位源文件 */
    public String simpleClassName() {
        if (className == null) {
            return null;
        }
        String s = className;
        int dot = s.lastIndexOf('.');
        if (dot >= 0) {
            s = s.substring(dot + 1);
        }
        int dollar = s.indexOf('$');
        return dollar > 0 ? s.substring(0, dollar) : s;
    }

    /** 包名，用于拼源文件相对路径 */
    public String packageName() {
        if (className == null) {
            return null;
        }
        int dot = className.lastIndexOf('.');
        return dot > 0 ? className.substring(0, dot) : "";
    }
}
