package dev.colorcc;

import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * One UI 8 only. Keeps the original onFinishInflate injection, adds a
 * bounded after-layout retry when it failed or the initial root was detached.
 *
 * This does not provide a Compose-surface port. A matching SystemUI class
 * alone does not establish that the root is active on every device.
 */
public final class OneUI8PanelFallback {
    private OneUI8PanelFallback() { }

    private static final WeakHashMap<ViewGroup, Integer> attempts = new WeakHashMap<>();
    private static final int MAX_RETRIES = 2;

    private static void log(String message) {
        try { XposedBridge.log("CombineCC [OneUI8 fallback] " + message); }
        catch (Throwable ignored) { }
    }

    private static String oneUiVersion() {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method get = systemProperties.getDeclaredMethod("get", String.class, String.class);
            return (String) get.invoke(null, "ro.build.version.oneui", "");
        } catch (Throwable e) {
            return "";
        }
    }

    static boolean isSamsungOneUI8() {
        if (Build.VERSION.SDK_INT != 36) return false;
        if (!"samsung".equalsIgnoreCase(Build.MANUFACTURER)
                && !"samsung".equalsIgnoreCase(Build.BRAND)) return false;
        String raw = oneUiVersion();
        if (!raw.isEmpty()) {
            try {
                int version = Integer.parseInt(raw);
                return version >= 80000 && version < 90000;
            } catch (NumberFormatException e) {
                log("Unrecognized ro.build.version.oneui=" + raw);
                return false;
            }
        }
        // Samsung's One UI property isn't available on every vendor build.
        // Log the uncertainty instead of pretending it was verified.
        log("One UI property unavailable; Samsung Android 16 fallback path");
        return true;
    }

    public static void install(Class<?> qsContainerImpl) {
        if (!isSamsungOneUI8()) return;
        if (qsContainerImpl == null
                || !ViewGroup.class.isAssignableFrom(qsContainerImpl)) return;
        try {
            // onLayout(ZIIII)V is declared on the supplied One UI 8
            // QSContainerImpl; onAttachedToWindow() is NOT declared there.
            XposedBridge.hookAllMethods(qsContainerImpl, "onLayout", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof ViewGroup)) return;
                    final ViewGroup root = (ViewGroup) param.thisObject;
                    if (!root.isAttachedToWindow() || root.getWidth() == 0
                            || root.getHeight() == 0) return;

                    View current = Hook.access$400();
                    if (current != null && current.isAttachedToWindow()) return;

                    synchronized (attempts) {
                        int count = attempts.containsKey(root) ? attempts.get(root) : 0;
                        if (count >= MAX_RETRIES) return;
                        attempts.put(root, count + 1);
                    }
                    // Do not add child views while ViewGroup is in onLayout().
                    root.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (!root.isAttachedToWindow()) return;
                                View existing = Hook.access$400();
                                if (existing != null && existing.isAttachedToWindow()) return;
                                Hook.access$2200(root);
                                View created = Hook.access$400();
                                if (created != null && created.getParent() == root) {
                                    log("Custom panel reattached after layout");
                                } else {
                                    log("Panel not available after bounded retry");
                                }
                            } catch (Throwable e) {
                                log("Retry failed: " + e.getClass().getName() + ": " + e.getMessage());
                            }
                        }
                    });
                }
            });
            log("Registered bounded QSContainerImpl.onLayout fallback");
        } catch (Throwable e) {
            log("Fallback hook not installed: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }
}
