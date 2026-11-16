package dev.colorcc;

import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Samsung One UI 8 / Android 16 compatibility probe and conservative fallback.
 * The original ColorCC hook code and renderer are kept, not replaced.
 * Never suppress or remove a stock view; use only verified and measured hosts.
 */
public final class OneUI8PanelFallback {
    private static final String PREFIX = "CombineCC [OneUI8 bridge] ";
    private static final WeakHashMap<ViewGroup,Integer> attempts = new WeakHashMap<>();
    private static final WeakHashMap<View,Boolean> observed = new WeakHashMap<>();
    private static final int MAX_ATTACH_ATTEMPTS = 2;
    private static boolean installed;

    private OneUI8PanelFallback() {}

    private static void log(String value) {
        try { XposedBridge.log(PREFIX + value); }
        catch (Throwable ignored) { }
    }

    private static String oneUiVersion() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Method getter = props.getDeclaredMethod("get", String.class, String.class);
            return (String)getter.invoke(null, "ro.build.version.oneui", "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean onOneUi8() {
        if (Build.VERSION.SDK_INT != 36) return false;
        if (!"samsung".equalsIgnoreCase(Build.MANUFACTURER)
                && !"samsung".equalsIgnoreCase(Build.BRAND)) return false;
        String version = oneUiVersion();
        if (version.length() == 0) {
            log("Samsung SDK 36 detected, oneui property not readable; using guarded probe");
            return true;
        }
        try {
            int code = Integer.parseInt(version);
            if (code >= 80000 && code < 90000) return true;
            log("Unsupported One UI property: " + version + "; keeping original hooks");
            return false;
        } catch (NumberFormatException ignored) {
            log("Unrecognized One UI property: " + version + "; keeping original hooks");
            return false;
        }
    }

    private static boolean panelPresent(ViewGroup host) {
        View panel = Hook.access$400();
        if (panel == null) return false;
        // Never add two panels to the same container or over a working panel.
        if (panel.getParent() == host || panel.isAttachedToWindow()) return true;
        return false;
    }

    private static void attempt(ViewGroup host, String path) {
        if (host == null || !host.isAttachedToWindow()
                || host.getWidth() <= 0 || host.getHeight() <= 0) return;
        if (panelPresent(host)) return;
        synchronized (attempts) {
            Integer count = attempts.get(host);
            if (count != null && count >= MAX_ATTACH_ATTEMPTS) return;
            attempts.put(host, count == null ? 1 : count + 1);
        }
        // Never add children from within ViewGroup.onLayout().
        host.post(new Runnable() {
            @Override public void run() {
                if (!host.isAttachedToWindow() || panelPresent(host)) return;
                try {
                    Hook.access$2200(host); // original ColorCC Panel constructor and addView()
                    View result = Hook.access$400();
                    if (result != null && result.getParent() == host) {
                        log("ATTACH_CREATED via " + path + ", host=" + host.getClass().getName()
                                + " (visual visibility still unverified)");
                    } else {
                        log("ATTACH_NOT_CREATED via " + path);
                    }
                } catch (Throwable error) {
                    log("ATTACH_FAILED via " + path + ": " + error.toString());
                }
            }
        });
    }

    private static String entryName(View view) {
        if (view.getId() == View.NO_ID) return "";
        try { return view.getResources().getResourceEntryName(view.getId()); }
        catch (Throwable ignored) { return ""; }
    }

    private static boolean isSafeComposeHost(FrameLayout host, View content) {
        if (!host.isAttachedToWindow() || host.getChildCount() > 8
                || host.getWidth() <= 0 || host.getHeight() <= 0) return false;
        // Never try to inject as a child of a ComposeView.
        if (host.getClass().getName().contains("Compose")) return false;
        String name = entryName(host).toLowerCase(java.util.Locale.ROOT);
        String type = host.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        if (!(name.contains("qs") || name.contains("quick")
                || type.contains("qs") || type.contains("quicksetting"))) return false;
        if (content != host && content.getParent() != host) return false;
        return true;
    }

    private static FrameLayout locateComposeHost(View content) {
        // The verified QSFragmentCompose.onCreateView() returns View. It can
        // return a ComposeView, which must NOT have arbitrary children added.
        if (content instanceof FrameLayout
                && isSafeComposeHost((FrameLayout)content, content)) {
            return (FrameLayout)content;
        }
        ViewParent parent = content.getParent();
        if (parent instanceof FrameLayout
                && isSafeComposeHost((FrameLayout)parent, content)) {
            return (FrameLayout)parent;
        }
        return null;
    }

    private static void observeComposeRoot(final View root) {
        synchronized (observed) {
            if (observed.containsKey(root)) return;
            observed.put(root, Boolean.TRUE);
        }
        if (root.isAttachedToWindow()) {
            checkComposeRoot(root, 0);
        } else {
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {
                    v.removeOnAttachStateChangeListener(this);
                    checkComposeRoot(v, 0);
                }
                @Override public void onViewDetachedFromWindow(View v) {
                    v.removeOnAttachStateChangeListener(this);
                }
            });
        }
    }

    private static void checkComposeRoot(final View root, final int frames) {
        root.post(new Runnable() {
            @Override public void run() {
                if (!root.isAttachedToWindow()) return;
                FrameLayout host = locateComposeHost(root);
                if (host != null) {
                    log("Compose host recognized: " + entryName(host));
                    attempt(host, "QSFragmentCompose.onCreateView");
                } else if (frames < 4) {
                    root.postOnAnimation(new Runnable() {
                        @Override public void run() { checkComposeRoot(root, frames + 1); }
                    });
                } else {
                    log("COMPOSE_HOST_NOT_SAFE: content=" + root.getClass().getName()
                            + ", parent=" + String.valueOf(root.getParent())
                            + "; stock panel unchanged");
                }
            }
        });
    }

    public static synchronized void install(Class<?> qsContainerClass) {
        if (installed || !onOneUi8() || qsContainerClass == null) return;
        installed = true;
        // Verified declared on the supplied One UI 8 APK:
        // QSContainerImpl.onLayout(boolean,int,int,int,int)
        try {
            XposedBridge.hookAllMethods(qsContainerClass, "onLayout", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof ViewGroup) {
                        attempt((ViewGroup)param.thisObject, "QSContainerImpl.onLayout");
                    }
                }
            });
            log("Registered legacy container layout hook");
        } catch (Throwable e) {
            log("Legacy hook unavailable: " + e.toString());
        }

        // Verified declared on the supplied One UI 8 APK:
        // QSFragmentCompose.onCreateView(LayoutInflater,ViewGroup,Bundle): View
        try {
            Class<?> compose = Class.forName(
                    "com.android.systemui.qs.composefragment.QSFragmentCompose",
                    false, qsContainerClass.getClassLoader());
            XposedBridge.hookAllMethods(compose, "onCreateView", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object value = param.getResult();
                    if (value instanceof View) observeComposeRoot((View)value);
                }
            });
            log("Registered Compose view observer; stock content not suppressed");
        } catch (Throwable e) {
            log("Compose observer unavailable: " + e.toString());
        }
    }
}
