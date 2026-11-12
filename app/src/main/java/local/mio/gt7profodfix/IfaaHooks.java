package local.mio.gt7profodfix;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * IFAA / 支付宝指纹支付兼容（RMX5010，scope: org.ifaa.aidl.manager）。
 *
 * haotian OS4 的 IFAAService 是一个瘦 Binder 桩：
 *   - 事务 1  getSupportBIOTypes
 *   - 事务 3  getDeviceModel
 *   - 事务 4  processCmd
 *   - 事务 9  getIDList
 * 它原本把 cmd 转发给小米 IMlipayService，并返回 Xiaomi-<device> 型号。
 * 这里改为：
 *   - 支持类型固定 fingerprint|FOD = 1|16 = 17
 *   - 型号可配置（默认 ONEPLUS-R23821，与 Oplus IFingerprintPay HAL 匹配）
 *   - cmd 直接转发给 vendor.oplus.hardware.biometrics.fingerprintpay.IFingerprintPay
 *   - 已录入指纹 ID 来自 FingerprintManager
 */
final class IfaaHooks {
    private static final String SERVICE_CLASS = "org.ifaa.aidl.manager.IfaaService";
    private static final String IFAA_DESCRIPTOR =
            "org.ifaa.aidl.manager.IfaaManagerService";
    private static final String OPLUS_PAY_SERVICE =
            "vendor.oplus.hardware.biometrics.fingerprintpay.IFingerprintPay/default";
    private static final String OPLUS_PAY_DESCRIPTOR =
            "vendor.oplus.hardware.biometrics.fingerprintpay.IFingerprintPay";
    private static final String DEFAULT_IFAA_MODEL = "ONEPLUS-R23821";
    private static final int IFAA_FINGERPRINT = 1;
    private static final int IFAA_FOD = 16;
    private static final int IFAA_FINGERPRINT_AND_FOD = IFAA_FINGERPRINT | IFAA_FOD;

    private static final int TRANSACTION_GET_SUPPORT_BIO_TYPES = 1;
    private static final int TRANSACTION_GET_DEVICE_MODEL = 3;
    private static final int TRANSACTION_PROCESS_CMD = 4;
    private static final int TRANSACTION_GET_ID_LIST = 9;
    private static final int OPLUS_TRANSACTION_INVOKE_COMMAND = 6;
    private static final int AIDL_TRANSACTION_GET_INTERFACE_VERSION = 0x00FFFFFF;
    private static final int HAL_LOOKUP_ATTEMPTS = 6;
    private static final long HAL_LOOKUP_RETRY_MS = 10L;

    private static final Set<Class<?>> INSTALLED_BINDER_CLASSES =
            ConcurrentHashMap.newKeySet();
    private static volatile IBinder fingerprintPayService;
    private static volatile WeakReference<Context> serviceContext =
            new WeakReference<>(null);

    private IfaaHooks() {
    }

