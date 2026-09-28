package dev.dsh.nativeapp;

/** ContentProvider 路径分类：纯 Java，无 Android 依赖。 */
final class ProviderRoute {

    static final String AUTHORITY = "dev.dsh.native.updates";
    static final String APK_NAME = "update.apk";

    static final int INVALID = 0;
    static final int UPDATE_APK = 1;
    static final int SHARED_FILE = 2;

    private ProviderRoute() { }

    /**
     * 只接受固定更新路径或明确的分享前缀。
     *
     * <p>未知路径不能回退成更新包，否则 URI 拼写错误也会静默读到 APK，
     * 掩盖调用方 bug，并扩大一次性 URI 授权的含义。</p>
     */
    static int classify(String authority, String encodedPath) {
        if (!AUTHORITY.equals(authority) || encodedPath == null) return INVALID;
        if (("/" + APK_NAME).equals(encodedPath)) return UPDATE_APK;
        if (encodedPath.startsWith("/" + ShareTargets.PREFIX)
                && encodedPath.length() > ShareTargets.PREFIX.length() + 1) {
            return SHARED_FILE;
        }
        return INVALID;
    }
}
