package dev.dsh.nativeapp;

/** 稳定版与测试版更新通道规则。 */
final class ReleaseChannel {
    static final String STABLE = "stable";
    static final String TEST = "test";

    private ReleaseChannel() { }

    static String normalize(String value) {
        return TEST.equalsIgnoreCase(value == null ? "" : value.trim()) ? TEST : STABLE;
    }

    static String manifest(String channel) {
        return TEST.equals(normalize(channel)) ? "latest-test.json" : "latest.json";
    }

    static String tag(String version, String channel) {
        return "v" + version + (TEST.equals(normalize(channel)) ? "-test" : "-bootstrap");
    }

    static String label(String channel) {
        return TEST.equals(normalize(channel)) ? "测试版" : "稳定版";
    }
}
