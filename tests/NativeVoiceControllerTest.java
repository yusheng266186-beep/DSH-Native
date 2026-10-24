package dev.dsh.nativeapp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Execute the actual controller; only Android OS calls are replaced by test fakes. */
public class NativeVoiceControllerTest {
    private static int checks;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static class Host implements NativeVoiceController.Host {
        boolean foreground = true;
        final List<Map<?, ?>> events = new ArrayList<Map<?, ?>>();
        final List<String> logs = new ArrayList<String>();
        public boolean canListen() { return foreground; }
        public void event(String json) { events.add((Map<?, ?>) JsonValue.parse(json)); }
        public void diagnostic(String detail) { logs.add(detail); }
        Map<?, ?> last() { return events.get(events.size()-1); }
        boolean state(String value) { return value.equals(last().get("state")); }
    }
    private static class Run {
        final Activity activity = new Activity();
        final Host host = new Host();
        final NativeVoiceController voice = new NativeVoiceController(activity, host);
        Run() { Handler.reset(); SpeechRecognizer.reset(); Settings.Secure.selected = "synthetic/RecognitionService"; }
        void command(String action) { voice.command(new VoiceUi.Command("test-1",action,"zh-CN")); }
        SpeechRecognizer engine() { return SpeechRecognizer.created.get(SpeechRecognizer.created.size()-1); }
        void windowResult(int code, String text) {
            Intent data = new Intent("synthetic-result").putExtra(RecognizerIntent.EXTRA_RESULTS,new ArrayList<String>(Arrays.asList(text)));
            voice.activityResult(code,data);
        }
    }
    public static void main(String[] args) {
        Run run = new Run(); Settings.Secure.selected = null;
        run.command("start");
        check(run.activity.windows==1,"registered service without a default must route to system window");
        check(SpeechRecognizer.created.isEmpty(),"no unusable default recognizer is constructed");
        check(run.activity.permissionRequests==0,"system window owns its microphone permission");
        run.voice.pause(); run.windowResult(Activity.RESULT_OK,"SYNTHETIC-DICTATION");
        check(run.host.state("result"),"system activity pause must retain result delivery");
        check("SYNTHETIC-DICTATION".equals(run.host.last().get("text")),"system transcript returned to scoped event");
        check(!run.voice.diagnostic().contains("SYNTHETIC-DICTATION"),"diagnostic never contains transcript");

        run = new Run(); Settings.Secure.selected = null; run.activity.pm.window=false;
        run.command("check"); check(Boolean.FALSE.equals(run.host.last().get("available")),"registered does not imply usable");
        run.command("start"); check(run.host.state("error"),"missing default and window must show error");
        check("default-service-unavailable".equals(run.host.last().get("reason")),"missing default is diagnosable");

        run = new Run(); run.command("start"); SpeechRecognizer first=run.engine(); first.listener.onError(5);
        check(run.host.state("fallback"),"immediate client error must enter bounded compatibility fallback");
        check(first.destroyed,"release failed service before launching system window");
        Handler.advance(250); check(run.activity.windows==1,"client error uses the system window");
        int before=run.host.events.size(); first.listener.onResults(new Bundle());
        check(run.host.events.size()==before,"old service callback cannot end system window request");
        run.windowResult(Activity.RESULT_OK,"safe");check(run.host.state("result"),"fallback succeeds without a new draft request");
        check(run.voice.diagnostic().contains("engine-start:5/service-error"),"failure path retained after success");

        run = new Run(); run.activity.pm.window=false;run.command("start");run.engine().listener.onError(5);
        check(run.host.state("error")&&((Number)run.host.last().get("error")).intValue()==5,"no window preserves concrete client error");
        Handler.advance(100000);check(SpeechRecognizer.created.size()==1,"no engine retry loop");

        run = new Run(); SpeechRecognizer.onDevice=true;run.command("start");first=run.engine();
        check(first.device,"supported on-device path is preferred");first.listener.onError(5);Handler.advance(250);
        check(SpeechRecognizer.created.size()==2&&!run.engine().device,"on-device failure tries default once");
        run.engine().listener.onError(5);Handler.advance(250);check(run.activity.windows==1,"default failure then uses system window once");
        run.windowResult(RecognizerIntent.RESULT_CLIENT_ERROR,"");check(run.host.state("error"),"system window error is not reported as cancellation");
        check(((Number)run.host.last().get("error")).intValue()==5,"system result error mapping");
        check("system-window".equals(run.host.last().get("mode")),"window error reports actual route");
        Handler.advance(100000);check(run.activity.windows==1,"window error does not recursively reopen");

        run = new Run(); SpeechRecognizer.failStart=true;run.command("start");Handler.advance(250);
        check(run.activity.windows==1,"startup exception also uses system window");
        check(run.voice.diagnostic().contains("IllegalStateException"),"exception class is retained");
        run = new Run(); SpeechRecognizer.failCreate=true;run.command("start");Handler.advance(250);
        check(run.activity.windows==1,"creation exception uses system window");

        run = new Run();run.command("start");run.engine().listener.onError(5);run.command("cancel");Handler.advance(250);
        check(run.activity.windows==0&&run.host.state("cancelled"),"cancel prevents delayed fallback window");
        run = new Run();run.command("start");run.engine().listener.onError(5);run.host.foreground=false;Handler.advance(250);
        check(run.activity.windows==0&&run.host.state("cancelled"),"background must not launch fallback window");

        run = new Run();run.command("start");run.command("stop");check(run.engine().stops==0,"stop before ready must not cause client error");
        run.engine().listener.onReadyForSpeech(new Bundle());run.command("stop");run.command("stop");
        check(run.engine().stops==1,"only one stop while waiting for results");run.voice.dispose();
        run = new Run();SpeechRecognizer.syncReady=true;run.command("start");Handler.advance(12000);
        check(run.host.state("listening")&&run.activity.windows==0,"synchronous ready keeps recording deadline");
        Handler.advance(48000);check(run.engine().stops==1,"recording duration remains bounded");
        run.voice.dispose();

        run = new Run();run.command("start");Handler.advance(12250);
        check(run.activity.windows==1,"missing startup callback falls back within bounded time");
        check(run.voice.diagnostic().contains("engine-start-timeout"),"startup timeout is distinct from recognition failure");
        run.windowResult(Activity.RESULT_CANCELED,"");check(run.host.state("cancelled"),"user cancellation remains cancellation");

        run = new Run();run.activity.permission=-1;run.command("start");run.voice.pause();
        check(run.host.state("permission")&&SpeechRecognizer.created.isEmpty(),"permission dialog does not start microphone");
        run.voice.permissionResult(false);check(run.host.state("error")&&run.activity.windows==0,"permission denial does not auto-open other recorder");
        run = new Run();run.activity.permission=-1;run.command("start");run.host.foreground=false;run.voice.permissionResult(true);
        check(SpeechRecognizer.created.isEmpty(),"grant before resume cannot record in background");
        run.host.foreground=true;run.voice.resume();check(SpeechRecognizer.created.size()==1,"grant starts only after foreground resume");
        run.voice.dispose();

        run = new Run();Settings.Secure.selected=null;run.activity.failWindow=true;run.command("start");
        check(run.host.state("error")&&"window-launch".equals(run.host.last().get("reason")),"failed activity launch is actionable");
        run = new Run();run.command("start");Bundle result=new Bundle();
        result.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,new ArrayList<String>(Arrays.asList(new String(new char[16001]).replace('\0','x'))));
        run.engine().listener.onResults(result);check(((Number)run.host.last().get("error")).intValue()==1001,"oversized transcript is not misreported as system client error");
        check(run.host.logs.stream().noneMatch(value->value.contains("SYNTHETIC-DICTATION")),"operational logs exclude transcript");
        System.out.println("Native voice controller: "+checks+" routing/lifecycle checks passed (Android boundary fakes; no device/audio/ASR).");
    }
}
