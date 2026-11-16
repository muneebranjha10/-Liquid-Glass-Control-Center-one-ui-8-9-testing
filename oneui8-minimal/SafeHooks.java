package dev.colorcc.compat;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Only used for the original unguarded SecQSPanelComposeAdapter
 * isInNonCollapsibleArea hook. On missing One UI 8 APIs, skip just this hook;
 * all other original hooks, panel rendering and animations remain untouched.
 */
public final class SafeHooks {
    private SafeHooks() {}

    public static XC_MethodHook.Unhook findAndHookMethod(
            Class<?> target, String methodName, Object... parameterTypesAndCallback) {
        try {
            return XposedHelpers.findAndHookMethod(target, methodName, parameterTypesAndCallback);
        } catch (Throwable error) {
            try {
                XposedBridge.log("CombineCC OneUI8: optional hook skipped: "
                        + methodName + " (" + error.getClass().getName() + ")");
            } catch (Throwable ignored) { }
            return null;
        }
    }
}
