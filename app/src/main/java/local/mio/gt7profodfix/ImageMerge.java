package local.mio.gt7profodfix;

/**
 * Images built with RMX5010_HyperOSFix scripts/merge_module_into_image.sh set
 * ro.rmx5010.image_merge=1. They already carry dex patches for some of the
 * hooks in this module (screen color, haptics, Settings device params and face
 * enroll, the ColorOS wallet power-key/gesture launch, camera gallery jump);
 * installing both would apply the same change twice or override the image's
 * behaviour, so those hooks are skipped there.
 */
final class ImageMerge {
    private static volatile Boolean merged;

    private ImageMerge() {
    }

    static boolean active() {
        Boolean value = merged;
        if (value == null) {
            value = Boolean.FALSE;
            try {
                Class<?> properties = Class.forName("android.os.SystemProperties");
                Object result = properties.getMethod("get", String.class, String.class)
                        .invoke(null, "ro.rmx5010.image_merge", "");
                value = "1".equals(result);
            } catch (Throwable ignored) {
            }
            merged = value;
        }
        return value;
    }

    /** True (and logs once) when the image already provides {@code what}. */
    static boolean skip(String what) {
        if (!active()) {
            return false;
        }
        HookLog.once("image_merge_skip_" + what,
                "image_merge=1: " + what + " is in the image, hook skipped");
        return true;
    }
}
