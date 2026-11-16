package dev.colorcc;

import android.os.Build;
import android.view.View;
import java.lang.reflect.Method;
import java.util.WeakHashMap;
import de.robv.android.xposed.XposedBridge;

/**
 * Fail-open protection for One UI 8 only. The original ColorCC renderer and
 * animation logic are unchanged. Don't remove or fade stock Quick Settings
 * until the custom panel has completed at least two visible draw passes.
 *
 * IMPORTANT: Two frames are NOT proof that the content is correct. This is
 * a guard against immediate blank panels, not device compatibility proof.
 */
public final class OneUI8Health {
    private static final WeakHashMap<Panel, Integer> FRAMES = new WeakHashMap<>();
    private static final boolean IS_ONE_UI_8 = detect();

    private OneUI8Health() {}

    private static void log(String s) {
        try { XposedBridge.log("CombineCC [UI8 stock guard] " + s); }
        catch (Throwable ignored) { }
    }

    private static boolean detect() {
        if (Build.VERSION.SDK_INT != 36 ||
                (!"samsung".equalsIgnoreCase(Build.BRAND)
                && !"samsung".equalsIgnoreCase(Build.MANUFACTURER))) return false;
        String version = "";
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Method get = props.getDeclaredMethod("get", String.class, String.class);
            version = (String)get.invoke(null, "ro.build.version.oneui", "");
        } catch (Throwable ignored) { }
        if (version.isEmpty()) {
            log("One UI property missing; conservative Samsung SDK 36 guard enabled");
            return true;
        }
        try {
            int number = Integer.parseInt(version);
            return number >= 80000 && number < 90000;
        } catch (NumberFormatException ex) {
            log("Unrecognized One UI value: " + version + "; leaving original behavior");
            return false;
        }
    }

    private static boolean healthy(Panel p) {
        if (p == null || !p.isAttachedToWindow() || !p.isShown()
                || p.getVisibility() != View.VISIBLE || p.getAlpha() <= 0.1f
                || p.getWidth() <= 0 || p.getHeight() <= 0
                || p.getChildCount() <= 0) return false;
        synchronized (FRAMES) {
            Integer count = FRAMES.get(p);
            return count != null && count >= 2;
        }
    }

    public static void noteFrameDrawn(Panel p) {
        if (!IS_ONE_UI_8 || p == null || !p.isAttachedToWindow()
                || !p.isShown() || p.getChildCount() <= 0
                || p.getWidth() <= 0 || p.getHeight() <= 0) return;
        synchronized (FRAMES) {
            Integer previous = FRAMES.get(p);
            int next = Math.min(2, (previous == null ? 0 : previous) + 1);
            FRAMES.put(p, next);
            if (next == 2 && (previous == null || previous < 2)) {
                log("Two visible custom panel drawing passes observed; allowing stock transition");
            }
        }
    }

    public static boolean guardStockHide(Panel p, boolean requested) {
        if (!requested || !IS_ONE_UI_8) return requested;
        return healthy(p);
    }

    public static float guardStockAlpha(Panel p, float requested) {
        if (!IS_ONE_UI_8 || healthy(p)) return requested;
        return 1.0f;
    }
}
