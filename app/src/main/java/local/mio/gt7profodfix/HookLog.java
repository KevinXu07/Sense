package local.mio.gt7profodfix;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;

final class HookLog {
    private static final String TAG = "RMX5010HyperOSFix";
    private static final Set<String> ONCE = ConcurrentHashMap.newKeySet();

    private HookLog() {
    }

    static void info(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    static void once(String key, String message) {
        if (ONCE.add(key)) {
            info(message);
        }
    }

    static void error(String message, Throwable error) {
        info(message + ": " + error);
        XposedBridge.log(error);
    }
}
