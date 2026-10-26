package dev.dsh.nativeapp;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.os.*;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.util.*;

/** Presents the existing settings controllers as DSH Web components, without Android windows. */
final class WebSettingsHost {
    interface Launcher { void open(String page); default void rememberCodeBuddy() throws Exception { } default void file(String action,String path) throws Exception { } }
    private static final WeakHashMap<View,Boolean> primaryButtons = new WeakHashMap<View,Boolean>();
    static void primary(View view,boolean value) { primaryButtons.put(view,value); }
    private static WeakReference<WebSettingsHost> active = new WeakReference<WebSettingsHost>(null);
    final Activity owner;
    final String session = UUID.randomUUID().toString().replace("-", "");
    private final Launcher launcher;
    private final ArrayList<PageDialog> stack = new ArrayList<PageDialog>();
    private final WeakHashMap<View,String> ids = new WeakHashMap<View,String>();
    private final HashMap<String,View> controls = new HashMap<String,View>();
    private int serial;
    private boolean enabled;
    private String page = "data";
    private volatile String notice = "";
    private String ticket = "", inputControl = "";
    private final WeakHashMap<Bitmap,String> images = new WeakHashMap<Bitmap,String>();
    private StringBuilder input = new StringBuilder();

    WebSettingsHost(Activity owner, Launcher launcher) { this.owner = owner; this.launcher = launcher; }
    static WebSettingsHost forContext(Context context) {
        WebSettingsHost host = active.get();
        while (context instanceof ContextWrapper && context != hostOwner(host)) context = ((ContextWrapper)context).getBaseContext();
        return host != null && host.enabled && host.owner == context && !host.owner.isDestroyed() ? host : null;
    }
    private static Activity hostOwner(WebSettingsHost host) { return host == null ? null : host.owner; }
    static Dialog dialog(Context context, View body, View footer) {
        WebSettingsHost host = forContext(context); return host == null ? null : host.new PageDialog(context,body,footer);
    }
    static boolean toast(Context context, CharSequence text) {
        WebSettingsHost host = forContext(context); if (host == null) return false;
        host.notice = text == null ? "" : text.toString(); return true;
    }
    JSONObject request(Map<?,?> command) throws Exception {
        if (!session.equals(command.get("session"))) throw new IllegalArgumentException("Settings page expired");
        String op = WebSettingsProtocol.text(command,"op");
        if ("codebuddy-default".equals(op)) launcher.rememberCodeBuddy();
        else if ("file".equals(op)) {
            String action=WebSettingsProtocol.text(command,"action");
            if ("edit".equals(action) || "browse".equals(action)) {
                close();enabled=true;active=new WeakReference<WebSettingsHost>(this);page="files";notice="";
            }
            launcher.file(action,WebSettingsProtocol.text(command,"path"));
        } else if ("open".equals(op)) {
            close(); enabled = true; active = new WeakReference<WebSettingsHost>(this); notice = "";
            page = WebSettingsProtocol.text(command,"page"); launcher.open(page);
        } else if ("close".equals(op)) close();
        else if (!enabled) throw new IllegalArgumentException("Open a settings page first");
        else if ("back".equals(op)) { if (!stack.isEmpty()) stack.get(stack.size()-1).back(); }
        else if (!"state".equals(op)) {
            View view = controls.get(command.get("control"));
            if (view == null || !view.isEnabled() || view.getVisibility() != View.VISIBLE || !isLive(view))
                throw new IllegalArgumentException("This control is no longer available");
            if ("act".equals(op)) { if (!view.isClickable()) throw new IllegalArgumentException("Not an action"); view.performClick(); }
            else if ("long".equals(op)) { if (!view.isLongClickable()) throw new IllegalArgumentException("Not an action"); view.performLongClick(); }
            else if (view instanceof EditText) {
                EditText field = (EditText)view;
                int offset = ((Number)command.get("offset")).intValue();
                if ("read".equals(op)) {
                    if (password(field)) throw new IllegalArgumentException("Stored credentials cannot be read");
                    String value = field.getText().toString();
                    if (offset > value.length()) throw new IllegalArgumentException("Text changed");
                    return new JSONObject().put("value",value.substring(offset,Math.min(value.length(),offset+32768))).put("length",value.length());
                }
                if ("input".equals(op)) {
                    if (!field.isFocusable() || field.getKeyListener()==null) throw new IllegalArgumentException("This field is read-only");
                    String next = WebSettingsProtocol.text(command,"ticket");
                    if (offset == 0) { ticket = next; inputControl = (String)command.get("control"); input = new StringBuilder(); }
                    if (!next.equals(ticket) || !command.get("control").equals(inputControl) || offset != input.length())
                        throw new IllegalArgumentException("Text update expired");
                    input.append(WebSettingsProtocol.text(command,"value"));
                    if (input.length() > 2*1024*1024) { input = new StringBuilder(); throw new IllegalArgumentException("Text too large"); }
                    if (Boolean.TRUE.equals(command.get("done"))) {
                        String value = input.toString(); input = new StringBuilder(); ticket = "";
                        field.setText(value); field.setSelection(field.length());
                    }
                }
            } else throw new IllegalArgumentException("Not a text input");
        }
        return snapshot();
    }
    boolean isEnabled() { return enabled; }
    void reopenRoot() { launcher.open(page); }
    private boolean isLive(View view) {
        if (stack.isEmpty()) return false;
        PageDialog top = stack.get(stack.size()-1);
        for (View candidate = view; candidate != null; candidate = candidate.getParent() instanceof View ? (View)candidate.getParent() : null) {
            if (candidate.getVisibility() != View.VISIBLE || !candidate.isEnabled()) return false;
            if (candidate == top.body || candidate == top.footer) return true;
        }
        return false;
    }
    JSONObject snapshot() throws JSONException {
        controls.clear(); JSONObject result = new JSONObject().put("notice",notice).put("open",enabled);
        JSONArray pages = new JSONArray(); int[] count = {0};
        for (PageDialog dialog : new ArrayList<PageDialog>(stack)) if (dialog.visible)
            pages.put(new JSONObject().put("id",dialog.id).put("body",node(dialog.body,count)).put("footer",node(dialog.footer,count)).put("cancelable",dialog.cancelable));
        return result.put("pages",pages);
    }
    private static boolean enabled(View view) {
        for(View current=view;current!=null;current=current.getParent() instanceof View?(View)current.getParent():null)
            if(!current.isEnabled())return false;
        return true;
    }
    private JSONObject node(View view, int[] count) throws JSONException {
        if (view == null || view.getVisibility() != View.VISIBLE) return null;
        if (++count[0] > 6000) throw new IllegalArgumentException("Too many settings controls");
        String id = ids.get(view); if (id == null) { id = "v"+(++serial); ids.put(view,id); }
        controls.put(id,view);
        JSONObject out = new JSONObject().put("id",id).put("enabled",enabled(view)).put("selected",view.isSelected() || view.isActivated()).put("primary",Boolean.TRUE.equals(primaryButtons.get(view)))
                .put("clickable",view.isClickable()).put("long",view.isLongClickable());
        if (view instanceof EditText) {
            EditText edit = (EditText)view; boolean secret = password(edit); String value = edit.getText().toString();
            out.put("type","input").put("password",secret).put("value",secret?"":value.substring(0,Math.min(value.length(),32768)))
                    .put("readonly",!edit.isFocusable() || edit.getKeyListener()==null)
                    .put("length",secret?0:value.length()).put("configured",secret&&!value.isEmpty()).put("hint",edit.getHint())
                    .put("multiline",(edit.getInputType() & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)!=0 || edit.getMaxLines()>1);
        } else if (view instanceof TextView) {
            TextView text = (TextView)view;
            out.put("type",view instanceof Button ? "button" : "text").put("text",text.getText().toString())
                    .put("large",text.getTextSize()/owner.getResources().getDisplayMetrics().scaledDensity>=17)
                    .put("bold",text.getTypeface()!=null&&text.getTypeface().isBold());
        } else if (view instanceof ProgressBar) {
            ProgressBar bar = (ProgressBar)view; out.put("type","progress").put("indeterminate",bar.isIndeterminate()).put("max",bar.getMax()).put("value",bar.getProgress());
        } else if (view instanceof ImageView) {
            out.put("type","image");
            if (((ImageView)view).getDrawable() instanceof BitmapDrawable) {
                Bitmap original = ((BitmapDrawable)((ImageView)view).getDrawable()).getBitmap();
                String encoded = images.get(original);
                if (encoded == null) {
                    int[] size = NativeBridgePolicy.captureSize(original.getWidth(),original.getHeight());
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    String mime = original.hasAlpha()?"image/png":"image/jpeg";
                    for (int attempt=0;attempt<4;attempt++) {
                        Bitmap image = Bitmap.createScaledBitmap(original,size[0],size[1],true); bytes.reset();
                        try { image.compress(original.hasAlpha()?Bitmap.CompressFormat.PNG:Bitmap.CompressFormat.JPEG,85,bytes); }
                        finally { if(image!=original)image.recycle(); }
                        if(bytes.size()<2*1024*1024)break;size[0]=Math.max(1,size[0]/2);size[1]=Math.max(1,size[1]/2);
                    }
                    if(bytes.size()<2*1024*1024)encoded="data:"+mime+";base64,"+android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP);
                    if(encoded!=null)images.put(original,encoded);
                }
                if(encoded!=null)out.put("src",encoded);
            }
        } else out.put("type","group");
        if (view instanceof ViewGroup) {
            JSONArray children = new JSONArray(); ViewGroup group = (ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) { JSONObject child = node(group.getChildAt(i),count); if(child!=null)children.put(child); }
            out.put("children",children).put("row",view instanceof LinearLayout&&((LinearLayout)view).getOrientation()==LinearLayout.HORIZONTAL);
        }
        return out;
    }
    private static boolean password(EditText field) { return field.getTransformationMethod() instanceof PasswordTransformationMethod
            || (field.getInputType() & 0x80)!=0; }
    void close() {
        enabled = false;
        for (PageDialog dialog : new ArrayList<PageDialog>(stack)) dialog.dismiss();
        stack.clear(); controls.clear(); images.clear(); input = new StringBuilder(); ticket = "";
        if(active.get()==this)active.clear();
    }
    final class PageDialog extends Dialog {
        final String id = UUID.randomUUID().toString(); final View body,footer;
        boolean visible,cancelable=true;
        DialogInterface.OnDismissListener dismissed; DialogInterface.OnShowListener shown; DialogInterface.OnCancelListener cancelled; DialogInterface.OnKeyListener key;
        PageDialog(Context c, View body, View footer) { super(c);this.body=body;this.footer=footer; }
        @Override public void show() { if(!enabled||visible)return;visible=true;stack.add(this);if(shown!=null)shown.onShow(this); }
        @Override public boolean isShowing() { return visible; }
        @Override public void dismiss() { if(!visible)return;visible=false;stack.remove(this);if(dismissed!=null)dismissed.onDismiss(this); }
        @Override public void cancel() { if(!cancelable)return;if(cancelled!=null)cancelled.onCancel(this);dismiss(); }
        @Override public void setCancelable(boolean value) { cancelable=value; }
        @Override public void setOnShowListener(DialogInterface.OnShowListener value) { shown=value; }
        @Override public void setOnDismissListener(DialogInterface.OnDismissListener value) { dismissed=value; }
        @Override public void setOnCancelListener(DialogInterface.OnCancelListener value) { cancelled=value; }
        @Override public void setOnKeyListener(DialogInterface.OnKeyListener value) { key=value; }
        void back() { if(!cancelable)return;if(key==null||!key.onKey(this,KeyEvent.KEYCODE_BACK,new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK)))cancel(); }
    }
}
