package dev.dsh.nativeapp;
import android.app.*;import android.widget.*;import android.content.*;import java.util.*;
public class WebSettingsHostTest {
 public static String json(Object value){return NativeBridgePolicy.json(value);}
 static int checks,actions,dismisses,shown,imports;static boolean consumer;static Activity activity;static WebSettingsHost host;static EditText field,secret;static Button button;static Dialog dialog;
 static void dense(LinearLayout body){
  TextView title=new TextView(activity);title.size=17;WebSettingsHost.semantic(title,"title");title.setText("显示与语言 / Display & language");body.addView(title);
  TextView hint=new TextView(activity);WebSettingsHost.semantic(hint,"hint");hint.setText("主题与语言跟随 DSH 设置。文字缩放立即生效。 Theme and language follow DSH settings; text scaling applies immediately.");body.addView(hint);
  TextView section=new TextView(activity);section.setText("显示缩放 / Display scale");WebSettingsHost.semantic(section,"section");body.addView(section);
  LinearLayout row=new LinearLayout(activity);row.orientation=LinearLayout.HORIZONTAL;
  for(String label:new String[]{"80%","90%","100%","115%","130%","150%"}){Button option=new Button(activity);option.setText(label);option.selected=label.equals("100%");WebSettingsHost.semantic(option,"choice");option.action=()->{for(int i=0;i<row.getChildCount();i++)row.getChildAt(i).selected=row.getChildAt(i)==option;};row.addView(option);}body.addView(row);
  LinearLayout columns=new LinearLayout(activity);columns.orientation=LinearLayout.HORIZONTAL;
  for(String label:new String[]{"当前项目改为跟随全局 / Use global default for this project","配置备份与恢复 / Configuration backup & restore"}){LinearLayout column=new LinearLayout(activity);TextView text=new TextView(activity);text.setText(label);column.addView(text);Button action=new Button(activity);action.setText(label);column.addView(action);columns.addView(column);}body.addView(columns);
 }
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
 static Map<?,?> map(Object value){return (Map<?,?>)value;}
 static Map<?,?> call(String op,Object...extra)throws Exception{Map<String,Object> args=NativeBridgePolicy.object("id","ws-1-test","session",host.session,"op",op);for(int i=0;i<extra.length;i+=2)args.put((String)extra[i],extra[i+1]);Map<?,?> parsed=WebSettingsProtocol.parse(WebSettingsProtocol.MARKER+json(args));check(parsed!=null,"valid protocol "+op);return map(JsonValue.parse(host.request(parsed).toString()));}
 static Map<?,?> node(Map<?,?> snapshot,int index){return map(((List<?>)map(((List<?>)snapshot.get("pages")).get(0)).get("body")).get(index));}
 static List<?> nodes(Map<?,?> snapshot){return (List<?>)map(map(((List<?>)snapshot.get("pages")).get(0)).get("body")).get("children");}
 static void launch(String page){LinearLayout body=new LinearLayout(activity);WebSettingsHost.semantic(body,"body");field=new EditText(activity);field.setText(page.equals("files")?String.join("",Collections.nCopies(70000,"x")):"synthetic text");if(page.equals("files"))field.inputType=131072;body.addView(field);secret=new EditText(activity);secret.password=true;secret.setText("SECRET-SYNTHETIC-CREDENTIAL");body.addView(secret);button=new Button(activity);button.setText("Save");button.action=()->button.setText("Saved "+(++actions));body.addView(button);if(consumer&&!page.equals("files")){dense(body);Button details=new Button(activity);details.setText("Open details");details.action=()->{LinearLayout inner=new LinearLayout(activity);WebSettingsHost.semantic(inner,"body");TextView caption=new TextView(activity);caption.setText("Operation details");WebSettingsHost.semantic(caption,"title");inner.addView(caption);TextView description=new TextView(activity);description.setText("Synthetic details");inner.addView(description);LinearLayout wrapper=new LinearLayout(activity);wrapper.addView(inner);Dialog nested=WebSettingsHost.dialog(activity,wrapper,null);nested.show();};body.addView(details);}dialog=WebSettingsHost.dialog(activity,body,null);dialog.setOnDismissListener(d->dismisses++);dialog.setOnShowListener(d->shown++);dialog.show();}
 public static void main(String[] args)throws Exception{
  activity=new Activity();host=new WebSettingsHost(activity,new WebSettingsHost.Launcher(){public void open(String page){launch(page);}public void rememberCodeBuddy(){imports++;}public void file(String action,String path){if(action.equals("edit")||action.equals("browse"))launch("files");}});
  if(args.length>0&&args[0].equals("--serve")){consumer=true;
   System.out.println(json(NativeBridgePolicy.object("ready",true,"session",host.session)));System.out.flush();
   java.io.BufferedReader input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in,"UTF-8"));String line;
   while((line=input.readLine())!=null){try{Map<?,?> command=WebSettingsProtocol.parse(line);if(command==null)throw new IllegalArgumentException();System.out.println(json(NativeBridgePolicy.object("id",command.get("id"),"ok",true,"value",JsonValue.parse(host.request(command).toString()))));}catch(Exception error){System.out.println("{\"ok\":false,\"error\":\"rejected\"}");}System.out.flush();}host.close();return;
  }
  check(WebSettingsHost.dialog(activity,new LinearLayout(activity),null)==null,"native fallback before page opens");
  Map<?,?> snapshot=call("open","page","display","locale","zh");check(dialog.isShowing()&&shown==1,"web dialog lifecycle show callback");
  check(!json(snapshot).contains("SECRET-SYNTHETIC-CREDENTIAL"),"stored password never enters snapshot");
  List<?> nodes=nodes(snapshot);String fieldId=(String)map(nodes.get(0)).get("id"),secretId=(String)map(nodes.get(1)).get("id"),buttonId=(String)map(nodes.get(2)).get("id");
  call("act","control",buttonId);check(actions==1,"real button listener executed");
  call("input","control",fieldId,"ticket","test","offset",0,"value","first ","done",false);check(field.getText().equals("synthetic text"),"chunk upload does not partially edit");
  call("input","control",fieldId,"ticket","test","offset",6,"value","second","done",true);check(field.getText().equals("first second"),"final input updates real field");
  check(call("read","control",fieldId,"offset",0).get("value").equals("first second"),"long editor text can be read in chunks");
  try{call("read","control",secretId,"offset",0);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
  call("input","control",secretId,"ticket","secret","offset",0,"value","synthetic-new-key","done",true);check(secret.getText().equals("synthetic-new-key"),"secret changes write through without reading old key");
  field.focusable=false;try{call("input","control",fieldId,"ticket","no","offset",0,"value","blocked","done",true);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
  button.enabled=false;try{call("act","control",buttonId);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}button.enabled=true;
  call("open","page","tasks","locale","en");check(dismisses==1&&shown==2,"navigation dismisses prior controller");
  try{call("act","control",buttonId);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
  call("close");check(!dialog.isShowing()&&dismisses==2,"web close invokes cleanup callback");
  call("codebuddy-default");check(imports==1,"fixed CodeBuddy default action works from original Models page");
  String valid=WebSettingsProtocol.MARKER+json(NativeBridgePolicy.object("id","ws-1-test","session",host.session,"op","state"));
  for(String invalid:new String[]{valid.replace("state","arbitrary"),valid.replace("ws-1-test","invalid"),valid.replace(host.session,"wrong"),valid.replace("\"op\":\"state\"","\"op\":\"state\",\"path\":\"/private\""),valid.replace("\"op\":\"state\"","\"op\":\"state\",\"op\":\"close\"")})check(WebSettingsProtocol.parse(invalid)==null,"closed schema rejects unexpected input");
  System.out.println("Web settings presenter: "+checks+" control, lifecycle, password, chunking and closed-protocol checks passed (Android API fakes; no device).");
 }
}
