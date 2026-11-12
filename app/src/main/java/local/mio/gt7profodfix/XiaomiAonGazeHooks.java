package local.mio.gt7profodfix;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Bridge HyperOS "注视感知" (com.xiaomi.aon) to the Oplus AON camera HAL.
 *
 * Xiaomi's AonAttentionService asks AONAdapter for event type 4 (face gaze);
 * every Xiaomi backend needs a Xiaomi-only vendor HAL, so on this device the
 * selected AONFaceTee backend reports capability 0 and the request fails.
 * The realme vendor ships vendor.oplus.hardware.camera.aon.IAONService (the
 * HAL ColorOS AONService drives) whose service type 0x4 returns gaze results
 * (result id 300) computed by the QSEE AON firmware on the front imx480.
 *
 * AONFaceTee is taken over for type 4 only: capability gains 0x4, register /
 * unregister start and stop an Oplus gaze session, and a "looking" decision
 * is posted through AONFaceTee.AlwaysonFaceCb(4, data) so Xiaomi's own
 * listener turns data[5] == 1 into ATTENTION_SUCCESS_PRESENT.
 *
 * Interface (V1, verified from /odm ndk stubs and ColorOS AONService):
 *   1 registerClient(IAONServiceCallBack, int mask) -> long
 *   3 subscribeSensor(long, SensorConfigInfo)   4 startService(long, int)
 *   5 stopService(long, int)  6 unsubscribeSensor(long)  7 unregisterClient(long)
 *   callback 1 onAONEvent(long, AONCallBackInfo), 2 onAONSensorEvent(long, ...)
 */
final class XiaomiAonGazeHooks {
    private static final String TEE = "com.xiaomi.aon.AONFaceTee";
    private static final int TYPE_FACE_GAZE = 4;

