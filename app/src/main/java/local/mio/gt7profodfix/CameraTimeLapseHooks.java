package local.mio.gt7profodfix;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * OS4 removed ImageReader.nativeGetConsumer(), so the APS video adapter gets
 * a zero consumer pointer and every time-lapse frame is dropped.  The native
 * JNIImageReaderContext layout starts with a 24-byte List, then
 * sp<BufferItemConsumer> mConsumer (offset 24) and sp<IGraphicBufferProducer>
 * mProducer (offset 32).  Return mConsumer directly so the APS video path can
 * acquire frames again.
 */
public final class CameraTimeLapseHooks {
    private static final String UTILS =
            "com.oplus.ocs.camera.consumer.apsAdapter.adapter.ApsUtils";
    private static final int CONSUMER_OFFSET = 24;
    private static final Set<Class<?>> HOOKED = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger PROBES = new AtomicInteger();

    private CameraTimeLapseHooks() {
    }

    public static void install(ClassLoader classLoader) {
        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args.length > 0 && UTILS.equals(param.args[0])) {
                                Object result = param.getResult();
                                if (result instanceof Class<?>) {
                                    hookUtils((Class<?>) result);
                                }
                            }
                        }
                    });
        } catch (Throwable error) {
            HookLog.error("time-lapse class-loader hook installation failed", error);
        }
        try {
            hookUtils(XposedHelpers.findClass(UTILS, classLoader));
        } catch (Throwable error) {
            HookLog.error("time-lapse consumer hook installation failed", error);
        }
    }

    private static void hookUtils(Class<?> utils) {
        if (utils == null || !HOOKED.add(utils)) {
            return;
        }
        XposedBridge.hookAllMethods(utils, "getConsumerPtr", new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) {
                return consumerPtr(param.args.length > 0 ? param.args[0] : null);
            }
        });
        HookLog.info("time-lapse consumer hook on " + utils.getClassLoader());
    }

    private static long consumerPtr(Object reader) {
        if (reader == null) {
            return 0L;
        }
        try {
            long ctx = XposedHelpers.getLongField(reader, "mNativeContext");
            Object unsafe = unsafe();
            Method getLong = unsafe.getClass().getMethod("getLong", long.class);
            long ptr = (Long) getLong.invoke(unsafe, ctx + CONSUMER_OFFSET);
            if (PROBES.incrementAndGet() <= 3) {
                HookLog.info("consumer-probe ctx=" + Long.toHexString(ctx)
                        + " consumer=" + Long.toHexString(ptr));
            }
            return ptr;
        } catch (Throwable error) {
            HookLog.error("time-lapse consumer probe failed", error);
            return 0L;
        }
    }

    private static Object unsafe() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return field.get(null);
    }
}
