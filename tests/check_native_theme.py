#!/usr/bin/env python3
"""Production theme methods and retained drawables with deterministic Android boundaries."""
import pathlib, shutil, subprocess, tempfile, sys
ROOT=pathlib.Path(__file__).resolve().parents[1]
SOURCE=ROOT/'src/dev/dsh/nativeapp'
if not SOURCE.is_dir(): SOURCE=ROOT/'bootstrap/src/dev/dsh/nativeapp'
main=(SOURCE/'MainActivity.java').read_text();ui=(SOURCE/'DshUi.java').read_text()
def method(text,signature):
 a=text.index(signature);b=text.index('{',a)+1;depth=1
 while depth:
  if text[b]=='{':depth+=1
  elif text[b]=='}':depth-=1
  b+=1
 return text[a:b]
colors='\n'.join(method(ui,'public static int '+n+'()') for n in ['BG','CARD','BORDER','FIELD','FIELD_FOCUS','BTN','BTN_PRESS','ACCENT','BRAND','ACCENT_DARK','SUCCESS','WARN','ERROR','TEXT','TEXT_2','TEXT_3','ON_ACCENT'])
colors+='\n'+method(ui,'private static int disabledFill()')+'\n'+method(ui,'static int[] palette()')
STUBS={
'android/content/Context.java':'package android.content;public class Context {}',
'android/content/ContextWrapper.java':'package android.content;public class ContextWrapper extends Context {Context c;public ContextWrapper(Context c){this.c=c;}public Context getBaseContext(){return c;}}',
'android/content/SharedPreferences.java':'package android.content;public class SharedPreferences {public java.util.Map<String,Boolean> data=new java.util.HashMap<>();public boolean contains(String k){return data.containsKey(k);}public boolean getBoolean(String k,boolean d){return data.getOrDefault(k,d);}public SharedPreferences edit(){return this;}public SharedPreferences putBoolean(String k,boolean v){data.put(k,v);return this;}public void apply(){}}',
'android/content/res/ColorStateList.java':'package android.content.res;public class ColorStateList {public int color;public static ColorStateList valueOf(int color){ColorStateList r=new ColorStateList();r.color=color;return r;}}',
'android/os/Looper.java':'package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'''package android.os;public class Handler {public static long now;static java.util.Map<Runnable,Long> work=new java.util.LinkedHashMap<>();public Handler(Looper l){}public void postDelayed(Runnable r,long ms){work.put(r,now+ms);}public void removeCallbacks(Runnable r){work.remove(r);}public static void advance(long ms){now+=ms;for(Runnable r:new java.util.ArrayList<>(work.keySet()))if(work.get(r)<=now){work.remove(r);r.run();}}}''',
'android/graphics/Paint.java':'package android.graphics;public class Paint {public int color;public void setColor(int c){color=c;}}',
'android/graphics/PorterDuff.java':'package android.graphics;public class PorterDuff {public enum Mode {SRC_IN}}',
'android/graphics/drawable/Drawable.java':'package android.graphics.drawable;public class Drawable {public int tint;public void setColorFilter(int c,android.graphics.PorterDuff.Mode m){tint=c;}}',
'android/graphics/drawable/GradientDrawable.java':'package android.graphics.drawable;public class GradientDrawable extends Drawable {public int fill,stroke,width;public void setColor(int c){fill=c;}public void setStroke(int w,int c){width=w;stroke=c;}}',
'android/graphics/drawable/RippleDrawable.java':'package android.graphics.drawable;public class RippleDrawable extends Drawable {public android.content.res.ColorStateList color;public void setColor(android.content.res.ColorStateList c){color=c;}}',
'android/graphics/drawable/ShapeDrawable.java':'package android.graphics.drawable;public class ShapeDrawable extends Drawable {public android.graphics.Paint paint=new android.graphics.Paint();public android.graphics.Paint getPaint(){return paint;}}',
'android/graphics/drawable/ColorDrawable.java':'package android.graphics.drawable;public class ColorDrawable extends Drawable {public int color;public ColorDrawable(int c){color=c;}}',
'android/view/View.java':'package android.view;public class View {public static int created;android.content.Context context;public int background;public View(android.content.Context c){created++;context=c;}public android.content.Context getContext(){return context;}public void setBackgroundColor(int c){background=c;}}',
'android/view/Window.java':'package android.view;public class Window {public android.graphics.drawable.Drawable background;public void setBackgroundDrawable(android.graphics.drawable.Drawable d){background=d;}}',
'android/widget/TextView.java':'package android.widget;public class TextView extends android.view.View {public int color,hint;public String value="";public boolean enabled=true,selected;public int selection,scroll;public TextView(android.content.Context c){super(c);}public void setTextColor(int c){color=c;}public int getCurrentTextColor(){return color;}public void setHintTextColor(int c){hint=c;}}',
'android/widget/EditText.java':'package android.widget;public class EditText extends TextView {public EditText(android.content.Context c){super(c);}}',
'android/widget/ProgressBar.java':'package android.widget;public class ProgressBar extends android.view.View {public ProgressBar(android.content.Context c){super(c);}public android.graphics.drawable.Drawable drawable=new android.graphics.drawable.Drawable();public android.graphics.drawable.Drawable getProgressDrawable(){return drawable;}}',
'android/webkit/WebView.java':'package android.webkit;public class WebView extends android.view.View {public int reloads;public WebView(android.content.Context c){super(c);}public void reload(){reloads++;}}',
'dev/dsh/nativeapp/DshUi.java':'package dev.dsh.nativeapp;class DshUi {static boolean dark;static boolean isDark(){return dark;}static void setDark(boolean d){dark=d;}'+colors+'}',
'dev/dsh/nativeapp/StartupView.java':'package dev.dsh.nativeapp;class StartupView {int paints;void applyTheme(){paints++;}}',
}
HARNESS=r'''
package dev.dsh.nativeapp;
import android.content.*;import android.os.*;import android.webkit.WebView;import android.widget.*;import android.graphics.drawable.*;
public class ThemeProbe extends Context {
 final Handler themeHandler=new Handler(Looper.getMainLooper());Runnable themeApply;long webDarkStamp,pendingWebDarkAt;boolean pendingWebDark,activityDisposing,destroyed;
 final StartupState startupState=new StartupState();static final String PREFS="fixture";static final int MODE_PRIVATE=0;
 SharedPreferences prefs=new SharedPreferences();WebView webView=new WebView(this);android.view.View rootView=new android.view.View(this);TextView logView=new TextView(this);StartupView splashView=new StartupView();ProgressBar topProgress=new ProgressBar(this);android.view.Window window=new android.view.Window();
 ThemeController themeController;int recreates,bars;boolean isFinishing(){return false;}boolean isDestroyed(){return destroyed;}boolean shouldAllowRecreate(){return true;}void recreate(){recreates++;}
 SharedPreferences getSharedPreferences(String p,int m){return prefs;}android.view.Window getWindow(){return window;}void log(String s){}void applySystemBars(){bars++;}
 __METHODS__
 static int checks;static void check(boolean value,String why){if(!value)throw new AssertionError(why);checks++;}
 public static void main(String[] args)throws Exception {
  if(args.length>0&&args[0].equals("--viewport-script")){System.out.print(ViewportState.script(Integer.parseInt(args[1])));return;}
  if(args.length>0&&args[0].equals("--script")){System.out.print(ThemeSync.script());return;}
  DshUi.setDark(false);ThemeProbe owner=new ThemeProbe();owner.startupState.navigate();
  if(args.length>0&&args[0].equals("--serve")){
   java.io.BufferedReader input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in,"UTF-8"));String line;
   while((line=input.readLine())!=null){if(line.equals("[dsh-theme] dark=1")||line.equals("[dsh-theme] dark=0"))owner.onWebTheme(line.endsWith("1"));Handler.advance(2000);System.out.println("{\"dark\":"+DshUi.isDark()+",\"recreates\":"+owner.recreates+",\"reloads\":"+owner.webView.reloads+",\"views\":"+android.view.View.created+"}");System.out.flush();}return;
  }
  EditText input=new EditText(new ContextWrapper(owner));input.value="UNSAVED-CANARY";input.selection=6;input.scroll=31;input.selected=true;input.enabled=false;input.color=DshUi.TEXT();NativeTheme.text(input,NativeTheme.Role.TEXT);
  TextView status=new TextView(owner);status.color=DshUi.ERROR();NativeTheme.text(status,NativeTheme.Role.TEXT_2);
  TextView primary=new TextView(owner);primary.color=DshUi.ON_ACCENT();NativeTheme.text(primary,NativeTheme.Role.ON_ACCENT);
  TextView foreign=new TextView(new Context());foreign.color=DshUi.TEXT();NativeTheme.text(foreign,NativeTheme.Role.TEXT);
  GradientDrawable field=NativeTheme.surface(owner,new GradientDrawable(),NativeTheme.Role.FIELD,NativeTheme.Role.BORDER,2);
  GradientDrawable dimmed=NativeTheme.surface(owner,new GradientDrawable(),NativeTheme.Role.DIMMED,null,0);
  RippleDrawable ripple=NativeTheme.surface(owner,new RippleDrawable(),NativeTheme.Role.RIPPLE_SECONDARY,null,0);
  ShapeDrawable divider=NativeTheme.surface(owner,new ShapeDrawable(),NativeTheme.Role.BORDER,null,0);
  final int[] redraw={0};NativeTheme.Target icon=new NativeTheme.Target(){public Context themeContext(){return owner;}public void applyTheme(){redraw[0]++;}};NativeTheme.watch(icon);
  WebView page=owner.webView;int count=android.view.View.created;
  owner.onWebTheme(true);Handler.advance(2000);
  check(redraw[0]==1,"retained custom icons receive an explicit redraw with the new palette");
  check(owner.recreates==0,"theme transition must never recreate the Activity/WebView");
  check(DshUi.isDark()&&input.color==DshUi.TEXT()&&primary.color==DshUi.ON_ACCENT(),"retained inputs and primary contrast follow the new palette");
  check(status.color==DshUi.ERROR(),"async error state survives recoloring");
  check(field.fill==DshUi.FIELD()&&field.stroke==DshUi.BORDER()&&field.width==2,"existing background and border recolor in place");
  check(dimmed.fill==DshUi.palette()[NativeTheme.Role.DIMMED.ordinal()]&&ripple.color.color==0x24FFFFFF&&divider.paint.color==DshUi.BORDER(),"disabled, ripple and separator states recolor");
  for(int i=0;i<20;i++){owner.onWebTheme(i%2==0);Handler.advance(2000);}
  check(owner.webView==page&&page.reloads==0&&owner.recreates==0&&android.view.View.created==count,"repeated switches retain exactly the same view objects");
  check(input.value.equals("UNSAVED-CANARY")&&input.selection==6&&input.scroll==31&&!input.enabled&&input.selected,"draft, selection, scrolling and enabled state are unchanged");
  check(foreign.color==0xFF0F1115,"another owner is not recolored");
  owner.onWebTheme(true);owner.onWebTheme(false);owner.onWebTheme(true);Handler.advance(2000);check(DshUi.isDark(),"rapid switches coalesce to the last value");
  owner.onWebTheme(false);owner.startupState.navigate();Handler.advance(2000);check(DshUi.isDark(),"old document callback cannot recolor the next navigation");
  owner.onWebTheme(false);owner.activityDisposing=true;Handler.advance(2000);check(DshUi.isDark(),"destroyed owner ignores pending theme work");
  owner.activityDisposing=false;NativeTheme.detach(owner);int redraws=redraw[0];int[] previous=DshUi.palette();DshUi.setDark(false);NativeTheme.apply(owner,previous,DshUi.palette());check(input.color!=DshUi.TEXT(),"detached controls are released from theme updates");
  check(redraw[0]==redraws,"detached custom icon targets are released");
  System.out.println("Native theme: "+checks+" production callback, retained state, color, lifecycle and cleanup checks passed; Android boundaries only.");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='dsh-theme-') as directory:
 root=pathlib.Path(directory)
 methods=method(main,'private void onWebTheme(')+'\n'+method(main,'private void applyNativeTheme(')+'\n'+method(main,'private ThemeController themeController()')
 if '--old-method' in sys.argv: methods=pathlib.Path(sys.argv[sys.argv.index('--old-method')+1]).read_text()+'\n'+method(main,'private void applyNativeTheme(')
 for name,body in {**STUBS,'dev/dsh/nativeapp/ThemeProbe.java':HARNESS.replace('__METHODS__',methods)}.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body)
 compiler=[shutil.which('javac')] if shutil.which('javac') else ['java','-m','jdk.compiler/com.sun.tools.javac.Main']
 subprocess.run([*compiler,'-encoding','UTF-8','-d',str(root/'classes'),*map(str,root.rglob('*.java')),*[str(SOURCE/(name+'.java')) for name in ['NativeTheme','UiPolicy','ThemeSync','ViewportState','MobileLayout','SessionProbe','SessionStatus','ConnectionRecovery','DraftRecovery','StartupState','ThemeController']]],check=True)
 subprocess.run(['java','-cp',str(root/'classes'),'dev.dsh.nativeapp.ThemeProbe'],check=True)
 if '--consumer' in sys.argv:
  index=sys.argv.index('--consumer');subprocess.run(['node',str(ROOT/'tests/js/theme-consumer.mjs'),sys.argv[index+1],sys.argv[index+2],str(root/'classes')],check=True)
