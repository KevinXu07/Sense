package local.mio.gt7profodfix;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Capture gate fix for the ported 6.070 OplusCamera.
 *
 * BaseMode.H7(m8.e, Z) is the last check before a shutter press becomes a
 * capture request. It returns:
 *
 *   decisionResult != null
 *     && decisionResult.mbAllowToCapture
 *     && Ub(decisionResult)                       // preview supports capture
 *     && decisionResult.mCameraId == getCameraId()
 *
 * On this device the OCS preview result carries no APS capture decision, so
 * m8.e is built with its defaults: mbAllowToCapture is true but mCameraId is
 * -1. getCameraId() returns the real id (0 for the wide camera), so the final
 * comparison fails and every shutter press logs
 * "isReadyToCapture, decisionResult is null or mCameraId not matched".
 *
 * -1 is the "no id supplied" default, not a conflicting id, so accept it when
 * every other condition the original method checks still holds.
 */
final class CameraCaptureFixHooks {
    /** Time of the last capture request; the device-error guard looks at it. */
    private static volatile long captureAt;

    private CameraCaptureFixHooks() {
    }

    static void install(ClassLoader loader) {
        final Class<?> base = XposedHelpers.findClass(
                "com.oplus.camera.module.BaseMode", loader);
        XposedBridge.hookAllMethods(base, "H7", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(param.getResult())) {
                    return;
                }
                try {
                    Object decision = param.args.length > 0 ? param.args[0] : null;
                    if (decision == null) {
                        return;
                    }
                    if (!Boolean.TRUE.equals(
                            XposedHelpers.getObjectField(decision, "a"))) {
                        return;
                    }
                    Object id = XposedHelpers.getObjectField(decision, "b");
                    if (!(id instanceof Integer) || (Integer) id != -1) {
                        return;
                    }
                    if (!Boolean.TRUE.equals(
                            XposedHelpers.callMethod(param.thisObject, "Ub", decision))) {
                        return;
                    }
                    param.setResult(true);
                    HookLog.once("capture-gate",
                            "capture gate: accepted decision with unset cameraId");
                } catch (Throwable error) {
                    HookLog.error("capture gate hook failed", error);
                }
            }
        });

        // ProducerImpl.takePicture refuses while the APS capture algo reports
        // itself as still initialising. Nothing on this device ever starts
        // that algorithm (the APS module parameter is not 1 or 2), so the
        // flag set in ApsAdapterImpl.init() is never cleared.
        for (String name : new String[]{
                "com.oplus.ocs.camera.consumer.apsAdapter.adapter.ApsAdapterImpl",
                "com.oplus.ocs.camera.consumer.ConsumerImpl"}) {
            try {
                XposedBridge.hookAllMethods(XposedHelpers.findClass(name, loader),
                        "isApsCaptureAlgoInitializing", new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (!Boolean.FALSE.equals(param.getResult())) {
                                    param.setResult(false);
                                    HookLog.once("aps-init",
                                            "capture: reported APS algo as initialised");
                                }
                            }
                        });
            } catch (Throwable error) {
                HookLog.error("APS init gate hook failed: " + name, error);
            }
        }

        // BaseMode.isAllowedToTakePicture first runs checkPreviewResult(),
        // which requires the APS preview result to carry KEY_PREVIEW_CAMERA_ID
        // equal to the request camera id. The APS preview algorithm supplies
        // no decision data on this device (the id comes through as -1, the same
        // empty decision seen in BaseMode.H7), so every shutter press returns
        // code 1 ("checkPreviewResult return false") from ProducerImpl.
        try {
            XposedBridge.hookAllMethods(XposedHelpers.findClass(
                            "com.oplus.ocs.camera.producer.mode.BaseMode", loader),
                    "checkPreviewResult", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!Boolean.TRUE.equals(param.getResult())) {
                                param.setResult(true);
                                HookLog.once("preview-result-gate",
                                        "capture: skipped preview-result camera id check");
                            }
                        }
                    });
        } catch (Throwable error) {
            HookLog.error("preview result gate hook failed", error);
        }

        // PhotoMode.isAllowedToTakePicture additionally waits for the APS
        // capture sensor mode list (CameraRequestTag.mCaptureSensorModeList).
        // With no APS decision that list is null and getCurrentSensorMode()
        // throws NullPointerException, aborting every capture. The super class
        // checks (already handled above) are all that matter here.
        try {
            XposedBridge.hookAllMethods(XposedHelpers.findClass(
                            "com.oplus.ocs.camera.producer.mode.PhotoMode", loader),
                    "isAllowedToTakePicture", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object tag = param.args.length > 2 ? param.args[2] : null;
                                if (tag == null) {
                                    return;
                                }
                                if (XposedHelpers.getObjectField(tag,
                                        "mCaptureSensorModeList") != null) {
                                    return;
                                }
                                param.setResult(0);
                                HookLog.once("sensor-mode-gate",
                                        "capture: allowed without APS sensor mode list");
                            } catch (Throwable error) {
                                HookLog.error("sensor mode gate failed", error);
                            }
                        }
                    });
        } catch (Throwable error) {
            HookLog.error("photo mode gate hook failed", error);
        }

        // The capture request count normally comes from the APS decision:
        // PhotoMode.createRequestTag overwrites mRequestNum with
        // mPreviewResult.get(KEY_PREVIEW_MULTI_FRAME_COUNT), which is 0 on
        // this device. Camera2Impl.takePicture then builds its request list
        // with "for (i = 1; i < mRequestNum; i++)", submits it through
        // captureBurst and the framework throws
        // IllegalArgumentException("Requests must have at least one element").
        // Patching the tag at the mode level is too early (the mode's own
        // override runs afterwards), so force it on the tag Camera2Impl
        // receives, where the count is actually consumed.
        try {
            XposedBridge.hookAllMethods(XposedHelpers.findClass(
                            "com.oplus.ocs.camera.producer.device.Camera2Impl", loader),
                    "takePicture", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object tag = param.args.length > 0 ? param.args[0] : null;
                                if (tag == null) {
                                    return;
                                }
                                captureAt = System.currentTimeMillis();
                                Object num = XposedHelpers.getObjectField(
                                        tag, "mRequestNum");
                                if (num instanceof Integer && (Integer) num <= 0) {
                                    XposedHelpers.setIntField(tag, "mRequestNum", 1);
                                    HookLog.once("request-num",
                                            "capture: forced request count to 1");
                                }
                                // BaseMode.createRequestTag sizes this array
                                // with the same (zero) request count, so it is
                                // empty and updatePreviewTarget throws
                                // ArrayIndexOutOfBoundsException on frame 0.
                                // A plain capture needs no preview target.
                                Object need = XposedHelpers.getObjectField(
                                        tag, "mbNeedPreviewStream");
                                int count = XposedHelpers.getIntField(
                                        tag, "mRequestNum");
                                if (need == null || ((boolean[]) need).length < count) {
                                    XposedHelpers.setObjectField(tag,
                                            "mbNeedPreviewStream", new boolean[count]);
                                }
                            } catch (Throwable error) {
                                HookLog.error("request count fix failed", error);
                            }
                        }
                    });
        } catch (Throwable error) {
            HookLog.error("request count hook failed", error);
        }

        installPictureSaver(loader);
        installPreviewFrameSaver(loader);
        installDeviceErrorGuard(loader);
    }

    /**
     * The rear capture never produces a usable still: the SAT photo use case
     * asks the HAL for a snapshot of three sensors at once plus two RAW16
     * streams, and the Xiaomi CamX + Oplus OEM layer combination cannot bring
     * up the extra IFE/CSID links. The snapshot frames that do arrive are
     * garbage, so keep the live preview frame instead: it is exposed
     * correctly and the preview stream is 1920x1440 YUV.
     *
     * ImageReader.acquireNextImage is hooked after the fact; the frame stays
     * owned by the app, we only read its planes while it is still valid.
     */
    private static void installPreviewFrameSaver(ClassLoader loader) {
        try {
            XposedBridge.hookAllMethods(android.media.ImageReader.class,
                    "acquireNextImage", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object result = param.getResult();
                                if (result instanceof android.media.Image) {
                                    savePreviewFrame((android.media.Image) result);
                                }
                            } catch (Throwable error) {
                                HookLog.error("capture: preview fallback failed", error);
                            }
                        }
                    });
        } catch (Throwable error) {
            HookLog.error("preview fallback hook failed", error);
        }
    }

    private static volatile long previewSavedFor;
    /** Capture whose HAL picture was already saved; the preview copy defers to it. */
    private static volatile long pictureSavedFor;

    private static void savePreviewFrame(android.media.Image image) {
        long at = captureAt;
        if (at == 0 || System.currentTimeMillis() - at > 1500) {
            return;
        }
        if (previewSavedFor == at) {
            return;
        }
        if (image.getFormat() != android.graphics.ImageFormat.YUV_420_888) {
            return;
        }
        final int width = image.getWidth();
        final int height = image.getHeight();
        // Skip the tiny metadata/picture readers; the preview is the only
        // large YUV frame queued after a shutter press.
        if (width < 1000 || height < 700) {
            return;
        }
        previewSavedFor = at;
        final byte[] nv21 = toNv21(image, width, height);
        if (nv21 == null) {
            return;
        }
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                // The main sensor's picture normally arrives ~700 ms after
                // the shutter; if it did, it is the better photo.
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if (pictureSavedFor == at) {
                    HookLog.info("capture: skipped preview copy, HAL picture saved");
                    return;
                }
                writeJpeg(nv21, width, height, "_preview");
            }
        }, "sense-preview-save");
        writer.setPriority(Thread.MIN_PRIORITY);
        writer.start();
    }

    /**
     * The HAL delivers the finished capture image to the consumer before the
     * snapshot pipeline reports its error, but the app never finishes its own
     * save: the device error makes it kill itself. Persist the image from
     * ConsumerImpl.onHalPictureImageArrived (ImageReader, Image, int, int,
     * int, CameraRequestTag, String) instead, so a shutter press still leaves
     * a file behind.
     */
    private static void installPictureSaver(ClassLoader loader) {
        try {
            Class<?> consumer = XposedHelpers.findClass(
                    "com.oplus.ocs.camera.consumer.ConsumerImpl", loader);
            XposedBridge.hookAllMethods(consumer, "onHalPictureImageArrived",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                addPicture(param.args, param.thisObject);
                            } catch (Throwable error) {
                                HookLog.error("capture: saving HAL image failed", error);
                            }
                        }
                    });
        } catch (Throwable error) {
            HookLog.error("picture saver hook failed", error);
        }
    }

    private static void addPicture(Object[] args, Object consumer) {
        if (args.length < 2 || !(args[1] instanceof android.media.Image)) {
            HookLog.once("picture-arg", "capture: unexpected picture callback args");
            return;
        }
        android.media.Image image = (android.media.Image) args[1];
        String surfaceKey = args.length > 6 && args[6] instanceof String
                ? (String) args[6] : "?";
        int role = args.length > 2 && args[2] instanceof Integer ? (Integer) args[2] : -1;
        int physicalId = args.length > 3 && args[3] instanceof Integer
                ? (Integer) args[3] : -1;
        // Only the main sensor produces a valid rear snapshot here; the wide
        // and tele capture streams go through the offline Bayer2Yuv path
        // whose input frames the HAL never captured (CSID config fails), so
        // they arrive as noise. The preview copy covers those shots.
        if (role > 1) {
            HookLog.once("picture-skip", "capture: ignoring role " + role
                    + " (not the main sensor)");
            return;
        }
        byte[] jpeg = toJpeg(image);
        if (jpeg == null || jpeg.length < 1024) {
            HookLog.once("picture-empty", "capture: no JPEG from " + surfaceKey);
            return;
        }
        File dir = new File("/sdcard/DCIM/Camera");
        if (!dir.exists() && !dir.mkdirs()) {
            HookLog.once("picture-dir", "capture: cannot create " + dir);
            return;
        }
        File out = new File(dir, "OPLUS_SENSE_" + System.currentTimeMillis()
                + "_r" + role + "_p" + physicalId + ".jpg");
        try {
            FileOutputStream stream = new FileOutputStream(out);
            try {
                stream.write(jpeg);
            } finally {
                stream.close();
            }
        } catch (Throwable error) {
            HookLog.error("capture: writing " + out + " failed", error);
            return;
        }
        HookLog.info("capture: wrote " + out.getAbsolutePath()
                + " (" + jpeg.length + " bytes, " + surfaceKey + ")");
        pictureSavedFor = captureAt;
        publish(out);
    }

    /**
     * The capture surface is YUV_420_888 (format 35); the SDK turns it into a
     * JPEG with the same NV21 conversion its own Util.buildJpegImage uses.
     */
    private static byte[] toJpeg(android.media.Image image) {
        int format = image.getFormat();
        if (format == android.graphics.ImageFormat.JPEG) {
            ByteBuffer plane = image.getPlanes()[0].getBuffer();
            byte[] data = new byte[plane.remaining()];
            plane.get(data);
            return data;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] nv21 = toNv21(image, width, height);
        return nv21 == null ? null : compressNv21(nv21, width, height);
    }

    private static byte[] toNv21(android.media.Image image, int width, int height) {
        android.media.Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) {
            HookLog.once("picture-format", "capture: image has "
                    + planes.length + " planes, format " + image.getFormat());
            return null;
        }
        byte[] nv21 = new byte[width * height * 3 / 2];
        ByteBuffer yPlane = planes[0].getBuffer();
        int yRow = planes[0].getRowStride();
        int yPixel = planes[0].getPixelStride();
        int offset = 0;
        for (int row = 0; row < height; row++) {
            int line = row * yRow;
            for (int col = 0; col < width; col++) {
                nv21[offset++] = yPlane.get(line + col * yPixel);
            }
        }
        ByteBuffer uPlane = planes[1].getBuffer();
        ByteBuffer vPlane = planes[2].getBuffer();
        int uvRow = planes[1].getRowStride();
        int uvPixel = planes[1].getPixelStride();
        int chromaWidth = width / 2;
        int chromaHeight = height / 2;
        for (int row = 0; row < chromaHeight; row++) {
            int line = row * uvRow;
            for (int col = 0; col < chromaWidth; col++) {
                int index = line + col * uvPixel;
                nv21[offset++] = vPlane.get(index);
                nv21[offset++] = uPlane.get(index);
            }
        }
        return nv21;
    }

    private static byte[] compressNv21(byte[] nv21, int width, int height) {
        android.graphics.YuvImage yuv = new android.graphics.YuvImage(
                nv21, android.graphics.ImageFormat.NV21, width, height, null);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        if (!yuv.compressToJpeg(
                new android.graphics.Rect(0, 0, width, height), 92, out)) {
            return null;
        }
        return out.toByteArray();
    }

    private static void writeJpeg(byte[] nv21, int width, int height, String suffix) {
        byte[] jpeg = compressNv21(nv21, width, height);
        if (jpeg == null || jpeg.length < 1024) {
            HookLog.once("picture-empty", "capture: no JPEG from preview frame");
            return;
        }
        File dir = new File("/sdcard/DCIM/Camera");
        if (!dir.exists() && !dir.mkdirs()) {
            HookLog.once("picture-dir", "capture: cannot create " + dir);
            return;
        }
        File out = new File(dir, "OPLUS_SENSE_" + System.currentTimeMillis()
                + suffix + ".jpg");
        try {
            FileOutputStream stream = new FileOutputStream(out);
            try {
                stream.write(jpeg);
            } finally {
                stream.close();
            }
        } catch (Throwable error) {
            HookLog.error("capture: writing " + out + " failed", error);
            return;
        }
        HookLog.info("capture: wrote " + out.getAbsolutePath()
                + " (" + jpeg.length + " bytes, " + width + "x" + height + ")");
        publish(out);
    }

    /**
     * The app's own MediaStore insert leaves no row behind (its gallery
     * provider is missing), so index the file we wrote to make it visible to
     * gallery and other apps.
     */
    private static void publish(File file) {
        try {
            Object application = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (!(application instanceof android.content.Context)) {
                return;
            }
            android.media.MediaScannerConnection.scanFile(
                    (android.content.Context) application,
                    new String[]{file.getAbsolutePath()},
                    new String[]{"image/jpeg"}, null);
            HookLog.info("capture: asked the media scanner for "
                    + file.getName());
        } catch (Throwable error) {
            HookLog.error("capture: media scan failed", error);
        }
    }

    /**
     * A capture makes the HAL's snapshot pipeline fail on this device
     * (CSID/VFE config returns -EINVAL for the multi-sensor SAT request), and
     * the framework reports ERROR_CAMERA_DEVICE about 50 ms later, while the
     * app is still writing the picture. Camera2StateMachineImpl$1.onError
     * forwards that to the app, which answers with "camera app will kill
     * self", so the file never lands. Drop the error inside a short window
     * after a capture request; later genuine errors still surface.
     */
    private static void installDeviceErrorGuard(ClassLoader loader) {
        try {
            Class<?> callback = XposedHelpers.findClass(
                    "com.oplus.ocs.camera.producer.device.Camera2StateMachineImpl$1",
                    loader);
            XposedBridge.hookAllMethods(callback, "onError", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    long at = captureAt;
                    long since = System.currentTimeMillis() - at;
                    if (at == 0 || since > 5000) {
                        return;
                    }
                    Object code = param.args.length > 1 ? param.args[1] : null;
                    if (code instanceof Integer && (Integer) code != 4) {
                        return;
                    }
                    // Camera2StateMachineImpl$1.onError forwards the error to
                    // the app, which answers with "camera app will kill self".
                    // The save is already running on another thread, so drop
                    // the error instead of letting the app die mid-save. The
                    // window is short so later genuine errors still surface.
                    param.setResult(null);
                    HookLog.once("device-error-guard",
                            "capture: kept the app alive after a device error");
                }
            });
        } catch (Throwable error) {
            HookLog.error("device error guard hook failed", error);
        }
    }
}
