package local.mio.gt7profodfix;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Keeps 音质音效 (com.miui.misound) from crashing on RMX5010.
 *
 * The headset page reads the "effect_selection" drop-down value and calls
 * equals() on it in several places (refresh, onPreferenceChange, onDestroy).
 * The value is only ever set when Dolby or MiSound (Dirac) is supported.
 * RMX5010 has neither: no ro.vendor.audio.dolby.dax.support and no Dirac
 * effect in the AIDL effect HAL. So the value stays null and the page dies
 * with an NPE in onCreatePreferences.
 *
 * Returning "none" for that one preference is exactly the state the app
 * would compute itself when no effect backend exists: the Dolby/MiSound
 * category and the graphic EQ entry stay hidden, the rest of the page works.
 */
final class MiSoundHooks {
    private static final String KEY = "effect_selection";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private MiSoundHooks() {
    }

    static void install(ClassLoader classLoader) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        XposedHelpers.findAndHookMethod("miuix.preference.DropDownPreference",
                classLoader, "getValue", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getResult() != null) return;
                        Object key;
                        try {
                            key = XposedHelpers.callMethod(param.thisObject, "getKey");
                        } catch (Throwable ignored) {
                            return;
                        }
                        if (!KEY.equals(key)) return;
                        param.setResult("none");
                        HookLog.once("misound-none",
                                "MiSound: effect_selection has no backend, using none");
                    }
                });
        HookLog.info("MiSound: effect_selection null guard installed");
    }
}
