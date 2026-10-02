package dev.dsh.nativeapp;
public class AppSettingsCommandsTest {
    public static void main(String[] args) {
        int checks=0;
        for (String action : new String[]{"tasks","models","display","updates","data","diagnostics"}) {
            AppSettingsCommands command=AppSettingsCommands.parse("[dsh-native-settings] {\"id\":\"app-123-abc\",\"action\":\""+action+"\",\"locale\":\"en\"}");
            if(command==null||!action.equals(command.action)||!"en".equals(command.locale))throw new AssertionError(action);
            checks++;
        }
        for (String data : new String[]{null,"", "x [dsh-native-settings] {}", "[dsh-native-settings] {}",
                "[dsh-native-settings] {\"id\":\"app-123-a';exec()\",\"action\":\"tasks\",\"locale\":\"en\"}",
                "[dsh-native-settings] {\"id\":\"app-123-a\",\"action\":\"shell\",\"locale\":\"en\"}",
                "[dsh-native-settings] {\"id\":\"app-123-a\",\"action\":\"data\",\"locale\":\"en\",\"path\":\"/data\"}",
                "[dsh-native-settings] {\"id\":\"app-123-a\",\"action\":\"data\",\"locale\":\"system\"}"}) {
            if(AppSettingsCommands.parse(data)!=null)throw new AssertionError("untrusted command accepted");checks++;
        }
        System.out.println("AppSettingsCommandsTest: "+checks+" checks passed");
    }
}
