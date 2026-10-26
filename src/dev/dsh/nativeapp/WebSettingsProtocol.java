package dev.dsh.nativeapp;

import java.util.*;

/** Closed UI-only operations. IDs refer only to controls in the current settings document. */
final class WebSettingsProtocol {
    static final String MARKER = "[dsh-settings-ui] ";
    static final List<String> PAGES = Arrays.asList("tasks", "models", "display", "updates", "data", "diagnostics", "files");
    static Map<?, ?> parse(String message) {
        if (message == null || !message.startsWith(MARKER) || message.length() > 70000) return null;
        try {
            Object value = JsonValue.parse(message.substring(MARKER.length()));
            if (!(value instanceof Map)) return null;
            Map<?, ?> data = (Map<?, ?>) value;
            if (!text(data,"id").matches("ws-[0-9]{1,16}-[a-z0-9]{1,12}")
                    || !text(data,"session").matches("[a-f0-9]{32}")) return null;
            String op = text(data,"op");
            Set<String> fields = new HashSet<String>(Arrays.asList("id","session","op"));
            if ("file".equals(op)) {
                if (!Arrays.asList("browse","edit","open","share").contains(text(data,"action"))
                        || text(data,"path").isEmpty() || text(data,"path").length()>4096
                        || text(data,"path").matches("(?s).*[\\x00-\\x1f\\x7f].*")
                        || !Arrays.asList("zh","en").contains(text(data,"locale"))) return null;
                fields.addAll(Arrays.asList("action","path","locale"));
            } else if ("open".equals(op)) {
                if (!PAGES.contains(text(data,"page")) || !Arrays.asList("zh","en").contains(text(data,"locale"))) return null;
                fields.addAll(Arrays.asList("page","locale"));
            } else if (Arrays.asList("act","long","input","read").contains(op)) {
                if (!text(data,"control").matches("v[1-9][0-9]{0,8}")) return null;
                fields.add("control");
                if ("input".equals(op) || "read".equals(op)) {
                    Object offset = data.get("offset");
                    if (!(offset instanceof Number) || ((Number)offset).doubleValue() != ((Number)offset).intValue()
                            || ((Number)offset).intValue() < 0 || ((Number)offset).intValue() > 2*1024*1024) return null;
                    fields.add("offset");
                }
                if ("input".equals(op)) {
                    if (!(data.get("value") instanceof String) || text(data,"value").length() > 32768
                            || !(data.get("done") instanceof Boolean) || !text(data,"ticket").matches("[a-z0-9-]{1,60}")) return null;
                    fields.addAll(Arrays.asList("value","done","ticket"));
                }
            } else if (!Arrays.asList("state","back","close","codebuddy-default").contains(op)) return null;
            if (!fields.equals(data.keySet())) return null;
            return data;
        } catch (IllegalArgumentException invalid) { return null; }
    }
    static String text(Map<?, ?> data, String key) { return data.get(key) instanceof String ? (String)data.get(key) : ""; }
}
