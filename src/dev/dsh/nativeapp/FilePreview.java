package dev.dsh.nativeapp;

import java.util.Locale;

/** 文件预览类型判定。纯 Java，避免让图片误入文本编辑器。 */
final class FilePreview {
    static final int TEXT = 0;
    static final int IMAGE = 1;
    static final int OTHER = 2;
    static final long MAX_IMAGE_BYTES = 64L * 1024 * 1024;

    private FilePreview() { }

    static int kind(String name) {
        if (name == null) return OTHER;
        String n = name.toLowerCase(Locale.ROOT);
        if (ends(n, ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp")) return IMAGE;
        if (ends(n, ".txt", ".md", ".json", ".jsonl", ".yaml", ".yml", ".xml",
                ".csv", ".tsv", ".log", ".java", ".kt", ".js", ".ts", ".tsx",
                ".jsx", ".py", ".sh", ".css", ".html", ".htm", ".ini", ".conf",
                ".toml", ".properties", ".gradle", ".sql", ".svg")) return TEXT;
        return OTHER;
    }

    static boolean canDecodeImage(long bytes) {
        return bytes > 0 && bytes <= MAX_IMAGE_BYTES;
    }

    private static boolean ends(String value, String... suffixes) {
        for (String suffix : suffixes) if (value.endsWith(suffix)) return true;
        return false;
    }
}
