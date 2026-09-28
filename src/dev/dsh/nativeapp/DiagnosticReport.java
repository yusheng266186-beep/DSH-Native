package dev.dsh.nativeapp;

/** 诊断摘要生成器：纯 Java，无 Android 依赖。 */
final class DiagnosticReport {
    static final int MAX_VALUE_CHARS = 8192;

    private DiagnosticReport() { }

    static final class Builder {
        private final StringBuilder out = new StringBuilder();

        Builder title(String value) {
            out.append(clean(value)).append('\n');
            return this;
        }

        Builder section(String value) {
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
            out.append("\n[").append(clean(value)).append("]\n");
            return this;
        }

        Builder add(String key, Object value) {
            out.append(clean(key)).append(": ")
                    .append(clean(value == null ? "" : String.valueOf(value))).append('\n');
            return this;
        }

        String build() {
            return SecretMasker.mask(out.toString());
        }
    }

    static String clean(String value) {
        if (value == null) return "";
        StringBuilder safe = new StringBuilder();
        int limit = Math.min(value.length(), MAX_VALUE_CHARS);
        for (int i = 0; i < limit; i++) {
            char c = value.charAt(i);
            if (c == '\r') continue;
            if (c == '\n' || c == '\t' || c >= 0x20) safe.append(c);
            else safe.append('?');
        }
        if (value.length() > MAX_VALUE_CHARS) safe.append("...[truncated]");
        return safe.toString();
    }
}
