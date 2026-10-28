#!/usr/bin/env python3
"""Run the actual startup view against a deterministic frame/lifecycle boundary.

No phone or emulator is used. Android compilation is checked separately.
"""
import pathlib
import shutil
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'src/dev/dsh/nativeapp'
if not SOURCE.is_dir():
    SOURCE = ROOT / 'bootstrap/src/dev/dsh/nativeapp'

STUBS = {
    'android/content/Context.java': 'package android.content;public class Context {}',
    'android/app/Activity.java': 'package android.app;public class Activity extends android.content.Context {public android.content.res.Resources getResources(){return new android.content.res.Resources();}public String getPackageName(){return "dev.dsh.native";}public android.graphics.drawable.Drawable getDrawable(int id){return new android.graphics.drawable.Drawable();}}',
    'android/content/res/Resources.java': 'package android.content.res;public class Resources {public int getIdentifier(String n,String t,String p){return 1;}public android.util.DisplayMetrics getDisplayMetrics(){return new android.util.DisplayMetrics();}public android.content.res.Configuration getConfiguration(){return new android.content.res.Configuration();}}',
    'android/content/res/Configuration.java': 'package android.content.res;public class Configuration {public float fontScale=1f;}',
    'android/text/TextUtils.java': 'package android.text;public class TextUtils {public enum TruncateAt {END}}',
    'android/util/DisplayMetrics.java': 'package android.util;public class DisplayMetrics {public float density=1;}',
    'android/os/SystemClock.java': 'package android.os;public class SystemClock {public static long now=1000;public static long uptimeMillis(){return now;}}',
    'android/view/Gravity.java': 'package android.view;public class Gravity {public static final int CENTER=1,CENTER_HORIZONTAL=2;}',
    'android/view/View.java': '''package android.view;import java.util.*;import android.content.Context;
        public class View {
            public static final int VISIBLE=0,GONE=8,IMPORTANT_FOR_ACCESSIBILITY_NO=2,ACCESSIBILITY_LIVE_REGION_POLITE=1;
            public static final List<Runnable> frames=new ArrayList<>();public boolean attached,clickable;public int visibility;public float alpha=1f,translationY;public ViewGroup.LayoutParams layout;
            public View(Context c){}public void setClickable(boolean v){clickable=v;}public void setVisibility(int v){visibility=v;}
            public int getVisibility(){return visibility;}public boolean isAttachedToWindow(){return attached;}
            public void postOnAnimation(Runnable v){frames.add(v);}public void removeCallbacks(Runnable v){frames.removeIf(task->task==v);}
            public void setAlpha(float v){alpha=v;}public void setTranslationY(float v){translationY=v;}public void setLayoutParams(ViewGroup.LayoutParams p){layout=p;}public void invalidate(){}public void setImportantForAccessibility(int v){}public void setAccessibilityLiveRegion(int v){}
            public void setBackgroundColor(int v){}public void setPadding(int a,int b,int c,int d){}
            public android.content.res.Resources getResources(){return new android.content.res.Resources();}
            public int getWidth(){return 208;}public int getHeight(){return 160;}protected void onDraw(android.graphics.Canvas c){}protected void onSizeChanged(int w,int h,int ow,int oh){}
            protected void onAttachedToWindow(){attached=true;}protected void onDetachedFromWindow(){attached=false;}
            public static void at(long time){android.os.SystemClock.now=time;List<Runnable> copy=new ArrayList<>(frames);frames.clear();for(Runnable work:copy)work.run();}
        }''',
    'android/view/ViewGroup.java': '''package android.view;import android.content.Context;public class ViewGroup extends View {
        public java.util.List<View> children=new java.util.ArrayList<>();public ViewGroup(Context c){super(c);}
        public void addView(View v,LayoutParams p){children.add(v);v.layout=p;}public static class LayoutParams {public static final int MATCH_PARENT=-1,WRAP_CONTENT=-2;public int width,height;public LayoutParams(int w,int h){width=w;height=h;}}
    }''',
    'android/widget/FrameLayout.java': '''package android.widget;public class FrameLayout extends android.view.ViewGroup {
        public FrameLayout(android.content.Context c){super(c);}public static class LayoutParams extends android.view.ViewGroup.LayoutParams {public LayoutParams(int w,int h,int g){super(w,h);}}
    }''',
    'android/widget/ScrollView.java': 'package android.widget;public class ScrollView extends android.view.ViewGroup {public ScrollView(android.content.Context c){super(c);}public void setFillViewport(boolean v){}public void setVerticalScrollBarEnabled(boolean v){}}',
    'android/widget/LinearLayout.java': '''package android.widget;public class LinearLayout extends android.view.ViewGroup {
        public static final int VERTICAL=1;public LinearLayout(android.content.Context c){super(c);}public void setOrientation(int v){}public void setGravity(int v){}
        public static class LayoutParams extends android.view.ViewGroup.LayoutParams {public int topMargin;public LayoutParams(int w,int h){super(w,h);}}
    }''',
    'android/widget/TextView.java': '''package android.widget;public class TextView extends android.view.View {
        public CharSequence text="";public int updates,minLines,maxLines;public TextView(android.content.Context c){super(c);}public void setText(CharSequence v){text=v;updates++;}public CharSequence getText(){return text;}public void setMinLines(int v){minLines=v;}public void setMaxLines(int v){maxLines=v;}public void setEllipsize(android.text.TextUtils.TruncateAt v){}public void setGravity(int v){}public void setTextSize(float v){}public void setTextColor(int v){}
    }''',
    'android/graphics/Paint.java': 'package android.graphics;public class Paint {public static final int ANTI_ALIAS_FLAG=1;public enum Style {STROKE}public enum Cap {ROUND}public Paint(int v){}public void setShader(android.graphics.Shader s){}public void setColor(int v){}public void setAlpha(int v){}public void setStyle(Style v){}public void setStrokeWidth(float v){}public void setStrokeCap(Cap v){}}',
    'android/graphics/Shader.java': 'package android.graphics;public class Shader {public enum TileMode {CLAMP}}',
    'android/graphics/RadialGradient.java': 'package android.graphics;public class RadialGradient extends Shader {public RadialGradient(float x,float y,float r,int[] colors,float[] stops,TileMode mode){}}',
    'android/graphics/Path.java': 'package android.graphics;public class Path {public void reset(){}public void moveTo(float a,float b){}public void cubicTo(float a,float b,float c,float d,float e,float f){}}',
    'android/graphics/drawable/Drawable.java': 'package android.graphics.drawable;public class Drawable {int a,b,c,d;public Drawable mutate(){return this;}public void setTint(int v){}public void setBounds(int a,int b,int c,int d){this.a=a;this.b=b;this.c=c;this.d=d;}public void draw(android.graphics.Canvas v){v.marks.add(new int[]{a,b,c,d});}}',
    'android/graphics/Canvas.java': '''package android.graphics;public class Canvas {
        public java.util.List<int[]> marks=new java.util.ArrayList<>();public int paths,ovals,lines;public void save(){}public void restore(){}public void translate(float x,float y){}public void scale(float x,float y){}public java.util.List<Float> angles=new java.util.ArrayList<>();public void rotate(float a,float x,float y){angles.add(a);}public void drawLine(float x,float y,float a,float b,Paint p){lines++;}public void drawPath(Path p,Paint b){paths++;}public void drawOval(float l,float t,float r,float b,Paint p){ovals++;}public java.util.List<float[]> circles=new java.util.ArrayList<>();public void drawCircle(float x,float y,float r,Paint p){circles.add(new float[]{x,y,r});}
    }''',
    'dev/dsh/nativeapp/DshUi.java': '''package dev.dsh.nativeapp;public class DshUi {
        static boolean motion=true;static int reads;static int BG(){return 0;}static int ACCENT(){return 0x4d6bfe;}static int BRAND(){return 0x4d6bfe;}static int TEXT_2(){return 0;}static int TEXT(){return 0;}
        static int dp(android.app.Activity a,int v){return v;}static boolean animationsEnabled(android.app.Activity a){reads++;return motion;}
        static android.widget.TextView title(android.app.Activity a,String text){return new android.widget.TextView(a);}
        static android.widget.TextView hint(android.app.Activity a,String text){return new android.widget.TextView(a);}
    }''',
    'dev/dsh/nativeapp/UiText.java': 'package dev.dsh.nativeapp;public class UiText {static String t(String a,String b){return b;}}',
    'dev/dsh/nativeapp/StartupViewBoundaryTest.java': '''package dev.dsh.nativeapp;
        import android.view.View;import android.os.SystemClock;import android.graphics.Canvas;
        public class StartupViewBoundaryTest {
            static int done,checks;static void check(boolean v,String why){if(!v)throw new AssertionError(why);checks++;}
            static StartupView create(){View.frames.clear();SystemClock.now=1000;done=0;DshUi.motion=true;
                StartupView v=new StartupView(new android.app.Activity(),()->done++);v.onAttachedToWindow();return v;}
            public static void main(String[] args)throws Exception {
                StartupView v=create();View.at(5000);check(done==0&&v.getVisibility()==View.VISIBLE,"long update keeps loading");
                v.ready(true);View.at(5090);check(done==0&&v.alpha==.5f&&v.clickable,"ready page blends in while touches remain intercepted");
                v.ready(true);View.at(5180);
                check(done==1&&v.getVisibility()==View.GONE&&!v.clickable&&View.frames.isEmpty(),"repeated readiness does not delay bounded handoff");
                v.ready(true);View.at(7000);check(done==1,"handoff is one shot");
                v=create();View.at(1300);v.ready(true);View.at(1390);v.ready(false);
                check(v.alpha==1f&&v.clickable,"disconnect restores an opaque loading page immediately");
                View.at(1600);check(done==0,"disconnect withdraws pending handoff");
                v.ready(true);v.status("Extracting updated slice");View.at(1780);check(done==1,"status text does not reset handoff");
                v=create();v.pause();check(View.frames.isEmpty(),"background has no frame loop");v.ready(true);View.at(5000);
                check(done==0,"do not hand off in background");v.resume();check(done==1,"ready foreground returns immediately");
                v=create();DshUi.motion=false;v.resume();View.at(1300);check(View.frames.isEmpty(),"reduced motion is static");v.ready(true);
                check(done==1&&v.getVisibility()==View.GONE,"reduced motion does not wait on a stopped animator");
                v=create();v.ready(true);View.at(1090);v.dismiss();View.at(1720);
                check(done==0&&!v.clickable&&View.frames.isEmpty(),"error interrupts transition without reporting success");
                v.begin("Retrying");check(v.alpha==1f&&v.clickable,"retry removes stale transition state");v.ready(true);View.at(1900);check(done==1,"retry starts an independent bounded handoff");
                v=create();v.onDetachedFromWindow();check(View.frames.isEmpty(),"destroyed view releases frame callbacks");
                v.onAttachedToWindow();v.ready(true);View.at(1180);check(done==1,"reattached view can finish");
                v=create();View.at(1350);View graphic=((android.view.ViewGroup)((android.view.ViewGroup)v.children.get(0)).children.get(0)).children.get(0);
                java.lang.reflect.Method draw=graphic.getClass().getDeclaredMethod("onDraw",Canvas.class);draw.setAccessible(true);
                Canvas canvas=new Canvas();draw.invoke(graphic,canvas);check(canvas.marks.size()==2&&canvas.paths==1&&canvas.ovals==3,"brand body, independent tail, wake and layered ripples render together");
                check(canvas.angles.size()==2&&!canvas.angles.get(0).equals(canvas.angles.get(1)),"tail articulates independently of the body's swimming pitch");
                for(float[] c:canvas.circles)check(c[0]-c[2]>=0&&c[0]+c[2]<=208&&c[1]-c[2]>=0&&c[1]+c[2]<=160,"motion stays within its bounds");
                View.at(7280);Canvas blink=new Canvas();draw.invoke(graphic,blink);check(blink.lines==1,"occasional blink draws within the swimming pose");
                v.onSizeChanged(640,320,320,640);check(graphic.layout.height<160,"short landscape reduces the decorative area");
                android.widget.TextView status=(android.widget.TextView)((android.view.ViewGroup)((android.view.ViewGroup)v.children.get(0)).children.get(0)).children.get(2);
                check(status.minLines==3&&status.maxLines==3,"status changes reserve stable text space");
                v.status("same");int updates=status.updates;v.status("same");check(updates==status.updates,"duplicate status does not repeat accessibility announcements");
                v.dismiss();check(View.frames.isEmpty(),"final cleanup");
                v=create();int before=DshUi.reads;for(int frame=0;frame<60;frame++)View.at(1000+frame*16);
                check(DshUi.reads==before,"animation frames never query the system settings provider");v.dismiss();
                v=create();v.ready(true);View.at(6000);check(done==1,"a dropped transition frame never stalls readiness");
                v=create();View.at(1600);graphic=((android.view.ViewGroup)((android.view.ViewGroup)v.children.get(0)).children.get(0)).children.get(0);
                java.lang.reflect.Field elapsed=graphic.getClass().getDeclaredField("elapsed");elapsed.setAccessible(true);
                long frozen=elapsed.getLong(graphic);v.pause();View.at(9000);v.resume();View.at(9016);
                check(elapsed.getLong(graphic)==frozen+16,"foreground resumes the frozen swim instead of jumping to a new pose");v.dismiss();
                System.out.println("Startup view: "+checks+" lifecycle, motion, error and drawing checks passed.");
            }
        }''',
}

with tempfile.TemporaryDirectory(prefix='dsh-startup-test-') as directory:
    root = pathlib.Path(directory)
    for name, source in STUBS.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source)
    compiler = [shutil.which('javac')] if shutil.which('javac') else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
    subprocess.run([*compiler, '-encoding', 'UTF-8', '-d', str(root / 'classes'),
                    *map(str, root.rglob('*.java')), str(SOURCE / 'StartupView.java'), str(SOURCE / 'StartupState.java')], check=True)
    subprocess.run(['java', '-cp', str(root / 'classes'), 'dev.dsh.nativeapp.StartupViewBoundaryTest'], check=True)
