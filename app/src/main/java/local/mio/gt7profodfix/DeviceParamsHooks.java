package local.mio.gt7profodfix;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Settings -> My device shows the specs of the spoofed model (Xiaomi 15 Pro),
 * fetched from Xiaomi's device-info service and cached in
 * device_params_pref. Same hook points as the OnePlus 13 port
 * (OP13HyperOSFix deviceparams.MainHook), with this phone's real values:
 * battery from charge_full_design, panel from the display's real size and
 * DPI, cameras from the camera HAL sensor arrays (main/periscope are 50MP
 * quad-bayer sensors reported binned).
 *
 * RAM stays the stock "xxxx" placeholder: DeviceBasicInfoPresenter fills it
 * from MiuiAboutPhoneUtils.getTotaolRam() plus extended RAM itself.
 */
final class DeviceParamsHooks {
    static final String MARKET_NAME = "真我GT7 Pro";
    static final String PROCESSOR = "骁龙8至尊版移动平台";
    static final String BATTERY = "6500mAh";
    static final String REAR_CAMERA = "50MP+50MP+8MP";
    static final String SCREEN_SIZE = "6.78″";
    static final String RESOLUTION = "2780*1264";
    static final String FRONT_CAMERA = "16MP";

    private static final String DEVICE = "com.android.settings.device.";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private DeviceParamsHooks() {
    }

    static void install(ClassLoader classLoader) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        final String basic;
        final String camera;
        try {
            basic = basicJson();
            camera = cameraJson();
        } catch (Throwable error) {
            HookLog.error("DeviceParams: JSON build failed", error);
            return;
        }

        int hooks = 0;
        hooks += replace(classLoader, "DeviceParamsManager", "getBasicParams", basic);
        hooks += replace(classLoader, "DeviceParamsManager", "getCameraParams", camera);
        hooks += replace(classLoader, "DeviceServiceCache", "readBasicInfo", basic);
        hooks += replace(classLoader, "DeviceServiceCache", "readCameraInfo", camera);
        hooks += replace(classLoader, "MiuiAboutPhoneUtils", "getDeviceMarketName",
                MARKET_NAME);
        HookLog.info("DeviceParams: RMX5010 specs installed hooks=" + hooks);
    }

    private static int replace(ClassLoader classLoader, String className, String method,
                               final String value) {
        try {
            Class<?> type = XposedHelpers.findClass(DEVICE + className, classLoader);
            int count = XposedBridge.hookAllMethods(type, method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(value);
                }
            }).size();
            if (count == 0) HookLog.info("DeviceParams: " + className + "." + method + " missing");
            return count;
        } catch (Throwable error) {
            HookLog.error("DeviceParams: " + className + "." + method + " unavailable", error);
            return 0;
        }
    }

    private static String basicJson() throws Exception {
        JSONObject mishop = new JSONObject();
        mishop.put("RightValue", "");
        mishop.put("ShowRedDot", "false");
        mishop.put("Url", "");
        JSONArray items = new JSONArray();
        items.put(item("处理器", PROCESSOR, 0));
        items.put(item("电池容量", BATTERY, 1));
        items.put(item("后置摄像头", REAR_CAMERA, 2));
        items.put(item("屏幕尺寸", SCREEN_SIZE, 3));
        items.put(item("分辨率", RESOLUTION, 4));
        items.put(item("运行内存", "xxxx", 5));
        JSONObject root = new JSONObject();
        root.put("Mishop", mishop);
        root.put("BasicInfoToggle", "1");
        root.put("BasicItems", items);
        return root.toString();
    }

    private static JSONObject item(String title, String summary, int index) throws Exception {
        JSONObject item = new JSONObject();
        item.put("Title", title);
        item.put("Summary", summary);
        item.put("Index", String.valueOf(index));
        return item;
    }

    private static String cameraJson() throws Exception {
        JSONObject camera = new JSONObject();
        camera.put("front_camera", FRONT_CAMERA);
        camera.put("rear_camera", REAR_CAMERA);
        JSONObject data = new JSONObject();
        data.put("BasicInfoToggle", "1");
        data.put("camera", camera);
        JSONObject root = new JSONObject();
        root.put("data", data);
        root.put("status", "true");
        return root.toString();
    }
}
