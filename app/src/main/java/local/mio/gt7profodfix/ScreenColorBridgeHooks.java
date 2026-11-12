package local.mio.gt7profodfix;

import java.util.Arrays;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Bridge HyperOS "色彩风格" (screen color) settings to the ColorOS display stack.
 *
 * HyperOS Settings writes screen_optimize_mode / screen_color_level, and MIUI's
 * DisplayFeatureManagerService turns them into setFeature(displayId, mode, value,
 * cookie) calls on vendor.xiaomi.hardware.displayfeature.  This port runs the
 * stock ColorOS vendor, which has no such HAL, so the service silently drops
 * every call and the settings do nothing.
 *
 * The ColorOS Qualcomm composer still carries the panel's factory calibrated
 * color modes as vendor render intents (dumpsys SurfaceFlinger, "Color Modes":
 * sRGB container intents 260-263/265/300/301/303/307).  ColorOS itself selects
 * them through ColorDisplayService.setColorModeExt(intent) -> SurfaceFlinger
 * transaction 1023 (OplusFeatureColorMode.updateColorMode1, WCG 2.0 device):
 * standard 301, nature 303, vivid 307.  HyperOS SurfaceFlinger honours 1023 with
 * these intents, so we:
 *
 *   - map MIUI color modes to the ColorOS intents and apply them through
 *     DisplayTransformManager.setDisplayColor (also persists
 *     persist.sys.sf.native_mode so SurfaceFlinger boots with it);
 *   - keep ColorDisplayService from resetting it to "natural" (intent 0);
 *   - implement the MIUI color temperature presets / custom picker and the
 *     reading mode with DisplayTransformManager color matrices.
 */
final class ScreenColorBridgeHooks {
    private static final String DFMS = "com.android.server.display.DisplayFeatureManagerService";
    private static final String DTM = "com.android.server.display.color.DisplayTransformManager";
    private static final String LOCAL_SERVICES = "com.android.server.LocalServices";

    // MIUI setFeature modes
    private static final int MIUI_MODE_ADAPTIVE = 0; // 生动 (screen_optimize_mode 1)
    private static final int MIUI_MODE_VIVID = 1;    // 鲜艳 (screen_optimize_mode 2)
    private static final int MIUI_MODE_STANDARD = 2; // 专业原色 (screen_optimize_mode 3)
    private static final int MIUI_MODE_READING = 3;  // 阅读/护眼
    // Color temperature alone, sent when ro.(vendor.)all_modes.colorpick_adjust
    // is set (MiuiSettings.ScreenEffect.SUPPORT_UNLIMITED_COLOR_MODE).
    private static final int MIUI_MODE_UNLIMITED_COLOR = 23;

    // ColorOS WCG 2.0 render intents (OplusFeatureColorMode)
    private static final int INTENT_STANDARD = 301;
    private static final int INTENT_NATURE = 303;
    private static final int INTENT_VIVID = 307;

    // Color matrix levels (DisplayTransformManager multiplies them in order).
    private static final int LEVEL_READING = 101;     // just after night display (100)
    private static final int LEVEL_TEMPERATURE = 126; // just after white balance (125)

    // 高级模式: setFeature(26, value, cookie), cookie = parameter index
    // (com.android.server.display.expertmode.ExpertData.Cookie).
    private static final int MIUI_MODE_EXPERT = 26;
    private static final int EXPERT_GAMUT = 0;
    private static final int EXPERT_R = 1;
    private static final int EXPERT_G = 2;
    private static final int EXPERT_B = 3;
    private static final int EXPERT_HUE = 4;
    private static final int EXPERT_SATURATION = 5;
    private static final int EXPERT_VALUE = 6;
    private static final int EXPERT_CONTRAST = 7;
    private static final int EXPERT_GAMMA = 8;
    private static final int EXPERT_COOKIE_RESTORE = 9;
    private static final int EXPERT_COOKIE_SET_LAST = 10;
    private static final String[] EXPERT_KEYS = {
            "color_gamut", "color_r", "color_g", "color_b", "color_hue",
            "color_saturation", "color_value", "contrast_ratio", "gamma"};
    // haotian device_features expert_*_default
    private static final int[] EXPERT_DEFAULTS = {0, 255, 255, 255, 0, 0, 0, 0, 220};
    /** Expert gamut index (expert_gamut 0..3) -> ColorOS render intent. */
    private static final int[] EXPERT_GAMUT_INTENTS = {
            INTENT_VIVID, INTENT_STANDARD, INTENT_NATURE, INTENT_STANDARD};
    private static final int LEVEL_EXPERT = 127;

    private static volatile int sIntent = -1;
    private static ClassLoader sLoader;
    private static Object sService;
    private static final int[] sExpert = EXPERT_DEFAULTS.clone();
    private static boolean sExpertActive;

    private ScreenColorBridgeHooks() {
    }

