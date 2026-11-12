package local.mio.gt7profodfix;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Bundle;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Keep the OplusCamera viewfinder UI in portrait with rotating icons.
 *
 * Icon rotation lives in Camera.E0, which only runs when the activity
 * rotation decision's l() is true.  That needs the "sensor" decision
 * (com.oplus.camera.support.activity.rotate, stock default "1") after it has
 * taken its forcePortrait branch.
 *
 * Camera$o.onOrientationChanged asks Util.M0() whether the activity should
 * "follow system": with pref_photo_preview_rotate_key at its default
 * "follow_system" and system auto-rotate on, M0 is true, the camera requests
 * SCREEN_ORIENTATION_SENSOR, the whole activity rotates and the camera stops
 * turning its icons itself.  Make M0 false so the camera takes its own
 * "forcePortrait" path (portrait activity, icons follow gravity) regardless of
 * the system switch.
 *
 * The manifest also declares screenOrientation="behind", which on HyperOS can
 * let the system rotate the activity before the listener runs, so portrait is
 * requested at onCreate and any rotation-following request is mapped to
 * portrait.
 */
final class CameraOrientationHooks {
    private static final String CAMERA_ACTIVITY = "com.oplus.camera.Camera";
    // OplusCamera 6.070.45: Util.M0(Context) == "activity follows system rotation".
    private static final String UTIL_CLASS = "com.oplus.camera.util.Util";
    private static final String FOLLOW_SYSTEM_METHOD = "M0";
    private static final String CAMERA_CONFIG_CLASS = "com.oplus.camera.configure.CameraConfig";
    private static final String ACTIVITY_ROTATE_KEY = "com.oplus.camera.support.activity.rotate";
    // o6.a = ActivityRotationDecision factory, o6.g = SensorActivityRotationDecision.
    private static final String DECISION_FACTORY = "o6.a";
    private static final String SENSOR_DECISION = "o6.g";
    private static final ThreadLocal<Boolean> IN_E0 = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private CameraOrientationHooks() {
    }

    static void install(ClassLoader loader) {
        Class<?> camera = XposedHelpers.findClassIfExists(CAMERA_ACTIVITY, loader);
        if (camera == null) {
            HookLog.info("camera orientation: " + CAMERA_ACTIVITY + " missing");
            return;
        }

        // Stock unit SDK default is "1"; on the port CameraConfig resolves it to
        // false, which selects PortraitActivityRotationDecision whose l() is
        // always false, so Camera.E0 never pushes the angle to the UI widgets.
        try {
            XposedHelpers.findAndHookMethod(CAMERA_CONFIG_CLASS, loader, "getConfigBooleanValue",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (ACTIVITY_ROTATE_KEY.equals(param.args[0])) {
                                param.setResult(true);
                            }
                        }
                    });
            HookLog.info("camera orientation: activity rotate decision enabled");
        } catch (Throwable error) {
            HookLog.error("camera orientation: CameraConfig hook failed", error);
        }

        try {
            // M0 means "activity follows the sensor" in onOrientationChanged
            // (keep false) but "rotate the UI widgets" inside Camera.E0 (true).
            XposedHelpers.findAndHookMethod(UTIL_CLASS, loader, FOLLOW_SYSTEM_METHOD,
                    android.content.Context.class, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            return IN_E0.get();
                        }
                    });
            HookLog.info("camera orientation: follow-system disabled");
        } catch (Throwable error) {
            HookLog.error("camera orientation: Util." + FOLLOW_SYSTEM_METHOD + " hook failed", error);
        }

        XposedBridge.hookAllMethods(Activity.class, "setRequestedOrientation", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!camera.isInstance(param.thisObject)
                        || param.args.length == 0 || !(param.args[0] instanceof Integer)) {
                    return;
                }
                int requested = (Integer) param.args[0];
                if (followsRotation(requested)) {
                    param.args[0] = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                    HookLog.once("camera_orientation_" + requested,
                            "camera orientation: " + requested + " -> portrait");
                }
            }
        });

        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (camera.isInstance(param.thisObject)) {
                    ((Activity) param.thisObject).setRequestedOrientation(
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                }
            }
        });
        HookLog.info("camera orientation: portrait lock installed");
        installForcePortrait(loader);
    }

    /**
     * Camera$o.onOrientationChanged only reaches decision.i() ("forcePortrait")
     * behind checks that fail on the port (mode nb() and Util.A0, which needs
     * ColorOS Gallery metadata and Oplus FlexibleTaskView), so the sensor
     * decision never sets its portrait flag and l() stays false.  Enter that
     * state right before Camera.E0 dispatches the angle to the UI.
     */
    private static void installForcePortrait(ClassLoader loader) {
        try {
            Class<?> decisions = XposedHelpers.findClass(DECISION_FACTORY, loader);
            XposedBridge.hookAllMethods(XposedHelpers.findClass(CAMERA_ACTIVITY, loader), "E0",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length == 0 || !(param.args[0] instanceof Activity)) {
                                return;
                            }
                            try {
                                Object decision = XposedHelpers.callStaticMethod(decisions, "a");
                                if (decision == null
                                        || !SENSOR_DECISION.equals(decision.getClass().getName())
                                        || (Boolean) XposedHelpers.callMethod(decision, "l")) {
                                    return;
                                }
                                XposedHelpers.callMethod(decision, "i", param.args[0]);
                                HookLog.once("camera_force_portrait",
                                        "camera orientation: decision forced portrait");
                            } catch (Throwable error) {
                                HookLog.error("camera orientation: force portrait failed", error);
                            } finally {
                                IN_E0.set(Boolean.TRUE);
                                // E0's widget branch swaps 90/270 because it assumes the
                                // activity itself rotated; it stays portrait here, so
                                // pre-swap to hand the widgets the real sensor angle.
                                if (param.args.length > 1 && param.args[1] instanceof Integer) {
                                    int angle = (Integer) param.args[1];
                                    if (angle == 90) {
                                        param.args[1] = 270;
                                    } else if (angle == 270) {
                                        param.args[1] = 90;
                                    }
                                }
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            IN_E0.set(Boolean.FALSE);
                        }
                    });
        } catch (Throwable error) {
            HookLog.error("camera orientation: E0 hook failed", error);
        }
    }

    private static boolean followsRotation(int orientation) {
        switch (orientation) {
            case ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED:
            case ActivityInfo.SCREEN_ORIENTATION_USER:
            case ActivityInfo.SCREEN_ORIENTATION_BEHIND:
            case ActivityInfo.SCREEN_ORIENTATION_SENSOR:
            case ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR:
            case ActivityInfo.SCREEN_ORIENTATION_FULL_USER:
                return true;
            default:
                return false;
        }
    }
}
