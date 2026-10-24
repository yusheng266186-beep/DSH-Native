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
        check(run.activity.windows==0,"inline start never opens system activity");
        check(run.engine().component.flattenToString().equals("synthetic/RecognitionService"),"missing default can directly use the same window provider's public service");
        run.engine().listener.onReadyForSpeech(new Bundle());
        android.os.Handler.advance(130);
        run.engine().listener.onRmsChanged(7);
        check(run.host.state("level")&&((Number)run.host.last().get("level")).doubleValue()>0,"real RMS is forwarded");
        int before=run.host.events.size();run.engine().listener.onRmsChanged(8);
        check(run.host.events.size()==before,"RMS events are throttled");
        Bundle partial=new Bundle();partial.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,new ArrayList<String>(Arrays.asList("PRIVATE-PARTIAL")));
        run.engine().listener.onPartialResults(partial);
        check(run.host.state("partial")&&"PRIVATE-PARTIAL".equals(run.host.last().get("text")),"partial transcript is a preview event");
        check(!run.voice.diagnostic().contains("PRIVATE-PARTIAL"),"partial does not enter diagnostics");run.voice.dispose();

        run = new Run();Settings.Secure.selected=null;run.activity.pm.windowPackage="unrelated";
        run.command("start");check(run.host.state("error")&&run.activity.windows==0,"unrelated registered provider is not silently selected");
        check("inline-unavailable".equals(run.host.last().get("reason")),"inline-only limitation is explicit");
        run.command("start-window");check(run.activity.windows==1,"system window requires explicit action");
        run.voice.pause();run.windowResult(Activity.RESULT_OK,"SYNTHETIC-DICTATION");
        check(run.host.state("result")&&"SYNTHETIC-DICTATION".equals(run.host.last().get("text")),"explicit window remains usable across activity pause");
        check(!run.voice.diagnostic().contains("SYNTHETIC-DICTATION"),"window transcript excluded from diagnostics");

        run = new Run();Settings.Secure.selected=null;run.activity.pm.configured=false;run.activity.pm.window=false;
        run.command("check");check(Boolean.FALSE.equals(run.host.last().get("available")),"no entry is not available");
        run.command("start");check(run.host.state("error")&&run.activity.windows==0,"no service never launches activity");

        run = new Run();run.command("start");SpeechRecognizer first=run.engine();first.listener.onError(5);
        check(run.host.state("error"),"inline failure stays in panel");check(first.destroyed,"failed inline recognizer destroyed");
        Handler.advance(100000);check(run.activity.windows==0&&SpeechRecognizer.created.size()==1,"client failure has no popup or retry loop");
        before=run.host.events.size();first.listener.onResults(new Bundle());check(run.host.events.size()==before,"stale callback ignored");
        run.command("start-window");run.windowResult(Activity.RESULT_OK,"safe");check(run.host.state("result"),"manual window still works after inline failure");

        run = new Run();SpeechRecognizer.onDevice=true;run.command("start");first=run.engine();check(first.device,"auto prefers on-device");
        first.listener.onError(5);Handler.advance(250);check(SpeechRecognizer.created.size()==2&&!run.engine().device,"on-device failure tries chosen inline service once");
        run.engine().listener.onError(5);Handler.advance(250);check(run.host.state("error")&&run.activity.windows==0,"inline fallback never escalates to popup");
        run = new Run();SpeechRecognizer.failStart=true;run.command("start");check(run.host.state("error")&&run.activity.windows==0,"start exception stays inline");
        check(run.voice.diagnostic().contains("IllegalStateException"),"exception class retained");
        run = new Run();SpeechRecognizer.failCreate=true;run.command("start");check(run.host.state("error")&&run.activity.windows==0,"creation exception stays inline");

        run = new Run();SpeechRecognizer.onDevice=true;run.command("start");run.engine().listener.onError(5);run.command("cancel");Handler.advance(250);
        check(SpeechRecognizer.created.size()==1&&run.host.state("cancelled"),"cancel prevents delayed inline fallback");
        run = new Run();SpeechRecognizer.onDevice=true;run.command("start");run.engine().listener.onError(5);run.host.foreground=false;Handler.advance(250);
        check(SpeechRecognizer.created.size()==1&&run.host.state("cancelled"),"background prevents delayed inline fallback");

        run = new Run();run.command("start");run.command("stop");check(run.engine().stops==0,"stop before ready ignored");
        run.engine().listener.onReadyForSpeech(new Bundle());run.command("stop");run.command("stop");check(run.engine().stops==1,"stop once only");run.voice.dispose();
        run = new Run();SpeechRecognizer.syncReady=true;run.command("start");Handler.advance(12000);
        check(run.host.state("listening")&&run.activity.windows==0,"synchronous ready preserves recording deadline");
        Handler.advance(48000);check(run.engine().stops==1,"recording remains bounded");run.voice.dispose();
        run = new Run();run.command("start");Handler.advance(12000);
        check(run.host.state("error")&&run.activity.windows==0,"unready timeout stays inline");
        check(run.voice.diagnostic().contains("engine-start-timeout"),"startup timeout diagnosable");

        run = new Run();run.activity.permission=-1;run.command("start");run.voice.pause();
        check(run.host.state("permission")&&SpeechRecognizer.created.isEmpty(),"no recording before permission");
        run.voice.permissionResult(false);check(run.host.state("error")&&run.activity.windows==0,"permission denial does not open window");
        run = new Run();run.activity.permission=-1;run.command("start");run.host.foreground=false;run.voice.permissionResult(true);
        check(SpeechRecognizer.created.isEmpty(),"grant before resume cannot record");run.host.foreground=true;run.voice.resume();
        check(SpeechRecognizer.created.size()==1,"starts after resume");run.voice.dispose();

        run = new Run();run.command("start-window");run.windowResult(RecognizerIntent.RESULT_CLIENT_ERROR,"");
        check(run.host.state("error")&&((Number)run.host.last().get("error")).intValue()==5,"window errors remain distinct from cancellation");
        run = new Run();run.command("start-window");run.windowResult(Activity.RESULT_CANCELED,"");check(run.host.state("cancelled"),"window cancellation preserved");
        run = new Run();run.activity.failWindow=true;run.command("start-window");
        check(run.host.state("error")&&"window-launch".equals(run.host.last().get("reason")),"explicit window launch failure actionable");
        run = new Run();run.command("start");Bundle result=new Bundle();
        result.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,new ArrayList<String>(Arrays.asList(new String(new char[16001]).replace('\0','x'))));
        run.engine().listener.onResults(result);check(((Number)run.host.last().get("error")).intValue()==1001,"oversized result has separate error");

        run = new Run();run.activity.pm.services.get(0).serviceInfo.exported=false;run.command("start");
        check(SpeechRecognizer.created.isEmpty()&&run.activity.windows==0,"non-exported service never bound");
        run = new Run();run.activity.pm.services.get(0).serviceInfo.applicationInfo.enabled=false;run.command("start");
        check(SpeechRecognizer.created.isEmpty(),"disabled provider never bound");
        run = new Run();run.voice.command(new VoiceUi.Command("select","engine","zh-CN","uninstalled/Engine"));
        check(run.host.state("error")&&run.activity.prefs.getString("voiceEngine","auto").equals("auto"),"uninstalled component cannot be selected");
        run = new Run();SpeechRecognizer.onDevice=true;run.voice.command(new VoiceUi.Command("select","engine","zh-CN","synthetic/RecognitionService"));
        check(run.host.state("capabilities"),"installed public service can be selected");run.command("start");
        check(!run.engine().device&&run.engine().component!=null,"manual engine choice respected over automatic on-device");
        check(Boolean.TRUE.equals(run.host.last().get("inlineAvailable")),"inline capability is separate from window");
        check(((List<?>)run.host.last().get("engines")).size()==1,"settings list only public recognition services");
        run.voice.dispose();
        System.out.println("Native voice controller: "+checks+" routing/lifecycle checks passed (Android boundary fakes; no device/audio/ASR).");
    }
}
