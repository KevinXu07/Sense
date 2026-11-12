package local.mio.gt7profodfix;

import java.lang.reflect.Field;

import android.os.Handler;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Preview fix for the ported 6.070 OplusCamera on haotian 4.0.0.19.
 *
 * The camera HAL delivers preview frames to the app as callback hardware
 * buffers (mk.b1.J0), but the OCS adapter reports APS version 2, so mk.b1
 * takes the legacy SurfaceTexture branch: mk.b1.b creates the display
 * SurfaceTexture E0 instead of calling PreviewShow.init(), and
 * mk.b1.n (drawNormalPreview) never pops J0 nor uploads it.  Nothing ever
 * feeds texture h[0], so the preview stays black.
 *
 * Three things are needed:
 *  - report APS version 3 so mk.b1.b initialises PreviewShow (mk.b1.N0) and
 *    mk.b1.n uploads the J0 buffers into h[0];
 *  - run the bookkeeping mk.h.onFrameAvailable skips for a null
 *    SurfaceTexture (first-frame flags, frame time, redraw runnable);
 *  - queue a render per frame, because mk.p.run only draws when the GL env
 *    gate e0.a() is true and that flag is not set on this device.
 */
final class CameraPreviewFixHooks {
    private static final String NAIL = "mk.h";
    private static final String BASE = "mk.b1";
    private static final String RENDER_TASK = "mk.p";

    private static volatile boolean installed;

    private CameraPreviewFixHooks() {
    }

    static void install(ClassLoader loader) {
        if (installed) {
            return;
        }

        try {
            final Class<?> algoSwitch = XposedHelpers.findClass(
                    "com.oplus.ocs.camera.appinterface.adapter.CameraUnitAlgoSwitchConfig",
                    loader);
            XposedBridge.hookAllMethods(algoSwitch, "getApsVersion", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object value = param.getResult();
                    if (value instanceof Integer && (Integer) value < 3) {
                        param.setResult(3);
                    }
                }
            });
        } catch (Throwable error) {
            HookLog.error("camera preview: APS version hook failed", error);
        }

        final Class<?> nail = XposedHelpers.findClass(NAIL, loader);
        final Class<?> base = XposedHelpers.findClass(BASE, loader);
        final Field firstDraw = XposedHelpers.findField(nail, "I1");
        final Field firstFrame = XposedHelpers.findField(nail, "J1");
        final Field redraw = XposedHelpers.findField(nail, "P1");
        final Field frameTime = XposedHelpers.findField(base, "M");
        final Field handler = XposedHelpers.findField(base, "k");

        XposedBridge.hookAllMethods(nail, "onFrameAvailable", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length == 0 || param.args[0] != null) {
                    return;
                }
                try {
                    Object self = param.thisObject;
                    firstDraw.setBoolean(self, true);
                    firstFrame.setBoolean(self, true);
                    frameTime.setLong(self, System.nanoTime());
                    Object runnable = redraw.get(self);
                    Object h = handler.get(self);
                    if (runnable instanceof Runnable) {
                        if (h instanceof Handler) {
                            ((Handler) h).post((Runnable) runnable);
                        } else {
                            ((Runnable) runnable).run();
                        }
                    }
                    requestRender(self);
                } catch (Throwable error) {
                    HookLog.error("camera preview: frame hook failed", error);
                }
            }
        });

        try {
            final Class<?> renderTask = XposedHelpers.findClass(RENDER_TASK, loader);
            XposedBridge.hookAllMethods(renderTask, "run", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // mk.p.run returns without drawing unless e0.a() is true.
                    // mk.q.q() only does makeCurrent + onDrawFrame, and the
                    // queue swaps after the task, so running it here is safe.
                    try {
                        Object task = param.thisObject;
                        Object queue = XposedHelpers.getObjectField(task, "a");
                        Object env = queue == null ? null
                                : XposedHelpers.getObjectField(queue, "f");
                        if (env == null || (Boolean) XposedHelpers.callMethod(env, "a")) {
                            return;
                        }
                        XposedHelpers.callMethod(queue, "q",
                                XposedHelpers.getObjectField(env, "z"));
                        param.setResult(null);
                    } catch (Throwable error) {
                        HookLog.error("camera preview: forced draw failed", error);
                    }
                }
            });
        } catch (Throwable error) {
            HookLog.error("camera preview: render hook failed", error);
        }

        installed = true;
        HookLog.info("camera preview: hooks installed");
    }

    /**
     * Queue a render through the app's own path, mk.q.D0(new mk.p(mk.q), false),
     * the same runnable submitted after a capture.
     */
    private static void requestRender(Object nail) {
        try {
            Object ui = XposedHelpers.getObjectField(nail, "l");
            Object glRoot = ui == null ? null : XposedHelpers.getObjectField(ui, "s1");
            Object renderer = glRoot == null ? null
                    : XposedHelpers.getObjectField(glRoot, "c");
            Object queue = renderer == null ? null
                    : XposedHelpers.getObjectField(renderer, "c");
            if (queue == null) {
                return;
            }
            Object task = XposedHelpers.newInstance(
                    XposedHelpers.findClass(RENDER_TASK, nail.getClass().getClassLoader()),
                    queue);
            XposedHelpers.callMethod(queue, "D0", task, false);
        } catch (Throwable error) {
            HookLog.error("camera preview: render request failed", error);
        }
    }
}
