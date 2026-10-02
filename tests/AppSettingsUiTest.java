package dev.dsh.nativeapp;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
public class AppSettingsUiTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 3) {
            String source = Files.readString(Paths.get(args[1]), StandardCharsets.UTF_8);
            String helper = "--dump-sessions".equals(args[0]) ? "" : Files.readString(Paths.get(args[2]), StandardCharsets.UTF_8);
            String patched = "--dump-sessions".equals(args[0]) ? AppSettingsUi.patchSessionClient(source) : "--dump-core".equals(args[0]) ? AppSettingsUi.patchCore(source, helper) : AppSettingsUi.patchClient(source, helper);
            String second = "--dump-sessions".equals(args[0]) ? AppSettingsUi.patchSessionClient(patched) : "--dump-core".equals(args[0]) ? AppSettingsUi.patchCore(patched, helper) : AppSettingsUi.patchClient(patched, helper);
            if (patched == null || !patched.equals(second)) throw new AssertionError("patch missing or not idempotent");
            if ("--dump-client".equals(args[0]) && !patched.equals(AccountUi.patchSettings(patched))) throw new AssertionError("account patch composition");
            System.out.print(patched); return;
        }
        if (AppSettingsUi.patchClient("changed", "/* DSH-ANDROID-APP-SETTINGS-v1 */\n/* DSH-ANDROID-APP-SETTINGS-END */") != null) throw new AssertionError("changed core accepted");
        if (AppSettingsUi.patchCore("changed", "/* DSH-ANDROID-SESSION-TOOLS-v1 */\n/* DSH-ANDROID-SESSION-TOOLS-END */") != null) throw new AssertionError("changed server accepted");
        if (!AppSettingsUi.openScript("sessions").contains("page:'sessions'")) throw new AssertionError("session route");
        if (AppSettingsUi.openScript("';alert(1)").contains("alert")) throw new AssertionError("untrusted route");
        System.out.println("AppSettingsUiTest: 4 checks passed");
    }
}
