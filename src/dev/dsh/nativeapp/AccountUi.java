package dev.dsh.nativeapp;

/** Keep the shipped account components, adapting only their platform boundary. */
final class AccountUi {
    private static final String TAG = "/* DSH-ANDROID-ACCOUNT-UI-v1 */\n";
    private static final String EVENT = "dsh-native-account-settings";
    private AccountUi() { }

    static String patchAccount(String source) {
        if (source == null) return null;
        if (source.startsWith(TAG)) return source;
        String out = once(source, "if (!(\"dshDesktop\" in globalThis)) return;",
                "// Android uses the real account UI without a Desktop bridge.");
        out = once(out, "window.location.origin, \"desktop\")).ok", "window.location.origin, \"web\")).ok");
        out = once(out, "if (snapshot.view?.status === \"credential-stored\") unregister ??= ctx.slots.register({",
                "if (snapshot.view !== void 0) unregister ??= ctx.slots.register({");
        String anchor = "const attempt = account.view?.attempt;";
        out = once(out, anchor, anchor + "\n"
                + "            const opened = (0, react.useRef)(void 0);\n"
                + "            const openBrowser = () => {\n"
                + "                if (attempt?.phase === \"waiting-browser\" && attempt.authorizeUrl)\n"
                + "                    window.open(authorizeUrlWithTheme(attempt.authorizeUrl, colorScheme), \"_blank\", \"noopener,noreferrer\");\n"
                + "            };\n"
                + "            (0, react.useEffect)(() => {\n"
                + "                if (attempt?.phase !== \"waiting-browser\" || !attempt.authorizeUrl || document.visibilityState !== \"visible\" || opened.current === attempt.id) return;\n"
                + "                opened.current = attempt.id; openBrowser();\n"
                + "            }, [attempt?.id, attempt?.phase, attempt?.authorizeUrl, colorScheme]);");
        out = once(out, "disabled: busy || committing || waiting || account.view === void 0,",
                "disabled: busy || committing || account.view === void 0,");
        out = once(out, "\"aria-label\": waiting ? t(\"waiting\") : void 0,",
                "\"aria-label\": waiting ? t(\"open\") : void 0,");
        out = once(out, "run(retry);", "if (waiting) openBrowser(); else run(retry);");
        out = once(out, "children: waiting ? (0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.IconLoadingOutlineRegular, { className: SignInDialog_module_css_default.spinner }) : t(expired || error ? \"retry\" : \"signIn\")",
                "children: waiting ? t(\"open\") : t(expired || error ? \"retry\" : \"signIn\")");
        return out == null ? null : TAG + out;
    }

    static String patchSettings(String source) {
        if (source == null) return null;
        if (source.startsWith(TAG)) return source;
        String anchor = "const { open, activeId } = useStore((state) => state);";
        String out = once(source, anchor, anchor + "\n"
                + "            (0, react.useEffect)(() => {\n"
                + "                const openAccount = (event) => { event.preventDefault(); actions.openSection(\"account\"); };\n"
                + "                window.addEventListener(\"" + EVENT + "\", openAccount);\n"
                + "                return () => window.removeEventListener(\"" + EVENT + "\", openAccount);\n"
                + "            }, [actions]);");
        out = once(out, "if (appeared && open) close();", "if (appeared && open && activeId !== \"account\") close();");
        out = once(out, "onboardingStep !== void 0 && renderSlot(\"settings.onboarding\", {",
                "!open && onboardingStep !== void 0 && renderSlot(\"settings.onboarding\", {");
        return out == null ? null : TAG + out;
    }

    static String openScript() {
        return "!window.dispatchEvent(new Event('" + EVENT + "',{cancelable:true}));";
    }

    private static String once(String source, String before, String after) {
        if (source == null) return null;
        int at = source.indexOf(before);
        if (at < 0 || source.indexOf(before, at + before.length()) >= 0) return null;
        return source.substring(0, at) + after + source.substring(at + before.length());
    }
}
