package dev.dsh.nativeapp;

/** Strict, repeatable integration into the pinned core's own settings shell. */
final class AppSettingsUi {
    private static final String TAG = "/* DSH-ANDROID-APP-SETTINGS-v1 */";
    private static final String END = "/* DSH-ANDROID-APP-SETTINGS-END */";
    private static final String CORE_TAG = "/* DSH-ANDROID-SESSION-TOOLS-v1 */";
    private static final String CORE_END = "/* DSH-ANDROID-SESSION-TOOLS-END */";
    private AppSettingsUi() { }

    static String patchClient(String source, String helper) {
        if (source == null || helper == null || !helper.startsWith(TAG) || !helper.contains(END)) return null;
        String out = strip(source, TAG, END);
        if (out == null) return null;
        out = out.replace("\n            androidInstallAppSettings(ctx);", "");
        String anchor = "const { open, activeId } = useStore((state) => state);";
        String listener = "\n            (0, react.useEffect)(() => {\n"
                + "                const openApp = (event) => {\n"
                + "                    const section = event.detail?.section || 'android-app';\n"
                + "                    if (!['android-app','general','account'].includes(section)) return;\n"
                + "                    event.preventDefault();\n"
                + "                    if (['home','sessions','tasks','models','display','updates','data','diagnostics'].includes(event.detail?.page)) window.__dshNativeSettingsPage = event.detail.page;\n"
                + "                    actions.openSection(section);\n"
                + "                };\n"
                + "                window.addEventListener('dsh-native-app-settings', openApp);\n"
                + "                return () => window.removeEventListener('dsh-native-app-settings', openApp);\n"
                + "            }, [actions]);";
        out = out.replace(listener, "");
        out = once(out, anchor, anchor + listener);
        if (out == null) return null;
        out = out.replace("if (appeared && open && activeId !== \"account\") close();",
                "if (appeared && open) setRequestedOnboarding(void 0);");
        String inject = "\"shortcuts\"\n\t\t];";
        String expanded = "\"shortcuts\", \"uiWorkspace\", \"sessions\", \"workspaces\"\n\t\t];";
        if (!out.contains(expanded)) out = once(out, inject, expanded);
        out = once(out, "function apply(ctx) {", helper.trim() + "\nfunction apply(ctx) {\n            androidInstallAppSettings(ctx);");
        return out;
    }

    static String patchCore(String source, String helper) {
        if (source == null || helper == null || !helper.startsWith(CORE_TAG) || !helper.contains(CORE_END)) return null;
        String out = strip(source, CORE_TAG, CORE_END);
        if (out == null) return null;
        out = out.replace("androidInstallSessionTools(SessionController);\n", "");
        // Filter before the upstream 20-result limit, so hidden hits cannot crowd out visible conversations.
        String visible = "const visibleIds = new Set(visible.filter((record) => record.header.cwd !== void 0).map((record) => record.header.id));";
        String filtered = "const trashIds = await androidReadTrash(this);\n"
                + "            const visibleIds = new Set(visible.filter((record) => record.header.cwd !== void 0 && !trashIds.has(record.header.id)).map((record) => record.header.id));";
        if (!out.contains(filtered)) out = once(out, visible, filtered);
        out = once(out, "export { ApiSessionNotFound, SessionController,",
                "androidInstallSessionTools(SessionController);\nexport { ApiSessionNotFound, SessionController,");
        return out == null ? null : helper.trim() + "\n" + out;
    }

    static String patchSessionClient(String source) {
        final String marker = "/* DSH-ANDROID-RESTORABLE-SESSIONS-v1 */\n";
        if (source == null) return null;
        if (source.startsWith(marker)) return source;
        String out = once(source, "handleRemoved() {", "handleRestored() {\n"
                + "                if (!this.removed) return;\n"
                + "                this.removed = false;\n"
                + "                this.notifier.markDirty();\n"
                + "            }\n            handleRemoved() {");
        out = once(out, "this.mergeSummary(summary);\n\t\t\t\tif (!this.disposed && summary.running)",
                "this.mergeSummary(summary);\n                this.sessions.get(summary.sessionId)?.handleRestored();\n\t\t\t\tif (!this.disposed && summary.running)");
        return out == null ? null : marker + out;
    }

    static String openScript(String page) {
        if (!("sessions".equals(page) || "tasks".equals(page) || "models".equals(page)
                || "display".equals(page) || "updates".equals(page) || "data".equals(page)
                || "diagnostics".equals(page))) page = "home";
        return "!window.dispatchEvent(new CustomEvent('dsh-native-app-settings',{cancelable:true,detail:{page:'" + page + "'}}));";
    }

    private static String strip(String source, String tag, String end) {
        int start = source.indexOf(tag);
        if (start < 0) return source;
        int finish = source.indexOf(end, start);
        if (finish < 0 || source.indexOf(tag, start + tag.length()) >= 0) return null;
        finish += end.length();
        if (finish < source.length() && source.charAt(finish) == '\n') finish++;
        return source.substring(0, start) + source.substring(finish);
    }
    private static String once(String source, String before, String after) {
        if (source == null) return null;
        int at = source.indexOf(before);
        if (at < 0 || source.indexOf(before, at + before.length()) >= 0) return null;
        return source.substring(0, at) + after + source.substring(at + before.length());
    }
}
