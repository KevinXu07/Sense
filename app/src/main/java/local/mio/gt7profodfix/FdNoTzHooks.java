package local.mio.gt7profodfix;

import android.content.Context;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * RMX5010 没有可用的小米 TZ/cardapp 凭证，FindDevice 的
 * SecurityDeviceCredentialService 仍会走 DeviceCredentialTZ，导致
 * 小爱/ASR 请求拿不到 fid/sign、「查找设备」签名失败。
 *
 * 1. 把 TZ 门面委托给 NoTZ 软件凭证（/mnt/vendor/persist/fdsd/dc），
 *    保持 fx.i.e()==TZ 的判断不变，只替换凭证方法本身。
 * 2. dc 不存在时（新机/从未签发过），在 finddevice 自己的进程里后台调用
 *    NoTZ 管理器的 updateDeviceCredential，从小米服务器在线签发并落盘。
 *    不能等开关触发：账号应用在 open() 之前就先调 sign()，凭证为空直接失败。
 * 3. cloudsp_tzid（hf.s0.a()）对 NoTZ 设备返回 ""，否则 open() 抛 "empty TZ id"。
 */
final class FdNoTzHooks {
    private static final String TZ_CLASS = "com.xiaomi.finddevice.v2.DeviceCredentialTZ";
    private static final String NOTZ_CLASS = "com.xiaomi.finddevice.v2.DeviceCredentialManagerNoTZ";
    private static final String CRED_IFACE = "com.xiaomi.finddevice.v2.IDeviceCredential";
    private static final String FACTORY_CLASS = "com.xiaomi.finddevice.v2.DeviceTypeBasedFactory";
    private static final String HANDLER_IFACE =
            "com.xiaomi.finddevice.v2.IDeviceCredentialManager$IUpdateDeviceCredentialHandler";
    // cloudsp_tzid provider; obfuscated name changes between FindDevice builds.
    private static final String[] TZ_ID_CLASSES = {"hf.s0", "gf.s0"};
    private static final long BOOTSTRAP_RETRY_MS = 60_000L;

    private static ClassLoader sLoader;
    private static volatile Object sCredential;
    private static final AtomicBoolean sBootstrapRunning = new AtomicBoolean();
    private static volatile long sLastBootstrap;

    private FdNoTzHooks() {
    }