    static void install(ClassLoader loader) {
        Class<?> serviceClass = Reflect.findClass(loader, SERVICE_CLASS);
        if (serviceClass == null) {
            HookLog.info("IFAA service class not found");
            return;
        }

        int count = Reflect.hookNamedMethods(serviceClass, "onBind", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof Context) {
                    serviceContext = new WeakReference<>((Context) param.thisObject);
                }
                Object result = param.getResult();
                if (result instanceof IBinder) {
                    installBinderHooks((IBinder) result);
                }
            }
        });
        if (count == 0) {
            HookLog.info("IFAA onBind entry not found");
            return;
        }
        HookLog.info("IFAA compatibility entry installed");
        verifyFingerprintPayAccess();
    }

    private static void installBinderHooks(IBinder binder) {
        Class<?> binderClass = binder.getClass();
        if (!INSTALLED_BINDER_CLASSES.add(binderClass)) {
            return;
        }

        try {
            Method supportMethod = compatibleMethod(binderClass,
                    "getSupportBIOTypes", 0, int.class);
            Method modelMethod = compatibleMethod(binderClass,
                    "getDeviceModel", 0, String.class);
            Method processMethod = compatibleMethod(binderClass,
                    "processCmd", 1, byte[].class);
            Method idListMethod = compatibleMethod(binderClass,
                    "getIDList", 1, int[].class);

            if (supportMethod != null && modelMethod != null && processMethod != null
                    && idListMethod != null) {
                installLegacyMethodHooks(supportMethod, modelMethod, processMethod,
                        idListMethod);
                HookLog.info("IFAA legacy method hooks installed on "
                        + binderClass.getName());
                return;
            }

            Method onTransact = Reflect.findMethod(binderClass, "onTransact",
                    int.class, Parcel.class, Parcel.class, int.class);
            if (onTransact == null) {
                throw new NoSuchMethodException("IFAA Binder onTransact");
            }
            Reflect.hookMethodOnce(onTransact, new IfaaTransactionHook());
            HookLog.info("IFAA OS4 transaction hooks installed on "
                    + binderClass.getName());
        } catch (Throwable error) {
            INSTALLED_BINDER_CLASSES.remove(binderClass);
            HookLog.error("IFAA Binder hook installation failed", error);
        }
    }

    private static Method compatibleMethod(Class<?> target, String name, int parameterCount,
            Class<?> returnType) {
        Method method = Reflect.findCompatibleMethod(target, name, parameterCount);
        return method != null && returnType.equals(method.getReturnType()) ? method : null;
    }

    private static void installLegacyMethodHooks(Method supportMethod, Method modelMethod,
            Method processMethod, Method idListMethod) {
        Reflect.hookMethodOnce(supportMethod, new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) {
                return IFAA_FINGERPRINT_AND_FOD;
            }
        });
        Reflect.hookMethodOnce(modelMethod, new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) {
                return ifaaModel();
            }
        });
        Reflect.hookMethodOnce(processMethod, new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    return forwardOplusCommand((byte[]) param.args[0]);
                } catch (Throwable error) {
                    HookLog.info("IFAA Oplus command failed, using original service: " + error);
                    return XposedBridge.invokeOriginalMethod(param.method,
                            param.thisObject, param.args);
                }
            }
        });
        Reflect.hookMethodOnce(idListMethod, new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                int authType = ((Number) param.args[0]).intValue();
                if (authType != IFAA_FINGERPRINT) {
                    return XposedBridge.invokeOriginalMethod(param.method,
                            param.thisObject, param.args);
                }
                int[] ids = readEnrolledFingerprintIds();
                if (ids != null) {
                    return ids;
                }
                HookLog.once("ifaa-id-list-fallback-" + authType,
                        "IFAA Android biometric list unavailable for type=" + authType
                                + ", using original service");
                return XposedBridge.invokeOriginalMethod(param.method,
                        param.thisObject, param.args);
            }
        });
    }

    private static final class IfaaTransactionHook extends XC_MethodHook {
        IfaaTransactionHook() {
            super(PRIORITY_HIGHEST);
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            int code = ((Number) param.args[0]).intValue();
            if (code != TRANSACTION_GET_SUPPORT_BIO_TYPES
                    && code != TRANSACTION_GET_DEVICE_MODEL
                    && code != TRANSACTION_PROCESS_CMD
                    && code != TRANSACTION_GET_ID_LIST) {
                return;
            }

            Parcel data = (Parcel) param.args[1];
            Parcel reply = (Parcel) param.args[2];
            if (data == null || reply == null) {
                return;
            }
            int startPosition = data.dataPosition();

            try {
                data.enforceInterface(IFAA_DESCRIPTOR);
                switch (code) {
                    case TRANSACTION_GET_SUPPORT_BIO_TYPES:
                        reply.writeNoException();
                        reply.writeInt(IFAA_FINGERPRINT_AND_FOD);
                        break;
                    case TRANSACTION_GET_DEVICE_MODEL:
                        reply.writeNoException();
                        reply.writeString(ifaaModel());
                        break;
                    case TRANSACTION_PROCESS_CMD:
                        byte[] request = data.createByteArray();
                        byte[] response = forwardOplusCommand(request);
                        reply.writeNoException();
                        reply.writeByteArray(response);
                        // IFAA's legacy AIDL declares this argument inout.
                        reply.writeByteArray(request);
                        break;
                    case TRANSACTION_GET_ID_LIST:
                        int authType = data.readInt();
                        if (authType != IFAA_FINGERPRINT) {
                            data.setDataPosition(startPosition);
                            return;
                        }
                        int[] ids = readEnrolledFingerprintIds();
                        if (ids == null) {
                            throw new IllegalStateException(
                                    "Android biometric list unavailable for type=" + authType);
                        }
                        reply.writeNoException();
                        reply.writeIntArray(ids);
                        break;
                    default:
                        return;
                }
                param.setResult(true);
            } catch (Throwable error) {
                data.setDataPosition(startPosition);
                HookLog.info("IFAA transaction " + code
                        + " failed, using original service: " + error);
            }
        }
    }

    private static String ifaaModel() {
        Context context = serviceContext.get();
        String model = context == null ? null
                : ModuleConfig.getString(context, ModuleConfig.IFAA_MODEL,
                        DEFAULT_IFAA_MODEL);
        if (model == null || model.trim().isEmpty()) {
            return DEFAULT_IFAA_MODEL;
        }
        return model.trim();
    }

    private static byte[] forwardOplusCommand(byte[] request) throws Throwable {
        IBinder service = fingerprintPayService;
        if (service == null || !service.isBinderAlive()) {
            service = findFingerprintPayService();
            if (service == null) {
                throw new RemoteException("Oplus IFingerprintPay/default not found");
            }
            fingerprintPayService = service;
            HookLog.once("ifaa-oplus-hal-connected",
                    "IFAA connected to Oplus IFingerprintPay/default");
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(OPLUS_PAY_DESCRIPTOR);
            data.writeByteArray(request);
            if (!service.transact(OPLUS_TRANSACTION_INVOKE_COMMAND,
                    data, reply, 0)) {
                fingerprintPayService = null;
                throw new RemoteException("Oplus invoke_command is unimplemented");
            }
            reply.readException();
            byte[] response = reply.createByteArray();
            HookLog.info("IFAA processCmd req=" + summarize(request)
                    + " resp=" + summarize(response));
            return response;
        } catch (Throwable error) {
            if (!service.isBinderAlive()) {
                fingerprintPayService = null;
            }
            throw error;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void verifyFingerprintPayAccess() {
        new Thread(() -> {
            try {
                IBinder service = findFingerprintPayService();
                if (service == null) {
                    HookLog.info("IFAA Oplus HAL not found");
                    return;
                }
                String descriptor = service.getInterfaceDescriptor();
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(descriptor == null
                            ? OPLUS_PAY_DESCRIPTOR : descriptor);
                    boolean ok = service.transact(
                            AIDL_TRANSACTION_GET_INTERFACE_VERSION, data, reply, 0);
                    int version = ok ? reply.readInt() : -1;
                    HookLog.info("IFAA Oplus HAL access ok descriptor=" + descriptor
                            + " version=" + version);
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            } catch (Throwable error) {
                HookLog.info("IFAA Oplus HAL access denied: " + error);
            }
        }, "ifaa-hal-probe").start();
    }

    private static String summarize(byte[] bytes) {
        if (bytes == null) {
            return "null";
        }
        int length = Math.min(bytes.length, 48);
        StringBuilder text = new StringBuilder(length * 2 + 16);
        for (int index = 0; index < length; index++) {
            int value = bytes[index] & 0xff;
            text.append(Character.forDigit(value >>> 4, 16));
            text.append(Character.forDigit(value & 0x0f, 16));
        }
        if (bytes.length > length) {
            text.append("...");
        }
        return text.append('(').append(bytes.length).append("B)").toString();
    }

    private static IBinder findFingerprintPayService() throws Throwable {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        for (int attempt = 0; attempt < HAL_LOOKUP_ATTEMPTS; attempt++) {
            IBinder service = (IBinder) XposedHelpers.callStaticMethod(serviceManager,
                    "getService", OPLUS_PAY_SERVICE);
            if (service != null) {
                return service;
            }
            if (attempt + 1 < HAL_LOOKUP_ATTEMPTS) {
                SystemClock.sleep(HAL_LOOKUP_RETRY_MS);
            }
        }
        return null;
    }

    private static int[] readEnrolledFingerprintIds() {
        try {
            Context context = serviceContext.get();
            if (context == null) {
                return null;
            }
            Object manager = XposedHelpers.callMethod(context,
                    "getSystemService", "fingerprint");
            if (manager == null) {
                return null;
            }

            Object value;
            try {
                value = XposedHelpers.callMethod(manager, "getEnrolledFingerprints");
            } catch (Throwable noZeroArgumentMethod) {
                value = XposedHelpers.callMethod(manager, "getEnrolledFingerprints",
                        currentUserId());
            }
            if (!(value instanceof List<?>)) {
                return null;
            }

            List<?> fingerprints = (List<?>) value;
            int[] ids = new int[fingerprints.size()];
            for (int index = 0; index < fingerprints.size(); index++) {
                Object fingerprint = fingerprints.get(index);
                Object id;
                try {
                    id = XposedHelpers.callMethod(fingerprint, "getBiometricId");
                } catch (Throwable noBiometricId) {
                    id = XposedHelpers.callMethod(fingerprint, "getFingerId");
                }
                if (!(id instanceof Number)) {
                    return null;
                }
                ids[index] = ((Number) id).intValue();
            }
            return ids;
        } catch (Throwable error) {
            HookLog.once("ifaa-fingerprint-list-error",
                    "IFAA fingerprint list read failed: " + error);
            return null;
        }
    }

    private static int currentUserId() {
        try {
            Class<?> userHandle = Class.forName("android.os.UserHandle");
            Object value = XposedHelpers.callStaticMethod(userHandle, "myUserId");
            return value instanceof Number ? ((Number) value).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
