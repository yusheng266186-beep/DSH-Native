package dev.dsh.nativeapp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class AccountUiTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 2) {
            String source = new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8);
            String patched = "--dump-account".equals(args[0]) ? AccountUi.patchAccount(source) : AccountUi.patchSettings(source);
            String second = "--dump-account".equals(args[0]) ? AccountUi.patchAccount(patched) : AccountUi.patchSettings(patched);
            if (patched == null || !patched.equals(second)) throw new AssertionError("Actual UI must patch idempotently");
            System.out.print(patched); return;
        }
        check(AccountUi.patchAccount("changed upstream") == null, "account changed source accepted");
        check(AccountUi.patchSettings("changed upstream") == null, "settings changed source accepted");
        check(AccountUi.patchAccount(null) == null, "account null accepted");
        check(AccountUi.patchSettings(null) == null, "settings null accepted");
        String source = "const { open, activeId } = useStore((state) => state);\nif (appeared && open) close();\nonboardingStep !== void 0 && renderSlot(\"settings.onboarding\", {";
        String patched = AccountUi.patchSettings(source);
        check(patched != null && patched.contains("actions.openSection(\"account\")"), "original settings action missing");
        check(patched.equals(AccountUi.patchSettings(patched)), "settings not idempotent");
        check(AccountUi.patchSettings(source + source) == null, "duplicate settings accepted");
        check(AccountUi.openScript().contains("cancelable:true"), "entry must report readiness");
        System.out.println("TOTAL: 8 pass / 0 fail");
    }
    private static void check(boolean ok, String detail) { if (!ok) throw new AssertionError(detail); }
}
