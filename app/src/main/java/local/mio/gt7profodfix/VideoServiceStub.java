package local.mio.gt7profodfix;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * Forwarding proxy for vendor.xiaomi.hardware.videoservice.IVideoService.
 *
 * The real Xiaomi vendor service binary is started as
 * vendor.qti.hardware.embmssl.IEmbms/default, a VINTF-declared AIDL name that
 * servicemanager accepts.  This proxy is registered from system_server under
 * the name OplusCamera expects and forwards every transaction to the real
 * service.  The interface token is rewritten because the real service's AIDL
 * descriptor was changed to the eMBMS name so it could pass the VINTF check.
 *
 * The camera's native media stack registers a listener and expects
 * onStopFrameCapture/onReleaseFrameCapture at video stop; the real service
 * owns that listener and invokes it, which is what makes MPEG4Writer finalize
 * the pending multi-view MP4 (moov).
 */
final class VideoServiceStub extends Binder {
    static final String DESCRIPTOR =
            "vendor.xiaomi.hardware.videoservice.IVideoService";
    private static final String TARGET_NAME =
            "vendor.qti.hardware.embmssl.IEmbms/default";
    private static final String TARGET_DESCRIPTOR =
            "vendor.qti.hardware.embmssl.IEmbms";
    private static final String TAG = "RMX5010VidSvc";
    private static volatile boolean sLoggedFirst;

    VideoServiceStub() {
        attachInterface(null, DESCRIPTOR);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            reply.writeString(DESCRIPTOR);
            return true;
        }

        IBinder target = waitTarget();
        if (target == null) {
            Log.w(TAG, "real video service not available for code " + code);
            return false;
        }

        // The camera client writes the videoservice interface token.  Consume
        // it, then rebuild the parcel with the eMBMS token the real service's
        // AIDL stub enforces, keeping the payload byte-for-byte.
        data.enforceInterface(DESCRIPTOR);
        Parcel out = Parcel.obtain();
        try {
            out.writeInterfaceToken(TARGET_DESCRIPTOR);
            int offset = data.dataPosition();
            out.appendFrom(data, offset, data.dataSize() - offset);
            boolean result = target.transact(code, out, reply, flags);
            if (!sLoggedFirst) {
                sLoggedFirst = true;
                Log.i(TAG, "forwarded first transaction code=" + code);
            }
            return result;
        } catch (Throwable error) {
            Log.w(TAG, "forward code " + code + " failed", error);
            return false;
        } finally {
            out.recycle();
        }
    }

    private static IBinder waitTarget() {
        for (int i = 0; i < 20; i++) {
            IBinder binder = getService(TARGET_NAME);
            if (binder != null) {
                return binder;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static IBinder getService(String name) {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) serviceManager.getMethod("getService", String.class)
                    .invoke(null, name);
            if (binder != null) {
                return binder;
            }
            try {
                java.lang.reflect.Method raw =
                        serviceManager.getDeclaredMethod("rawGetService", String.class);
                raw.setAccessible(true);
                binder = (IBinder) raw.invoke(null, name);
                Log.i(TAG, "rawGetService(" + name + ")=" + binder);
                return binder;
            } catch (Throwable rawError) {
                Log.w(TAG, "rawGetService(" + name + ") failed", rawError);
                return null;
            }
        } catch (Throwable error) {
            Log.w(TAG, "getService(" + name + ") failed", error);
            return null;
        }
    }
}