    static void install(ClassLoader loader) {
        sLoader = loader;
        XposedHelpers.findAndHookMethod(DFMS, loader, "setDisplayFeature",
                int.class, int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            sService = param.thisObject;
                            handle((Integer) param.args[0], (Integer) param.args[1],
                                    (Integer) param.args[2], (Integer) param.args[3]);
                        } catch (Throwable error) {
                            HookLog.error("screen color: bridge failed", error);
                        }
                    }
                });
        // ColorDisplayService re-applies display_color_mode (0 = natural) at boot
        // and on user switch; keep the bridged ColorOS intent instead.
        XposedHelpers.findAndHookMethod(DTM, loader, "setColorMode",
                int.class, float[].class, float[].class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int intent = sIntent;
                        if (intent >= 256 && (Integer) param.args[0] == 0) {
                            param.args[0] = intent;
                            HookLog.info("screen color: ColorDisplayService natural -> intent " + intent);
                        }
                    }
                });
        HookLog.info("screen color: DisplayFeatureManagerService bridge hooked");
    }

    private static synchronized void handle(int displayId, int mode, int value, int cookie) {
        if (displayId != 0) {
            return;
        }
        switch (mode) {
            case MIUI_MODE_ADAPTIVE:
                leaveExpert();
                applyIntent(INTENT_NATURE);
                applyTemperature(value);
                break;
            case MIUI_MODE_VIVID:
                leaveExpert();
                applyIntent(INTENT_VIVID);
                applyTemperature(value);
                break;
            case MIUI_MODE_STANDARD:
                leaveExpert();
                applyIntent(INTENT_STANDARD);
                applyTemperature(value);
                break;
            case MIUI_MODE_READING:
                applyReading(value);
                break;
            case MIUI_MODE_UNLIMITED_COLOR:
                if (!sExpertActive) {
                    applyTemperature(value);
                }
                break;
            case MIUI_MODE_EXPERT:
                applyExpert(value, cookie);
                break;
            default:
                HookLog.once("screen-color-mode-" + mode,
                        "screen color: unbridged mode=" + mode + " value=" + value
                                + " cookie=" + cookie);
                break;
        }
    }

    private static void leaveExpert() {
        if (sExpertActive) {
            sExpertActive = false;
            setRawMatrix(LEVEL_EXPERT, null);
            HookLog.info("screen color: expert mode off");
        }
    }

    /**
     * cookie 0..8: one parameter changed (sent per slider move, and all nine by
     * DisplayFeatureManagerService.setExpertScreenMode); 9: restore defaults;
     * 10: re-apply the saved expert_data (sent when the expert page opens).
     */
    private static void applyExpert(int value, int cookie) {
        if (!sExpertActive) {
            sExpertActive = true;
            // MIUI disables the colour temperature picker in expert mode.
            setMatrix(LEVEL_TEMPERATURE, null);
        }
        if (cookie >= 0 && cookie < EXPERT_KEYS.length) {
            sExpert[cookie] = value;
        } else if (cookie == EXPERT_COOKIE_RESTORE) {
            System.arraycopy(EXPERT_DEFAULTS, 0, sExpert, 0, sExpert.length);
        } else if (cookie == EXPERT_COOKIE_SET_LAST) {
            loadExpertFromSettings();
        } else {
            HookLog.once("screen-color-expert-" + cookie,
                    "screen color: unknown expert cookie=" + cookie + " value=" + value);
            return;
        }
        int gamut = sExpert[EXPERT_GAMUT];
        int intent = EXPERT_GAMUT_INTENTS[Math.max(0, Math.min(gamut, EXPERT_GAMUT_INTENTS.length - 1))];
        if (intent != sIntent) {
            applyIntent(intent);
        }
        setRawMatrix(LEVEL_EXPERT, expertMatrix());
        HookLog.info("screen color: expert cookie=" + cookie + " value=" + value
                + " params=" + Arrays.toString(sExpert));
    }

    private static void loadExpertFromSettings() {
        try {
            android.content.Context context = (android.content.Context)
                    XposedHelpers.getObjectField(sService, "mContext");
            String json = android.provider.Settings.System.getString(
                    context.getContentResolver(), "expert_data");
            if (json == null || json.isEmpty()) {
                return;
            }
            org.json.JSONObject data = new org.json.JSONObject(json);
            for (int i = 0; i < EXPERT_KEYS.length; i++) {
                sExpert[i] = data.optInt(EXPERT_KEYS[i], sExpert[i]);
            }
        } catch (Throwable error) {
            HookLog.error("screen color: read expert_data failed", error);
        }
    }

    /**
     * Combine RGB gains, hue rotation, saturation, value and contrast into one
     * affine colour transform (column-major 4x4, as DisplayTransformManager
     * expects).  Gamma (cookie 8) has no linear equivalent and is ignored.
     */
    private static float[] expertMatrix() {
        boolean identity = true;
        for (int i = 1; i < EXPERT_GAMMA; i++) {
            if (sExpert[i] != EXPERT_DEFAULTS[i]) {
                identity = false;
                break;
            }
        }
        if (identity) {
            return null;
        }
        float[][] m = {
                {sExpert[EXPERT_R] / 255f, 0f, 0f},
                {0f, sExpert[EXPERT_G] / 255f, 0f},
                {0f, 0f, sExpert[EXPERT_B] / 255f}};
        // hue rotation around the luminance axis (-180..180 degrees)
        double th = Math.toRadians(sExpert[EXPERT_HUE]);
        float c = (float) Math.cos(th);
        float s = (float) Math.sin(th);
        m = mul(new float[][] {
                {0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f,
                        0.072f - c * 0.072f + s * 0.928f},
                {0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f,
                        0.072f - c * 0.072f - s * 0.283f},
                {0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f,
                        0.072f + c * 0.928f + s * 0.072f}}, m);
        // saturation -50..50 -> 0.5x..1.5x
        float sat = 1f + sExpert[EXPERT_SATURATION] / 100f;
        m = mul(new float[][] {
                {0.213f + 0.787f * sat, 0.715f - 0.715f * sat, 0.072f - 0.072f * sat},
                {0.213f - 0.213f * sat, 0.715f + 0.285f * sat, 0.072f - 0.072f * sat},
                {0.213f - 0.213f * sat, 0.715f - 0.715f * sat, 0.072f + 0.928f * sat}}, m);
        // value -255..255 -> 0.7x..1.3x
        float val = 1f + 0.3f * sExpert[EXPERT_VALUE] / 255f;
        // contrast -100..100 -> 0.5x..1.5x around mid grey
        float con = 1f + 0.5f * sExpert[EXPERT_CONTRAST] / 100f;
        float scale = val * con;
        float offset = 0.5f * (1f - con);
        float[] out = new float[16];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                out[col * 4 + row] = m[row][col] * scale;
            }
            out[12 + row] = offset;
        }
        out[15] = 1f;
        return out;
    }

    private static float[][] mul(float[][] a, float[][] b) {
        float[][] r = new float[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            }
        }
        return r;
    }

    private static void setRawMatrix(int level, float[] matrix) {
        Object dtm = dtm();
        if (dtm != null) {
            XposedHelpers.callMethod(dtm, "setColorMatrix", level, matrix);
        }
    }

    private static Object dtm() {
        Class<?> dtmClass = XposedHelpers.findClass(DTM, sLoader);
        Class<?> local = XposedHelpers.findClass(LOCAL_SERVICES, sLoader);
        return XposedHelpers.callStaticMethod(local, "getService", dtmClass);
    }

    private static void applyIntent(int intent) {
        Object dtm = dtm();
        if (dtm == null) {
            HookLog.once("screen-color-no-dtm", "screen color: DisplayTransformManager not ready");
            return;
        }
        sIntent = intent;
        XposedHelpers.callMethod(dtm, "setDisplayColor", intent, -1);
        HookLog.info("screen color: render intent " + intent);
    }

    /** value: 1 warm, 2 default, 3 cool, otherwise an ARGB color from the custom picker. */
    private static void applyTemperature(int value) {
        float[] gains;
        if (value == 1) {
            gains = new float[] {1.0f, 0.94f, 0.82f};
        } else if (value == 2 || value == 0) {
            gains = null;
        } else if (value == 3) {
            gains = new float[] {0.90f, 0.95f, 1.0f};
        } else {
            gains = pickerGains(value);
        }
        setMatrix(LEVEL_TEMPERATURE, gains);
        HookLog.info("screen color: temperature value=" + value + " gains="
                + Arrays.toString(gains));
    }

    /**
     * The custom picker stores ScreenColorAlgorithm.encode(pixel): same hue as
     * the picked tint with log-compressed chroma.  Scale channels so the
     * strongest stays at 1.0.
     */
    private static float[] pickerGains(int argb) {
        float r = ((argb >> 16) & 0xff) / 255f;
        float g = ((argb >> 8) & 0xff) / 255f;
        float b = (argb & 0xff) / 255f;
        float max = Math.max(r, Math.max(g, b));
        if (max <= 0f) {
            return null;
        }
        return new float[] {
                clamp(r / max, 0.6f), clamp(g / max, 0.6f), clamp(b / max, 0.6f)};
    }

    /** value: 0 off, otherwise MIUI reading-mode level (larger = warmer, up to 255). */
    private static void applyReading(int value) {
        float[] gains = null;
        if (value > 0) {
            float t = Math.min(1f, value / 255f);
            gains = new float[] {1.0f, 1.0f - 0.25f * t, 1.0f - 0.6f * t};
        }
        setMatrix(LEVEL_READING, gains);
        HookLog.info("screen color: reading value=" + value + " gains=" + Arrays.toString(gains));
    }

    private static void setMatrix(int level, float[] gains) {
        Object dtm = dtm();
        if (dtm == null) {
            return;
        }
        float[] matrix = null;
        if (gains != null) {
            matrix = new float[] {
                    gains[0], 0f, 0f, 0f,
                    0f, gains[1], 0f, 0f,
                    0f, 0f, gains[2], 0f,
                    0f, 0f, 0f, 1f};
        }
        XposedHelpers.callMethod(dtm, "setColorMatrix", level, matrix);
    }

    private static float clamp(float v, float min) {
        return Math.max(min, Math.min(1f, v));
    }
}
