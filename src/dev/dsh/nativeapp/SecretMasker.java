package dev.dsh.nativeapp;

/** 日志与诊断文本脱敏：纯 Java，无 Android 依赖，可离线测试。 */
final class SecretMasker {

    private SecretMasker() { }

    static String mask(String value) {
        if (value == null) return "";
        String out = value;
        out = out.replaceAll(
                "(?i)(authorization[\\\"']?\\s*[:=]\\s*[\\\"']?(?:bearer\\s+)?)[^\\s,;\\\"'}]+",
                "$1***");
        out = out.replaceAll(
                "(?i)((?:api[_-]?key|password|secret|credential)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,;\\\"'}]+",
                "$1***");
        out = out.replaceAll(
                "(?i)(token[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s&;,\\\"'}]+",
                "$1***");
        out = out.replaceAll("sk-[A-Za-z0-9_\\-]{6,}", "sk-***");
        out = out.replaceAll("user_[A-Za-z0-9_\\-]{12,}", "user_***");
        out = out.replaceAll(
                "[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}",
                "***");
        out = out.replaceAll("[A-Za-z0-9_\\-]{40,}", "***");
        return out;
    }
}
