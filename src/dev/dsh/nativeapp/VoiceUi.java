package dev.dsh.nativeapp;

import java.util.Map;

/** Scoped voice protocol and adapter for the shipped plugin's actual slots. */
final class VoiceUi {
    static final String MARKER = "[dsh-voice] ";
    private static final String START = "/* DSH-ANDROID-VOICE-v1 */";
    private static final String END = "/* DSH-ANDROID-VOICE-END */";
    private VoiceUi() { }

    static final class Command {
        final String id, action, language, engine;
        Command(String id, String action, String language) {
            this(id, action, language, "auto");
        }
        Command(String id, String action, String language, String engine) {
            this.id = id; this.action = action; this.language = language; this.engine = engine;
        }
    }

    static Command parse(String message) {
        if (message == null || !message.startsWith(MARKER) || message.length() > 768) return null;
        try {
            Map<?, ?> value = (Map<?, ?>) JsonValue.parse(message.substring(MARKER.length()));
            String id = (String) value.get("id"), action = (String) value.get("action");
            String language = value.get("language") instanceof String ? (String) value.get("language") : "zh-CN";
            String engine = value.get("engine") instanceof String ? (String) value.get("engine") : "auto";
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,80}") || action == null
                    || !action.matches("start|start-window|engine|stop|cancel|check|settings")
                    || engine.length() > 240 || !engine.matches("auto|[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")
                    || !language.matches("[a-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}")) return null;
            return new Command(id, action, language, engine);
        } catch (RuntimeException invalid) { return null; }
    }

    static String bridgeScript() {
        return "window.__dshNativeVoice=1;window.dispatchEvent(new Event('dsh-native-voice-ready'));";
    }

    static String patch(String source, String helper) {
        if (source == null || helper == null || !helper.startsWith(START) || !helper.contains(END)) return null;
        String out = source;
        if (out.contains(START)) {
            int start = out.indexOf(START), end = out.indexOf(END, start);
            if (end < 0) return null;
            String tail = out.substring(end + END.length());
            if (tail.startsWith("\n")) tail = tail.substring(1);
            out = out.substring(0, start) + tail;
            out = out.replace("}, DshVoiceInput));", "}, VoiceInput));")
                    .replace("}, DshVoicePreparation));", "}, VoicePreparation));")
                    .replace("}, DshVoiceSetupPrompt));", "}, VoiceSetupPrompt));");
        }
        out = once(out, "function registerUi(ctx) {", helper.trim() + "\nfunction registerUi(ctx) {");
        out = once(out, "}, VoiceInput));", "}, DshVoiceInput));");
        out = once(out, "}, VoicePreparation));", "}, DshVoicePreparation));");
        return once(out, "}, VoiceSetupPrompt));", "}, DshVoiceSetupPrompt));");
    }

    private static String once(String source, String before, String after) {
        if (source == null) return null;
        int at = source.indexOf(before);
        if (at < 0 || source.indexOf(before, at + before.length()) >= 0) return null;
        return source.substring(0, at) + after + source.substring(at + before.length());
    }
}
