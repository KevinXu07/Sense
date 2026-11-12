package local.mio.gt7profodfix;

import android.animation.Animator;

import java.lang.reflect.Method;
import java.util.List;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Face enrollment stalls at the first progress circle because circle_0 has no
 * AnimatorListener: only views 1..4 get "new $15(fragment)" inside
 * initProgressView(), and only $15.onAnimationEnd resets mFaceEnrollAnimating.
 * Append the same listener to view 0 after initProgressView() returns.
 */
final class FaceEnrollHooks {
    private static final String FRAGMENT =
            "com.android.settings.faceunlock.MiuiNormalCameraMultiFaceInput$NewMultiFaceEnrollFragment";
    private static final String LISTENER = FRAGMENT + "$15";

    private static final WeakHashMap<Object, Boolean> PATCHED = new WeakHashMap<>();

    private FaceEnrollHooks() {
    }

    static void install(ClassLoader loader) {
        Class<?> fragment;
        try {
            fragment = XposedHelpers.findClass(FRAGMENT, loader);
        } catch (Throwable error) {
            HookLog.error("face enroll: fragment class missing", error);
            return;
        }
        if (fragment == null) {
            HookLog.info("face enroll: fragment class missing");
            return;
        }
        XposedBridge.hookAllMethods(fragment, "initProgressView", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object self = param.thisObject;
                if (self == null) {
                    return;
                }
                synchronized (PATCHED) {
                    if (PATCHED.containsKey(self)) {
                        return;
                    }
                }
                try {
                    Object views = XposedHelpers.getObjectField(self, "mFaceInputProgressViews");
                    Object first = null;
                    if (views instanceof List) {
                        List<?> list = (List<?>) views;
                        if (!list.isEmpty()) {
                            first = list.get(0);
                        }
                    } else if (views instanceof Object[]) {
                        Object[] array = (Object[]) views;
                        if (array.length > 0) {
                            first = array[0];
                        }
                    }
                    if (first == null) {
                        HookLog.once("face_no_view", "face enroll: progress view list empty");
                        return;
                    }
                    Class<?> listenerClass = XposedHelpers.findClass(LISTENER, loader);
                    if (listenerClass == null) {
                        HookLog.once("face_no_listener", "face enroll: $15 listener missing");
                        return;
                    }
                    Object listener = XposedHelpers.newInstance(listenerClass, self);
                    Method add = first.getClass().getMethod(
                            "addAnimatorListener", Animator.AnimatorListener.class);
                    add.invoke(first, listener);
                    synchronized (PATCHED) {
                        PATCHED.put(self, Boolean.TRUE);
                    }
                    HookLog.info("face enroll: listener added to view 0");
                } catch (Throwable error) {
                    HookLog.error("face enroll hook failed", error);
                }
            }
        });
        HookLog.info("face enroll hooks installed");
    }
}
