package dev.dsh.nativeapp;
import java.util.Map;

/** A bounded navigation-only bridge; it cannot execute scripts, paths or RPC. */
final class AppSettingsCommands {
    static final String MARKER = "[dsh-native-settings] ";
    final String id, action, locale;
    private AppSettingsCommands(String id, String action, String locale) {
        this.id = id; this.action = action; this.locale = locale;
    }
    static AppSettingsCommands parse(String message) {
        if (message == null || !message.startsWith(MARKER) || message.length() > 512) return null;
        try {
            Object decoded = JsonValue.parse(message.substring(MARKER.length()));
            if (!(decoded instanceof Map)) return null;
            Map<?, ?> data = (Map<?, ?>) decoded;
            if (data.size() != 3) return null;
            Object id = data.get("id"), action = data.get("action"), locale = data.get("locale");
            if (!(id instanceof String) || !((String) id).matches("app-[0-9]{1,16}-[a-z0-9]{1,12}")) return null;
            if (!(action instanceof String) || !("tasks".equals(action) || "models".equals(action)
                    || "display".equals(action) || "updates".equals(action) || "data".equals(action)
                    || "diagnostics".equals(action))) return null;
            if (!"en".equals(locale) && !"zh".equals(locale)) return null;
            return new AppSettingsCommands((String) id, (String) action, (String) locale);
        } catch (IllegalArgumentException malformed) { return null; }
    }
}
