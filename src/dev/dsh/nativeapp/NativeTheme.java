package dev.dsh.nativeapp;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Recolor retained controls and drawable states; never rebuild views or edit their contents. */
final class NativeTheme {
    enum Role { BG, CARD, BORDER, FIELD, FIELD_FOCUS, BTN, BTN_PRESS, ACCENT, BRAND,
        ACCENT_DARK, SUCCESS, WARN, ERROR, TEXT, TEXT_2, TEXT_3, ON_ACCENT, DIMMED,
        RIPPLE_PRIMARY, RIPPLE_SECONDARY }
    private static final WeakHashMap<TextView, Role> texts = new WeakHashMap<TextView, Role>();
    private static final WeakHashMap<Drawable, Surface> surfaces = new WeakHashMap<Drawable, Surface>();
    private static final Role[] textRoles = {Role.TEXT, Role.TEXT_2, Role.TEXT_3,
        Role.SUCCESS, Role.WARN, Role.ERROR, Role.BRAND, Role.ON_ACCENT};

    private static final class Surface {
        final WeakReference<Context> owner;
        final Role fill, stroke;
        final int width;
        Surface(Context context, Role fill, Role stroke, int width) {
            owner = new WeakReference<Context>(context);
            this.fill = fill; this.stroke = stroke; this.width = width;
        }
    }

    static <T extends TextView> T text(T view, Role role) {
        synchronized (texts) { texts.put(view, role); }
        return view;
    }
    static <T extends Drawable> T surface(Context owner, T drawable, Role fill, Role stroke, int width) {
        synchronized (surfaces) { surfaces.put(drawable, new Surface(owner, fill, stroke, width)); }
        return drawable;
    }
    static int color(int[] palette, Role role) { return palette[role.ordinal()]; }

    private static boolean owns(Context expected, Context actual) {
        for (int depth = 0; actual != null && depth < 32; depth++) {
            if (actual == expected) return true;
            if (!(actual instanceof ContextWrapper)) return false;
            Context next = ((ContextWrapper) actual).getBaseContext();
            if (next == actual) return false;
            actual = next;
        }
        return false;
    }

    /** Keep async success/error colors; unknown custom colors remain unchanged. */
    private static Role textRole(int current, Role fallback, int[] old) {
        if (current == color(old, fallback)) return fallback;
        for (Role role : textRoles) if (current == color(old, role)) return role;
        return null;
    }

    static void apply(Context owner, int[] old, int[] next) {
        Map<TextView, Role> textSnapshot;
        Map<Drawable, Surface> surfaceSnapshot;
        synchronized (texts) { textSnapshot = new LinkedHashMap<TextView, Role>(texts); }
        synchronized (surfaces) { surfaceSnapshot = new LinkedHashMap<Drawable, Surface>(surfaces); }
        for (Map.Entry<TextView, Role> item : textSnapshot.entrySet()) {
            TextView view = item.getKey();
            if (!owns(owner, view.getContext())) continue;
            Role role = textRole(view.getCurrentTextColor(), item.getValue(), old);
            if (role != null) view.setTextColor(color(next, role));
            if (view instanceof EditText) view.setHintTextColor(color(next, Role.TEXT_3));
        }
        for (Map.Entry<Drawable, Surface> item : surfaceSnapshot.entrySet()) {
            Drawable drawable = item.getKey(); Surface style = item.getValue();
            if (!owns(owner, style.owner.get())) continue;
            int fill = color(next, style.fill);
            if (style.width < 0) {
                drawable.setColorFilter(fill, android.graphics.PorterDuff.Mode.SRC_IN);
            } else if (drawable instanceof GradientDrawable) {
                GradientDrawable gradient = (GradientDrawable) drawable;
                gradient.setColor(fill);
                if (style.stroke != null) gradient.setStroke(style.width, color(next, style.stroke));
            } else if (drawable instanceof RippleDrawable) {
                ((RippleDrawable) drawable).setColor(ColorStateList.valueOf(fill));
            } else if (drawable instanceof ShapeDrawable) {
                ((ShapeDrawable) drawable).getPaint().setColor(fill);
            }
        }
    }

    static void detach(Context owner) {
        synchronized (texts) { texts.keySet().removeIf(view -> owns(owner, view.getContext())); }
        synchronized (surfaces) { surfaces.values().removeIf(style -> owns(owner, style.owner.get())); }
    }
}
