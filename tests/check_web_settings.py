#!/usr/bin/env python3
"""Execute the production Web settings presenter with deterministic Android boundaries."""
import importlib.util,pathlib,shutil,subprocess,tempfile,sys
ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('native_boundaries',ROOT/'tests/check_native_bridge.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
STUBS=dict(module.STUBS);SOURCE=module.SOURCE
STUBS.update({
'org/json/JSONException.java':'package org.json;public class JSONException extends RuntimeException {public JSONException(String message){super(message);}}',
'org/json/JSONObject.java':'package org.json;public class JSONObject extends java.util.LinkedHashMap<String,Object> {public JSONObject put(String key,Object value){super.put(key,value);return this;}public String toString(){return dev.dsh.nativeapp.WebSettingsHostTest.json(this);}}',
'org/json/JSONArray.java':'package org.json;public class JSONArray extends java.util.ArrayList<Object> {public JSONArray put(Object value){add(value);return this;}public String toString(){return dev.dsh.nativeapp.WebSettingsHostTest.json(this);}}',
'android/content/ContextWrapper.java':'package android.content;public class ContextWrapper extends Context {Context base;public ContextWrapper(Context c){base=c;}public Context getBaseContext(){return base;}}',
'android/content/DialogInterface.java':'''package android.content;import android.view.KeyEvent;public interface DialogInterface {interface OnShowListener{void onShow(DialogInterface d);}interface OnDismissListener{void onDismiss(DialogInterface d);}interface OnCancelListener{void onCancel(DialogInterface d);}interface OnKeyListener{boolean onKey(DialogInterface d,int key,KeyEvent e);}}''',
'android/app/Dialog.java':'''package android.app;import android.content.*;public class Dialog implements DialogInterface {Context context;public Dialog(Context c){context=c;}public Context getContext(){return context;}public boolean isShowing(){return false;}public void show(){}public void dismiss(){}public void cancel(){}public void setCancelable(boolean b){}public void setOnShowListener(OnShowListener v){}public void setOnDismissListener(OnDismissListener v){}public void setOnCancelListener(OnCancelListener v){}public void setOnKeyListener(OnKeyListener v){}}''',
'android/view/View.java':'''package android.view;import android.content.Context;public class View {public static final int VISIBLE=0,GONE=8;public int visibility;public boolean enabled=true,selected,clickable,longClickable;public ViewGroup parent;public Runnable action,longAction;Context context;public View(Context c){context=c;}public Context getContext(){return context;}public Object getParent(){return parent;}public int getVisibility(){return visibility;}public boolean isEnabled(){return enabled;}public boolean isActivated(){return selected;}public boolean isSelected(){return selected;}public boolean isClickable(){return clickable;}public boolean isLongClickable(){return longClickable;}public boolean performClick(){if(action!=null)action.run();return true;}public boolean performLongClick(){if(longAction!=null)longAction.run();return true;}}''',
'android/view/ViewGroup.java':'''package android.view;import android.content.Context;public class ViewGroup extends View {java.util.List<View> children=new java.util.ArrayList<>();public ViewGroup(Context c){super(c);}public void addView(View v){children.add(v);v.parent=this;}public int getChildCount(){return children.size();}public View getChildAt(int i){return children.get(i);}}''',
'android/view/KeyEvent.java':'package android.view;public class KeyEvent {public static final int ACTION_UP=1,KEYCODE_BACK=4;public KeyEvent(int action,int key){}}',
'android/widget/LinearLayout.java':'package android.widget;public class LinearLayout extends android.view.ViewGroup {public static final int HORIZONTAL=0,VERTICAL=1;public int orientation=VERTICAL;public LinearLayout(android.content.Context c){super(c);}public int getOrientation(){return orientation;}}',
'android/widget/TextView.java':'''package android.widget;public class TextView extends android.view.View {public String text="";public float size=14;public TextView(android.content.Context c){super(c);}public CharSequence getText(){return text;}public void setText(CharSequence v){text=v.toString();}public float getTextSize(){return size;}public android.graphics.Typeface getTypeface(){return new android.graphics.Typeface();}}''',
'android/widget/Button.java':'package android.widget;public class Button extends TextView {public Button(android.content.Context c){super(c);clickable=true;}}',
'android/widget/EditText.java':'''package android.widget;public class EditText extends TextView {public int inputType;public boolean focusable=true,password;public EditText(android.content.Context c){super(c);}public Object getTransformationMethod(){return password?new android.text.method.PasswordTransformationMethod():null;}public Object getKeyListener(){return focusable?this:null;}public int getMaxLines(){return 1;}public int getInputType(){return inputType;}public CharSequence getHint(){return "Input";}public boolean isFocusable(){return focusable;}public void setSelection(int value){}public int length(){return text.length();}}''',
'android/widget/ProgressBar.java':'package android.widget;public class ProgressBar extends android.view.View {public ProgressBar(android.content.Context c){super(c);}public boolean isIndeterminate(){return false;}public int getMax(){return 100;}public int getProgress(){return 60;}}',
'android/widget/ImageView.java':'package android.widget;public class ImageView extends android.view.View {public ImageView(android.content.Context c){super(c);}public Object getDrawable(){return null;}}',
'android/graphics/Typeface.java':'package android.graphics;public class Typeface {public boolean isBold(){return false;}}',
'android/graphics/drawable/BitmapDrawable.java':'package android.graphics.drawable;public class BitmapDrawable {public android.graphics.Bitmap getBitmap(){return null;}}',
'android/text/method/PasswordTransformationMethod.java':'package android.text.method;public class PasswordTransformationMethod {}',
'android/text/InputType.java':'package android.text;public class InputType {public static final int TYPE_TEXT_FLAG_MULTI_LINE=131072;}',
'android/util/Base64.java':'package android.util;public class Base64 {public static final int NO_WRAP=2;public static String encodeToString(byte[] v,int flags){return java.util.Base64.getEncoder().encodeToString(v);}}',
})
STUBS['android/app/Activity.java']=STUBS['android/app/Activity.java'].replace('public Intent lastIntent;', 'public boolean isDestroyed(){return false;}public Intent lastIntent;')
STUBS['android/util/DisplayMetrics.java']=STUBS['android/util/DisplayMetrics.java'].replace('public int widthPixels','public float scaledDensity=1;public int widthPixels')
STUBS['android/content/res/Resources.java']=STUBS['android/content/res/Resources.java'].replace('public Configuration getConfiguration()', 'public android.util.DisplayMetrics getDisplayMetrics(){return new android.util.DisplayMetrics();}public Configuration getConfiguration()')
STUBS['android/graphics/Bitmap.java']=STUBS['android/graphics/Bitmap.java'].replace('public enum CompressFormat {PNG}', 'public enum CompressFormat {PNG,JPEG}').replace('public void recycle()', 'public int getWidth(){return w;}public int getHeight(){return h;}public boolean hasAlpha(){return true;}public static Bitmap createScaledBitmap(Bitmap b,int w,int h,boolean filter){return b;}public void recycle()')
def compile_at(root):
 for name,text in STUBS.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 compiler=[shutil.which('javac')] if shutil.which('javac') else ['java','-m','jdk.compiler/com.sun.tools.javac.Main']
 sources=[SOURCE/(name+'.java') for name in ['JsonValue','FileOps','ShareTargets','NativeBridgePolicy','WebSettingsProtocol','WebSettingsHost']]
 subprocess.run([*compiler,'-encoding','UTF-8','-d',str(root/'classes'),*map(str,[*root.rglob('*.java'),*sources,ROOT/'tests/WebSettingsHostTest.java'])],check=True)
 return root/'classes'
if __name__=='__main__':
 with tempfile.TemporaryDirectory(prefix='dsh-web-settings-') as directory:
  classes=compile_at(pathlib.Path(directory));subprocess.run(['java','-cp',str(classes),'dev.dsh.nativeapp.WebSettingsHostTest'],check=True)
  if len(sys.argv)>1 and sys.argv[1]=='--consumer':subprocess.run(['node',str(ROOT/'tests/js/web-settings-consumer.mjs'),*sys.argv[2:],str(classes)],check=True)
