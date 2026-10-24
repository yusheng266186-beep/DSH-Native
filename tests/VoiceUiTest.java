package dev.dsh.nativeapp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
public class VoiceUiTest {
    private static int checks;
    static String read(String path) throws Exception { return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8); }
    static void check(boolean value) { if (!value) throw new AssertionError("voice protocol " + checks); checks++; }
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "--dump-module".equals(args[0])) {
            String helper=read(args[2]), next=VoiceUi.patch(read(args[1]), helper);
            check(next!=null); check(next.equals(VoiceUi.patch(next, helper))); System.out.print(next); return;
        }
        for (String action : new String[]{"start","stop","cancel","check","settings"}) {
            VoiceUi.Command result=VoiceUi.parse(VoiceUi.MARKER+"{\"id\":\"safe-1\",\"action\":\""+action+"\",\"language\":\"zh-CN\"}");
            check(result!=null && result.id.equals("safe-1") && result.action.equals(action));
        }
        for (String value : new String[]{"", "{}", "[dsh-voice] {}", "[dsh-voice] {\"id\":\"bad id\",\"action\":\"start\"}",
                "[dsh-voice] {\"id\":\"x\",\"action\":\"shell\"}", "[dsh-voice] {\"id\":\"x\",\"action\":\"start\",\"language\":\"zh;exec\"}"}) check(VoiceUi.parse(value)==null);
        check(VoiceUi.patch("unknown", "invalid")==null);
        check(VoiceUi.bridgeScript().contains("dsh-native-voice-ready"));
        System.out.println("TOTAL: "+checks+" pass / 0 fail");
    }
}