    private static final String SERVICE = "vendor.oplus.hardware.camera.aon.IAONService/default";
    private static final String DESC = "vendor.oplus.hardware.camera.aon.IAONService";
    private static final String CB_DESC = "vendor.oplus.hardware.camera.aon.IAONServiceCallBack";
    private static final String CB_HASH = "8d0c8f312ebf7c4e6a50ebba9432d2bde93c09c2";
    private static final int SRV_GAZE = 0x4;
    private static final int RESULT_ANGLE = 100;
    private static final int RESULT_FACE_COUNT = 101;
    private static final int RESULT_GAZE = 300;
    // Front AON sensor (imx480, slot 3): 320x240, 10 fps, 10 frames per 1 s delivery.
    private static final int FRAME_W = 320;
    private static final int FRAME_H = 240;
    private static final int FPS = 10;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "rmx-aon-gaze");
        thread.setDaemon(true);
        return thread;
    });

    // EXECUTOR-confined state.
    private static IBinder service;
    private static long client;
    private static boolean running;
    private static Object tee;
    private static int sensorId = 3;

    // Binder-thread state, guarded by WINDOW.
    private static final ArrayDeque<Boolean> WINDOW = new ArrayDeque<>();
    private static volatile Object activeTee;
    private static volatile long lastPresentMs;
    // Per-session diagnostics (guarded by WINDOW).
    private static long sessionStartMs;
    private static long firstEventMs;
    private static long firstPresentMs;
    private static int frames;
    private static int faceFrames;
    private static int gazeFrames;
    private static int lookFrames;
    private static float minY = Float.MAX_VALUE;
    private static float maxY = -Float.MAX_VALUE;

    private XiaomiAonGazeHooks() {
    }

    static void install(ClassLoader loader) {
        Class<?> teeClass = XposedHelpers.findClassIfExists(TEE, loader);
        if (teeClass == null) {
            HookLog.info("aon gaze: " + TEE + " missing");
            return;
        }
        XposedHelpers.findAndHookMethod(teeClass, "getAONCapability", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (halService() != null) {
                    param.setResult(((Integer) param.getResult()) | TYPE_FACE_GAZE);
                }
            }
        });
        XposedHelpers.findAndHookMethod(teeClass, "AonEventRegister",
                int.class, float.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if ((Integer) param.args[0] != TYPE_FACE_GAZE) {
                            return;
                        }
                        Object owner = param.thisObject;
                        EXECUTOR.execute(() -> start(owner));
                        param.setResult(true);
                    }
                });
        XposedHelpers.findAndHookMethod(teeClass, "AonEventUnRegister", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if ((Integer) param.args[0] != TYPE_FACE_GAZE) {
                            return;
                        }
                        EXECUTOR.execute(XiaomiAonGazeHooks::stop);
                        param.setResult(true);
                    }
                });
        HookLog.info("aon gaze: AONFaceTee type 4 routed to Oplus AON HAL");
    }

    private static IBinder halService() {
        try {
            Method getService = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, SERVICE);
            if (binder == null) {
                HookLog.once("aon_gaze_missing", "aon gaze: Oplus AON HAL not visible");
            }
            return binder;
        } catch (Throwable error) {
            HookLog.once("aon_gaze_lookup", "aon gaze: HAL lookup failed: " + error);
            return null;
        }
    }

    private static void start(Object owner) {
        tee = owner;
        activeTee = owner;
        synchronized (WINDOW) {
            WINDOW.clear();
            if (!running) {
                sessionStartMs = System.currentTimeMillis();
                firstEventMs = 0;
                firstPresentMs = 0;
                frames = faceFrames = gazeFrames = lookFrames = 0;
                minY = Float.MAX_VALUE;
                maxY = -Float.MAX_VALUE;
            }
        }
        if (running) {
            return;
        }
        try {
            if (service == null || !service.isBinderAlive()) {
                service = halService();
                if (service == null) {
                    return;
                }
                service.linkToDeath(() -> EXECUTOR.execute(() -> {
                    service = null;
                    running = false;
                    HookLog.info("aon gaze: HAL died");
                }), 0);
            }
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESC);
                data.writeStrongBinder(CALLBACK);
                data.writeInt(0xf);
                service.transact(1, data, reply, 0);
                reply.readException();
                client = reply.readLong();
            } finally {
                data.recycle();
                reply.recycle();
            }
            // sensorId, width, height, frameRate, framesPerDelivery, deliveryPeriodMs,
            // serviceType, deliveryMode (ColorOS AonSensorHubHelper defaults)
            int[] config = {sensorId, FRAME_W, FRAME_H, FPS, FPS, 1000, SRV_GAZE, 1};
            data = Parcel.obtain();
            reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESC);
                data.writeLong(client);
                data.writeInt(1);
                data.writeInt(4 + config.length * 4);
                for (int value : config) {
                    data.writeInt(value);
                }
                service.transact(3, data, reply, 0);
                reply.readException();
            } finally {
                data.recycle();
                reply.recycle();
            }
            call(4, client, SRV_GAZE);
            running = true;
            HookLog.info("aon gaze: session started client=0x" + Long.toHexString(client));
        } catch (Throwable error) {
            HookLog.error("aon gaze: start failed", error);
            release();
        }
    }

    private static void stop() {
        activeTee = null;
        if (!running) {
            return;
        }
        release();
        String stats;
        synchronized (WINDOW) {
            stats = "frames=" + frames + " faces=" + faceFrames + " gaze=" + gazeFrames
                    + " looking=" + lookFrames
                    + " firstEvent=" + (firstEventMs == 0 ? -1 : firstEventMs - sessionStartMs) + "ms"
                    + " firstPresent=" + (firstPresentMs == 0 ? -1 : firstPresentMs - sessionStartMs) + "ms"
                    + (gazeFrames > 0 ? " y=[" + minY + "," + maxY + "]" : "");
        }
        HookLog.info("aon gaze: session stopped " + stats);
    }

    private static void release() {
        running = false;
        if (service == null || client == 0) {
            return;
        }
        call(5, client, SRV_GAZE);
        call(6, client, null);
        call(7, client, null);
        client = 0;
    }

    private static void call(int code, long handle, Integer arg) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC);
            data.writeLong(handle);
            if (arg != null) {
                data.writeInt(arg);
            }
            service.transact(code, data, reply, 0);
            reply.readException();
        } catch (Throwable error) {
            HookLog.once("aon_gaze_call_" + code, "aon gaze: call " + code + " failed: " + error);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static final Binder CALLBACK = createCallback();

    private static Binder createCallback() {
        Binder binder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                try {
                    if (code == 0xfffffe) {
                        reply.writeNoException();
                        reply.writeString(CB_HASH);
                        return true;
                    }
                    if (code == 0xffffff) {
                        reply.writeNoException();
                        reply.writeInt(1);
                        return true;
                    }
                    if (code == INTERFACE_TRANSACTION) {
                        reply.writeString(CB_DESC);
                        return true;
                    }
                    data.enforceInterface(CB_DESC);
                    data.readLong();
                    if (code == 1) {
                        onEvent(data);
                    }
                    if (reply != null && (flags & FLAG_ONEWAY) == 0) {
                        reply.writeNoException();
                    }
                } catch (Throwable error) {
                    HookLog.once("aon_gaze_parse", "aon gaze: callback parse failed: " + error);
                }
                return true;
            }
        };
        binder.attachInterface(null, CB_DESC);
        try {
            // Vendor HALs may only call back into VINTF-stable binders.
            XposedHelpers.callMethod(binder, "markVintfStability");
        } catch (Throwable error) {
            HookLog.error("aon gaze: markVintfStability failed", error);
        }
        return binder;
    }

    private static void onEvent(Parcel p) {
        if (p.readInt() == 0) {
            return;
        }
        int start = p.dataPosition();
        int size = p.readInt();
        p.readInt(); // serviceType
        int angle = 0;
        int faces = 0;
        float[] gaze = null;
        int count = p.readInt();
        for (int i = 0; i < count; i++) {
            if (p.readInt() == 0) {
                continue;
            }
            int resultStart = p.dataPosition();
            int resultSize = p.readInt();
            int id = p.readInt();
            if (p.readInt() != 0) {
                int tag = p.readInt();
                if (id == RESULT_ANGLE && tag == 5) {
                    angle = Math.round(p.readFloat());
                } else if (id == RESULT_FACE_COUNT && tag == 2) {
                    faces = p.readInt();
                } else if (id == RESULT_GAZE && tag == 6) {
                    gaze = p.createFloatArray();
                }
            }
            p.setDataPosition(resultStart + resultSize);
        }
        p.setDataPosition(start + size);
        boolean looking = faces > 0 && gaze != null && gaze.length >= 6 && isLooking(gaze, angle);
        boolean present;
        synchronized (WINDOW) {
            long eventMs = System.currentTimeMillis();
            if (firstEventMs == 0) {
                firstEventMs = eventMs;
            }
            frames++;
            if (faces > 0) {
                faceFrames++;
            }
            if (gaze != null && gaze.length >= 6) {
                gazeFrames++;
                minY = Math.min(minY, gaze[1]);
                maxY = Math.max(maxY, gaze[1]);
            }
            if (looking) {
                lookFrames++;
            }
            WINDOW.addLast(looking);
            while (WINDOW.size() > 5) {
                WINDOW.removeFirst();
            }
            int hits = 0;
            for (Boolean hit : WINDOW) {
                if (hit) {
                    hits++;
                }
            }
            present = hits >= 2;
            if (present && firstPresentMs == 0) {
                firstPresentMs = eventMs;
            }
        }
        Object owner = activeTee;
        long now = System.currentTimeMillis();
        if (present && owner != null && now - lastPresentMs > 500) {
            lastPresentMs = now;
            int[] event = new int[16];
            event[5] = 1;
            try {
                XposedHelpers.callMethod(owner, "AlwaysonFaceCb", TYPE_FACE_GAZE, event);
                HookLog.once("aon_gaze_present", "aon gaze: first PRESENT delivered (angle "
                        + angle + ", faces " + faces + ")");
            } catch (Throwable error) {
                HookLog.error("aon gaze: deliver failed", error);
            }
        }
    }

    /**
     * Gaze (x, y) in degrees from the front camera.  Calibrated on RMX5010:
     * looking at the screen in portrait gives y ~ 3..10 (camera above the
     * screen), looking away gives y <= 2 or no gaze result.  Eyes must be
     * open: ColorOS GazeUtils ratio (a1 + a3) / (a0 + a2) <= 7.
     */
    private static boolean isLooking(float[] g, int angle) {
        float x = g[0];
        float y = g[1];
        float open = g[2] + g[4];
        if (open <= 0 || (g[3] + g[5]) / open > 7f) {
            return false;
        }
        switch (((angle % 360) + 360) % 360) {
            case 90:
                return x < -3f && x > -19.4f && y > -14f && y < 14f;
            case 180:
                return y < -3f && y > -19.4f && x > -14f && x < 14f;
            case 270:
                return x > 3f && x < 19.4f && y > -14f && y < 14f;
            default:
                return y > 3f && y < 19.4f && x > -14f && x < 10f;
        }
    }
}
