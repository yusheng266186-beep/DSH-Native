package dev.dsh.nativeapp;

import java.util.*;

/** Explicit phone operations and bounded inputs, independent of Android. */
final class PhoneControlPolicy {
    static final List<String> METHODS = Collections.unmodifiableList(Arrays.asList(
            "phone.status", "phone.apps", "phone.observe", "phone.tap", "phone.long_press", "phone.input",
            "phone.swipe", "phone.scroll", "phone.back", "phone.home", "phone.recents", "phone.launch",
            "phone.screenshot", "phone.stop"));
    static final int MAX_NODES = 350, MAX_DEPTH = 24, MAX_TEXT = 4000;
    private PhoneControlPolicy() { }
    static void validate(Map<?, ?> command) {
        String method = NativeBridgePolicy.text(command, "method", 64, true);
        if (!METHODS.contains(method)) throw new IllegalArgumentException("Unknown phone operation");
        Set<String> fields = new HashSet<>(Collections.singletonList("method"));
        if (Arrays.asList("phone.tap", "phone.long_press", "phone.input", "phone.scroll", "phone.swipe").contains(method)) {
            if (!NativeBridgePolicy.text(command, "snapshot", 32, true).matches("[a-f0-9]{16}")) throw new IllegalArgumentException("Invalid screen snapshot");
            fields.add("snapshot");
            if (!method.equals("phone.swipe") && (command.containsKey("node") || method.equals("phone.input") || method.equals("phone.scroll"))) {
                if (!NativeBridgePolicy.text(command, "node", 12, true).matches("n[0-9]{1,4}")) throw new IllegalArgumentException("Invalid screen node");
                fields.add("node");
            } else {
                integer(command, "x", 0, 32767); integer(command, "y", 0, 32767); fields.addAll(Arrays.asList("x", "y"));
            }
            if (method.equals("phone.input")) { NativeBridgePolicy.text(command, "text", MAX_TEXT, false); fields.add("text"); }
            if (method.equals("phone.scroll")) {
                if (!Arrays.asList("forward", "backward").contains(command.get("direction"))) throw new IllegalArgumentException("Invalid scroll direction");
                fields.add("direction");
            }
            if (method.equals("phone.swipe")) {
                integer(command, "endX", 0, 32767); integer(command, "endY", 0, 32767); fields.addAll(Arrays.asList("endX", "endY"));
                if (command.containsKey("durationMs")) { integer(command, "durationMs", 100, 1000); fields.add("durationMs"); }
            }
        } else if (method.equals("phone.launch")) {
            if (!NativeBridgePolicy.text(command, "package", 200, true).matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) throw new IllegalArgumentException("Invalid App package");
            fields.add("package");
        }
        if (!fields.equals(command.keySet())) throw new IllegalArgumentException("Unsupported phone field");
    }
    static int integer(Map<?, ?> command, String key, int low, int high) {
        Object raw = command.get(key);
        if (!(raw instanceof Number)) throw new IllegalArgumentException("Invalid " + key);
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value) || value != (int) value || value < low || value > high) throw new IllegalArgumentException("Invalid " + key);
        return (int) value;
    }
    static boolean systemPackage(String name) {
        return Arrays.asList("dev.dsh.native", "com.android.settings", "com.android.systemui",
                "com.android.permissioncontroller", "com.google.android.permissioncontroller",
                "com.android.packageinstaller", "com.google.android.packageinstaller", "com.miui.securitycenter",
                "com.huawei.systemmanager", "com.vivo.permissionmanager", "com.coloros.safecenter").contains(name);
    }
    static boolean protectedTarget(boolean password, String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return password || value.matches("(?s).*(password|passcode|one.time.code|verification.code|验证码|支付密码|密码|确认支付|立即付款|确认转账|confirm payment|pay now).*" );
    }
}
