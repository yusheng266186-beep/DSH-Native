package dev.dsh.nativeapp;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 插件启用前的能力披露、授权版本绑定与风险文案。 */
final class PluginPermissions {
    private PluginPermissions() { }

    static List<String> capabilities(String name, boolean builtIn) {
        List<String> out = new ArrayList<String>();
        if ("@deepseek-ai/dsh-schedule".equals(name)) {
            out.add("读取会话与任务信息");
            out.add("创建和执行计划任务");
            out.add("写入 DSH 配置");
        } else if ("@deepseek-ai/dsh-webhook".equals(name)) {
            out.add("访问网络并接收外部请求");
            out.add("读取会话与任务信息");
            out.add("写入 DSH 配置");
        } else {
            out.add("读取和修改当前项目文件");
            out.add("访问网络");
            out.add("执行本机命令和安装脚本");
            out.add("读取 DSH 配置，其中可能包含账号密钥");
            if (builtIn) out.add("随 DSH 服务在后台运行");
        }
        return out;
    }

    static String disclosure(String name, boolean builtIn) {
        StringBuilder out = new StringBuilder();
        out.append("启用后将允许：\n");
        for (String item : capabilities(name, builtIn)) out.append("- ").append(item).append('\n');
        out.append("\nAndroid 无法在同一 DSH 进程内逐项隔离插件权限；"
                + "这里的授权会阻止未确认插件启用，但不是操作系统级沙箱。"
                + "插件版本变化后需要重新授权。");
        return out.toString();
    }

    static String grantKey(String name, String packageJson) {
        String safeName = name == null ? "" : name;
        String version = versionOf(packageJson);
        String source = safeName + "\n" + version + "\n" + (packageJson == null ? "" : packageJson);
        return safeName + "@" + version + "#" + sha256(source).substring(0, 16);
    }

    static String versionOf(String packageJson) {
        if (packageJson == null) return "unknown";
        Matcher m = Pattern.compile("\\\"version\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .matcher(packageJson);
        return m.find() ? m.group(1) : "unknown";
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Throwable t) {
            return String.format("%064x", value.hashCode());
        }
    }
}