    static void install(ClassLoader loader) {
        sLoader = loader;
        Class<?> tz;
        try {
            tz = XposedHelpers.findClass(TZ_CLASS, loader);
        } catch (Throwable error) {
            HookLog.error("FdNoTz: TZ class missing", error);
            return;
        }

        hookString(tz, "getFId", "getFId");
        hookString(tz, "getHost", "getHost");
        hookString(tz, "getOwnUserId", "getOwnUserId");

        XposedBridge.hookAllMethods(tz, "isProvisioned", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(credential() != null);
            }
        });

        XposedBridge.hookAllMethods(tz, "sign", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object credential = credential();
                if (credential == null || param.args == null || param.args.length == 0) {
                    param.setResult(null);
                    return;
                }
                param.setResult(invoke(credential, "sign",
                        new Class<?>[]{byte[].class}, param.args[0]));
            }
        });

        // TZ provisioning always fails on this TEE; report success so the Union
        // manager keeps the TZ facade (which is backed by the NoTZ credential).
        XposedBridge.hookAllMethods(tz, "provision", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(true);
            }
        });

        hookTzId(loader);
        HookLog.info("FdNoTz: hooks installed");
    }

    private static void hookTzId(ClassLoader loader) {
        for (String name : TZ_ID_CLASSES) {
            Class<?> cls = XposedHelpers.findClassIfExists(name, loader);
            if (cls == null) {
                continue;
            }
            try {
                Method method = cls.getDeclaredMethod("a");
                if (method.getReturnType() != String.class
                        || !java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        || !throwsNested(method, cls)) {
                    continue;
                }
                XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(""));
                HookLog.info("FdNoTz: tz id hook on " + name);
                return;
            } catch (NoSuchMethodException ignored) {
                // try the next candidate
            }
        }
        HookLog.info("FdNoTz: tz id provider not found");
    }

    /** The tz id provider declares {@code throws <Self>$b} (its own checked exception). */
    private static boolean throwsNested(Method method, Class<?> owner) {
        for (Class<?> type : method.getExceptionTypes()) {
            if (type.getName().startsWith(owner.getName() + "$")) {
                return true;
            }
        }
        return false;
    }

    private static void hookString(Class<?> tz, String method, String credentialMethod) {
        XposedBridge.hookAllMethods(tz, method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object credential = credential();
                param.setResult(credential == null
                        ? null : invoke(credential, credentialMethod, new Class<?>[0]));
            }
        });
    }

    private static Object credential() {
        Object cached = sCredential;
        if (cached != null) {
            return cached;
        }
        Context context = currentApplication();
        if (context == null) {
            HookLog.once("fd_no_context", "FdNoTz: context unavailable");
            return null;
        }
        synchronized (FdNoTzHooks.class) {
            if (sCredential != null) {
                return sCredential;
            }
            Object credential = load(context);
            if (credential != null) {
                sCredential = credential;
                HookLog.info("FdNoTz: credential loaded " + credential.getClass().getName());
                return credential;
            }
        }
        HookLog.once("fd_credential_null", "FdNoTz: NoTZ credential is null");
        startBootstrap(context);
        return null;
    }

    private static Object load(Context context) {
        try {
            Class<?> managerClass = XposedHelpers.findClass(NOTZ_CLASS, sLoader);
            Object manager = managerClass.getConstructor(Context.class).newInstance(context);
            return managerClass.getMethod("get", Context.class).invoke(manager, context);
        } catch (Throwable error) {
            HookLog.error("FdNoTz: credential load failed", error);
            return null;
        }
    }

    /**
     * Ask Xiaomi's server for a software credential through the app's own NoTZ
     * manager.  Runs off the main thread (the request layer refuses the main
     * looper) and at most once a minute while the credential is missing.
     */
    private static void startBootstrap(Context context) {
        long now = System.currentTimeMillis();
        if (now - sLastBootstrap < BOOTSTRAP_RETRY_MS || !sBootstrapRunning.compareAndSet(false, true)) {
            return;
        }
        sLastBootstrap = now;
        Thread thread = new Thread(() -> {
            try {
                bootstrap(context);
            } finally {
                sBootstrapRunning.set(false);
            }
        }, "FdNoTzBootstrap");
        thread.setDaemon(true);
        thread.start();
    }

    private static void bootstrap(Context context) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return;
        }
        try {
            Object manager = noTzManager(context);
            Class<?> handlerType = XposedHelpers.findClass(HANDLER_IFACE, sLoader);
            Object handler = Proxy.newProxyInstance(sLoader, new Class<?>[]{handlerType},
                    (proxy, method, args) -> {
                        if ("doStatusUpdate".equals(method.getName())) {
                            HookLog.info("FdNoTz: bootstrap got new credential");
                            return null;
                        }
                        if ("toString".equals(method.getName())) {
                            return "FdNoTzBootstrapHandler";
                        }
                        if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(method.getName())) {
                            return proxy == args[0];
                        }
                        return null;
                    });
            HookLog.info("FdNoTz: bootstrap start via " + manager.getClass().getName());
            manager.getClass().getMethod("updateDeviceCredential", Context.class, handlerType)
                    .invoke(manager, context, handler);
            Object credential = load(context);
            if (credential != null) {
                sCredential = credential;
                HookLog.info("FdNoTz: bootstrap ok, credential saved");
            } else {
                HookLog.info("FdNoTz: bootstrap finished without credential");
            }
        } catch (Throwable error) {
            Throwable cause = error instanceof java.lang.reflect.InvocationTargetException
                    && error.getCause() != null ? error.getCause() : error;
            HookLog.error("FdNoTz: bootstrap failed", cause);
        }
    }

    /** The NoTZ manager the app itself uses (inside its Union manager), so its in-memory state updates too. */
    private static Object noTzManager(Context context) throws Exception {
        Class<?> notz = XposedHelpers.findClass(NOTZ_CLASS, sLoader);
        try {
            Object union = XposedHelpers.findClass(FACTORY_CLASS, sLoader)
                    .getMethod("getDeviceCredentialManager", Context.class).invoke(null, context);
            if (notz.isInstance(union)) {
                return union;
            }
            if (union != null) {
                for (Field field : union.getClass().getDeclaredFields()) {
                    if (field.getType() == notz) {
                        field.setAccessible(true);
                        Object value = field.get(union);
                        if (value != null) {
                            return value;
                        }
                    }
                }
            }
        } catch (Throwable error) {
            HookLog.error("FdNoTz: app credential manager unavailable", error);
        }
        return notz.getConstructor(Context.class).newInstance(context);
    }

    private static Object invoke(Object credential, String method, Class<?>[] types,
                                 Object... args) {
        try {
            Class<?> iface = XposedHelpers.findClass(CRED_IFACE, sLoader);
            Method target = iface.getMethod(method, types);
            return target.invoke(credential, args);
        } catch (Throwable error) {
            HookLog.error("FdNoTz: invoke " + method + " failed", error);
            return null;
        }
    }

    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object application = activityThread.getMethod("currentApplication").invoke(null);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable error) {
            return null;
        }
    }
}
