/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.ui.Components;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.InputType;
import android.widget.EditText;

import io.github.webarmour.videomessagerecorder.RecordingMode;
import io.github.webarmour.videomessagerecorder.camera.AePrioritySetting;
import io.github.webarmour.videomessagerecorder.camera.HotPixelSetting;
import io.github.webarmour.videomessagerecorder.camera.ShadingSetting;
import io.github.webarmour.videomessagerecorder.camera.DistortionCorrectionSetting;
import io.github.webarmour.videomessagerecorder.camera.AberrationCorrectionSetting;
import io.github.webarmour.videomessagerecorder.camera.ExposureMode;
import io.github.webarmour.videomessagerecorder.camera.AntibandingSetting;
import io.github.webarmour.videomessagerecorder.camera.AwbSetting;
import io.github.webarmour.videomessagerecorder.camera.ColorCorrectionSetting;
import io.github.webarmour.videomessagerecorder.camera.EdgeSetting;
import io.github.webarmour.videomessagerecorder.camera.FocusSetting;
import io.github.webarmour.videomessagerecorder.camera.NoiseReductionSetting;
import io.github.webarmour.videomessagerecorder.camera.OpticalStabilizationSetting;
import io.github.webarmour.videomessagerecorder.camera.RecordingConfig;
import io.github.webarmour.videomessagerecorder.camera.StabilizationSetting;
import io.github.webarmour.videomessagerecorder.camera.TonemapSetting;
import io.github.webarmour.videomessagerecorder.camera.VideoQuality;

/** Experimental Camera2 based AAR recorder settings, scoped to video notes. */
public final class RoundVideoRecorderSettings {
    private static final String PREFS = "round_video_custom_recorder";
    private static final String RECORDER = "recorder";
    private static final String REMEMBER_LAST_CAMERA = "remember_last_camera";
    private static final String LAST_CAMERA_REAR = "last_camera_rear";
    private static final String MODE = "mode";
    private static final String QUALITY = "quality";
    private static final String FPS = "fps";
    private static final String BITRATE = "bitrate";
    private static final String NOISE = "noise";
    private static final String STABILIZATION = "stabilization";
    private static final String EDGE = "edge";
    private static final String HOLD_FPS = "hold_fps";
    private static final String LOW_LIGHT_BOOST = "low_light_boost";
    private static final String AE_PRIORITY = "ae_priority";
    private static final String EXPOSURE_STEPS = "exposure_steps";
    private static final String AWB = "awb";
    private static final String FOCUS = "focus";
    private static final String ANTIBANDING = "antibanding";
    private static final String OPTICAL_STABILIZATION = "optical_stabilization";
    private static final String COLOR_CORRECTION = "color_correction";
    private static final String TONEMAP = "tonemap";
    private static final String ZOOM = "zoom";
    public static final String REAR_GL_ZOOM = "rear_gl_zoom";
    private static final String AUDIO_BITRATE = "audio_bitrate";
    private static final String HOT_PIXEL = "hot_pixel";
    private static final String SHADING = "shading";
    private static final String DISTORTION = "distortion";
    private static final String ABERRATION = "aberration";
    private static final String EXPOSURE_MODE = "exposure_mode";
    private static final String MANUAL_ISO = "manual_iso";
    private static final String MANUAL_EXPOSURE_US = "manual_exposure_us";
    private static final String MANUAL_FOCUS_DISTANCE = "manual_focus_distance";
    private static final String AE_LOCK = "ae_lock";
    private static final String AWB_LOCK = "awb_lock";
    private static final String COLOR_TEMPERATURE = "color_temperature_kelvin";
    // CUSTOM_RECORDER_CONFIGURABLE_START_DELAY_V1
    private static final String RECORDING_START_DELAY = "recording_start_delay_ms";
    private static final String MAX_DURATION = "max_duration_seconds";
    public static final String ENABLE_CONCURRENT_PREWARM = "enable_concurrent_prewarm";
    public static final String CONCURRENT_PREWARM_FPS = "concurrent_prewarm_fps";
    public static final String FACE_AWARE_METERING = "face_aware_metering";
    public static final String FACE_AWARE_EXPOSURE = "face_aware_exposure";
    private static final int DEFAULT_MAX_DURATION_SECONDS = 60;
    private static final int DEFAULT_RECORDING_START_DELAY_MS = 200;
    private static final int MAX_RECORDING_START_DELAY_MS = 2000;
    public static final boolean DEFAULT_ENABLE_CONCURRENT_PREWARM = false;
    public static final int DEFAULT_CONCURRENT_PREWARM_FPS = 30;
    public static final boolean DEFAULT_FACE_AWARE_METERING = true;
    public static final boolean DEFAULT_FACE_AWARE_EXPOSURE = false;

    // Defaults for new Easygram installs (existing SharedPreferences always take precedence).
    // Resetting CustomRecorder options also returns to these defaults.
    private static final boolean DEFAULT_RECORDER = true;
    private static final boolean DEFAULT_MANUAL_MODE = true;
    private static final String DEFAULT_QUALITY = "MAX"; // 640 x 640
    private static final int DEFAULT_FPS = 60;
    private static final boolean DEFAULT_LOW_LIGHT_BOOST = false;
    private static final boolean DEFAULT_HOLD_FPS = true;
    private static final AePrioritySetting DEFAULT_AE_PRIORITY = AePrioritySetting.OFF;
    private static final int DEFAULT_EXPOSURE_STEPS = -4;
    private static final AwbSetting DEFAULT_AWB = AwbSetting.AUTO;
    private static final FocusSetting DEFAULT_FOCUS = FocusSetting.CONTINUOUS_VIDEO;
    private static final AntibandingSetting DEFAULT_ANTIBANDING = AntibandingSetting.AUTO;
    private static final float DEFAULT_ZOOM = 0.8f;
    private static final NoiseReductionSetting DEFAULT_NOISE = NoiseReductionSetting.OFF;
    private static final StabilizationSetting DEFAULT_STABILIZATION = StabilizationSetting.OEM_DEFAULT;
    private static final OpticalStabilizationSetting DEFAULT_OPTICAL_STABILIZATION = OpticalStabilizationSetting.ON;
    private static final EdgeSetting DEFAULT_EDGE = EdgeSetting.HIGH_QUALITY;
    private static final ColorCorrectionSetting DEFAULT_COLOR_CORRECTION = ColorCorrectionSetting.HIGH_QUALITY;
    private static final TonemapSetting DEFAULT_TONEMAP = TonemapSetting.HIGH_QUALITY;
    private static final int DEFAULT_AUDIO_BITRATE = 128_000;

    private static final String[] QUALITIES = {
            "COMPACT", "DEFAULT_TELEGRAM", "MEDIUM", "HIGH", "MAX"
    };
    private static final String[] QUALITY_LABELS = {
            "320 × 320 · экономия места",
            "384 × 384 · как в Telegram",
            "400 × 400 · стандартное",
            "480 × 480 · улучшенное",
            "640 × 640 · максимальное"
    };

    private RoundVideoRecorderSettings() { }

    public static boolean isAutomatic(Context context) {
        return !prefs(context).getBoolean(MODE, DEFAULT_MANUAL_MODE);
    }

    public static boolean rememberLastCamera(Context context) {
        return prefs(context).getBoolean(REMEMBER_LAST_CAMERA, false);
    }

    public static boolean lastCameraWasRear(Context context) {
        return prefs(context).getBoolean(LAST_CAMERA_REAR, false);
    }

    public static void setLastCameraWasRear(Context context, boolean rear) {
        prefs(context).edit().putBoolean(LAST_CAMERA_REAR, rear).apply();
    }

    /** Fill missing preferences once, preserving existing user choices. */
    private static SharedPreferences prefs(Context c) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean("easygram_native_defaults_initialized_v1", false)) {
            synchronized (RoundVideoRecorderSettings.class) {
                if (!p.getBoolean("easygram_native_defaults_initialized_v1", false)) {
                    SharedPreferences.Editor e = p.edit();
                    if (!p.contains(RECORDER)) e.putBoolean(RECORDER, true);
                    if (!p.contains(MODE)) e.putBoolean(MODE, true);
                    if (!p.contains(QUALITY)) e.putString(QUALITY, "MAX");
                    if (!p.contains(FPS)) e.putInt(FPS, 60);
                    if (!p.contains(BITRATE)) e.putInt(BITRATE, 2_000_000);
                    if (!p.contains(HOLD_FPS)) e.putBoolean(HOLD_FPS, true);
                    if (!p.contains(LOW_LIGHT_BOOST)) e.putBoolean(LOW_LIGHT_BOOST, false);
                    if (!p.contains(AE_PRIORITY)) e.putString(AE_PRIORITY, "OFF");
                    if (!p.contains(EXPOSURE_STEPS)) e.putInt(EXPOSURE_STEPS, -4);
                    if (!p.contains(AWB)) e.putString(AWB, "AUTO");
                    if (!p.contains(FOCUS)) e.putString(FOCUS, "CONTINUOUS_VIDEO");
                    if (!p.contains(ANTIBANDING)) e.putString(ANTIBANDING, "HZ_60");
                    if (!p.contains(ZOOM)) e.putInt(ZOOM, Float.floatToIntBits(0.8f));
                    if (!p.contains(REAR_GL_ZOOM)) e.putBoolean(REAR_GL_ZOOM, false);
                    if (!p.contains(NOISE)) e.putString(NOISE, "OFF");
                    if (!p.contains(STABILIZATION)) e.putString(STABILIZATION, "OEM_DEFAULT");
                    if (!p.contains(OPTICAL_STABILIZATION)) e.putString(OPTICAL_STABILIZATION, "ON");
                    if (!p.contains(EDGE)) e.putString(EDGE, "HIGH_QUALITY");
                    if (!p.contains(COLOR_CORRECTION)) e.putString(COLOR_CORRECTION, "HIGH_QUALITY");
                    if (!p.contains(TONEMAP)) e.putString(TONEMAP, "HIGH_QUALITY");
                    if (!p.contains(AUDIO_BITRATE)) e.putInt(AUDIO_BITRATE, 128_000);
                    if (!p.contains(RECORDING_START_DELAY)) e.putInt(RECORDING_START_DELAY, 200);
                    if (!p.contains(MAX_DURATION)) e.putInt(MAX_DURATION, DEFAULT_MAX_DURATION_SECONDS);
                    if (!p.contains(ENABLE_CONCURRENT_PREWARM)) e.putBoolean(ENABLE_CONCURRENT_PREWARM, DEFAULT_ENABLE_CONCURRENT_PREWARM);
                    if (!p.contains(CONCURRENT_PREWARM_FPS)) e.putInt(CONCURRENT_PREWARM_FPS, DEFAULT_CONCURRENT_PREWARM_FPS);
                    if (!p.contains(FACE_AWARE_METERING)) e.putBoolean(FACE_AWARE_METERING, DEFAULT_FACE_AWARE_METERING);
                    if (!p.contains(FACE_AWARE_EXPOSURE)) e.putBoolean(FACE_AWARE_EXPOSURE, DEFAULT_FACE_AWARE_EXPOSURE);
                    e.putBoolean("easygram_native_defaults_initialized_v1", true).apply();
                }
            }
            sanitizePreferences(c, new RoundVideoCameraSupport(c));
        }
        return p;
    }

    private static int nearestSupportedInt(RoundVideoCameraSupport support, String key, int current, int fallback, int[] candidates) {
        if (support.optionAvailable(key, Integer.toString(current))) return current;
        Integer best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (int candidate : candidates) {
            if (support.optionAvailable(key, Integer.toString(candidate))) {
                int diff = Math.abs(candidate - current);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = candidate;
                }
            }
        }
        return best != null ? best : fallback;
    }

    private static String sanitizeMode(RoundVideoCameraSupport support, String key, String current, String inactiveFallback, String... activeFallbacks) {
        if (!support.rowAvailable(key)) {
            return support.optionAvailable(key, inactiveFallback) ? inactiveFallback : "OEM_DEFAULT";
        }
        if (current != null && support.optionAvailable(key, current)) {
            return current;
        }
        return support.firstSupported(key, "OEM_DEFAULT", activeFallbacks);
    }

    /**
     * Validates all stored preferences against the device's actual Camera2 capabilities, clamping
     * unsupported values into the supported range and turning off unavailable switches.
     */
    public static void sanitizePreferences(Context context, RoundVideoCameraSupport support) {
        if (context == null || support == null) return;
        SharedPreferences p = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = p.edit();

        String rawQuality = p.getString(QUALITY, DEFAULT_QUALITY);
        String normalizedQuality = rawQuality;
        if ("TELEGRAM".equals(rawQuality)) normalizedQuality = "DEFAULT_TELEGRAM";
        else if ("IPHONE_LIKE".equals(rawQuality)) normalizedQuality = "MEDIUM";
        else if ("TELEGRAM_NOTE_MAX".equals(rawQuality) || "QHD".equals(rawQuality)) normalizedQuality = "MAX";
        if (normalizedQuality == null || !support.optionAvailable(QUALITY, normalizedQuality)) {
            normalizedQuality = support.firstSupported(QUALITY, "MAX",
                    "MAX", "HIGH", "MEDIUM", "DEFAULT_TELEGRAM", "COMPACT");
        }
        if (!normalizedQuality.equals(rawQuality)) {
            edit.putString(QUALITY, normalizedQuality);
        }

        int fps = p.getInt(FPS, DEFAULT_FPS);
        if (!support.optionAvailable(FPS, Integer.toString(fps))) {
            fps = Integer.parseInt(support.firstSupported(FPS, "30", "60", "30", "24"));
            edit.putInt(FPS, fps);
        }

        boolean holdFps = p.getBoolean(HOLD_FPS, DEFAULT_HOLD_FPS);
        if (holdFps && !support.canHoldFps(fps)) {
            holdFps = false;
            edit.putBoolean(HOLD_FPS, false);
        }

        boolean enablePrewarm = p.getBoolean(ENABLE_CONCURRENT_PREWARM, DEFAULT_ENABLE_CONCURRENT_PREWARM);
        if (enablePrewarm && !support.isPrewarmAvailable()) {
            enablePrewarm = false;
            edit.putBoolean(ENABLE_CONCURRENT_PREWARM, false);
        }

        if (p.getBoolean(REMEMBER_LAST_CAMERA, false) && !support.hasMultipleCameras()) {
            edit.putBoolean(REMEMBER_LAST_CAMERA, false);
        }

        int prewarmFps = DEFAULT_CONCURRENT_PREWARM_FPS;
        try {
            prewarmFps = p.getInt(CONCURRENT_PREWARM_FPS, DEFAULT_CONCURRENT_PREWARM_FPS);
        } catch (Exception e) {
            try {
                prewarmFps = Integer.parseInt(p.getString(CONCURRENT_PREWARM_FPS, "30"));
            } catch (Exception ignore) {}
        }
        if (!support.optionAvailable(CONCURRENT_PREWARM_FPS, Integer.toString(prewarmFps))) {
            prewarmFps = Integer.parseInt(support.firstSupported(CONCURRENT_PREWARM_FPS, "30", "30", "15", "60"));
        }
        edit.putInt(CONCURRENT_PREWARM_FPS, prewarmFps);

        String exposureMode = p.getString(EXPOSURE_MODE, "AUTO");
        if (exposureMode == null || !support.optionAvailable(EXPOSURE_MODE, exposureMode)) {
            exposureMode = "AUTO";
            edit.putString(EXPOSURE_MODE, exposureMode);
        }
        boolean manualExposure = "MANUAL".equals(exposureMode);

        boolean lowLight = p.getBoolean(LOW_LIGHT_BOOST, DEFAULT_LOW_LIGHT_BOOST);
        if (lowLight && (manualExposure || fps > 30 || !support.optionAvailable(LOW_LIGHT_BOOST, "true"))) {
            lowLight = false;
            edit.putBoolean(LOW_LIGHT_BOOST, false);
        }

        String aePriority = p.getString(AE_PRIORITY, DEFAULT_AE_PRIORITY.name());
        if (aePriority == null || manualExposure || lowLight || !support.optionAvailable(AE_PRIORITY, aePriority)) {
            aePriority = "OFF";
            edit.putString(AE_PRIORITY, aePriority);
        }

        boolean aeLock = p.getBoolean(AE_LOCK, false);
        if (aeLock && (manualExposure || !support.optionAvailable(AE_LOCK, "true"))) {
            aeLock = false;
            edit.putBoolean(AE_LOCK, false);
        }

        String colorCorrection = sanitizeMode(support, COLOR_CORRECTION,
                p.getString(COLOR_CORRECTION, DEFAULT_COLOR_CORRECTION.name()),
                "OEM_DEFAULT", "HIGH_QUALITY", "FAST", "OEM_DEFAULT", "CCT");
        edit.putString(COLOR_CORRECTION, colorCorrection);

        boolean awbLock = p.getBoolean(AWB_LOCK, false);
        if (awbLock && ("CCT".equals(colorCorrection) || !support.optionAvailable(AWB_LOCK, "true"))) {
            awbLock = false;
            edit.putBoolean(AWB_LOCK, false);
        }

        boolean faceMetering = p.getBoolean(FACE_AWARE_METERING, DEFAULT_FACE_AWARE_METERING);
        if (faceMetering && !support.isFaceMeteringAvailable()) {
            faceMetering = false;
            edit.putBoolean(FACE_AWARE_METERING, false);
        }

        boolean faceExposure = p.getBoolean(FACE_AWARE_EXPOSURE, DEFAULT_FACE_AWARE_EXPOSURE);
        if (faceExposure && (!faceMetering || !support.isFaceExposureAvailable() || manualExposure || aeLock)) {
            faceExposure = false;
            edit.putBoolean(FACE_AWARE_EXPOSURE, false);
        }

        int steps = p.getInt(EXPOSURE_STEPS, DEFAULT_EXPOSURE_STEPS);
        int validSteps = nearestSupportedInt(support, EXPOSURE_STEPS, steps, 0,
                new int[]{-7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3});
        if (validSteps != steps) {
            edit.putInt(EXPOSURE_STEPS, validSteps);
        }

        String awb = p.getString(AWB, DEFAULT_AWB.name());
        if (awb == null || !support.optionAvailable(AWB, awb)) {
            awb = support.firstSupported(AWB, "AUTO",
                    "AUTO", "DAYLIGHT", "CLOUDY_DAYLIGHT", "INCANDESCENT", "FLUORESCENT", "WARM_FLUORESCENT", "TWILIGHT", "SHADE");
            edit.putString(AWB, awb);
        }

        String focus = p.getString(FOCUS, DEFAULT_FOCUS.name());
        if (focus == null || !support.optionAvailable(FOCUS, focus)) {
            focus = support.firstSupported(FOCUS, "CONTINUOUS_VIDEO", "CONTINUOUS_VIDEO", "AUTO", "MANUAL");
            edit.putString(FOCUS, focus);
        }

        int iso = p.getInt(MANUAL_ISO, 400);
        int validIso = nearestSupportedInt(support, MANUAL_ISO, iso, 400,
                new int[]{100, 200, 400, 800, 1600, 3200});
        if (validIso != iso) {
            edit.putInt(MANUAL_ISO, validIso);
        }

        int shutterUs = p.getInt(MANUAL_EXPOSURE_US, 10000);
        int validShutterUs = nearestSupportedInt(support, MANUAL_EXPOSURE_US, shutterUs, 10000,
                new int[]{1000, 2000, 4000, 8000, 10000, 16667, 33333});
        if (validShutterUs != shutterUs) {
            edit.putInt(MANUAL_EXPOSURE_US, validShutterUs);
        }

        float focusDist = Float.intBitsToFloat(p.getInt(MANUAL_FOCUS_DISTANCE, Float.floatToIntBits(0f)));
        String focusDistStr = focusDist == (int) focusDist
                ? Integer.toString((int) focusDist)
                : String.format(java.util.Locale.US, "%.1f", focusDist);
        if (!support.optionAvailable(MANUAL_FOCUS_DISTANCE, focusDistStr)) {
            float validFocusDist = Float.parseFloat(support.firstSupported(
                    MANUAL_FOCUS_DISTANCE, "0", "0", "0.5", "1", "2", "5", "10"));
            edit.putInt(MANUAL_FOCUS_DISTANCE, Float.floatToIntBits(validFocusDist));
        }

        String antibanding = p.getString(ANTIBANDING, DEFAULT_ANTIBANDING.name());
        if (antibanding == null || !support.optionAvailable(ANTIBANDING, antibanding)) {
            antibanding = support.firstSupported(ANTIBANDING, "AUTO", "AUTO", "HZ_50", "HZ_60", "OFF");
            edit.putString(ANTIBANDING, antibanding);
        }

        float zoom = Float.intBitsToFloat(p.getInt(ZOOM, Float.floatToIntBits(DEFAULT_ZOOM)));
        String zoomStr = String.format(java.util.Locale.US, "%.1f", zoom);
        if (!support.optionAvailable(ZOOM, zoomStr)) {
            float validZoom = Float.parseFloat(support.firstSupported(
                    ZOOM, "1.0", "1.0", "0.8", "1.2", "1.5", "2.0"));
            edit.putInt(ZOOM, Float.floatToIntBits(validZoom));
        }

        if (p.getBoolean(REAR_GL_ZOOM, false) && !support.rowAvailable(REAR_GL_ZOOM)) {
            edit.putBoolean(REAR_GL_ZOOM, false);
        }

        edit.putString(NOISE, sanitizeMode(support, NOISE,
                p.getString(NOISE, DEFAULT_NOISE.name()),
                "OFF", "OFF", "MINIMAL", "FAST", "HIGH_QUALITY", "OEM_DEFAULT"));
        edit.putString(STABILIZATION, sanitizeMode(support, STABILIZATION,
                p.getString(STABILIZATION, DEFAULT_STABILIZATION.name()),
                "OFF", "OEM_DEFAULT", "VIDEO", "PREVIEW", "OFF"));
        edit.putString(OPTICAL_STABILIZATION, sanitizeMode(support, OPTICAL_STABILIZATION,
                p.getString(OPTICAL_STABILIZATION, DEFAULT_OPTICAL_STABILIZATION.name()),
                "OFF", "ON", "OFF", "OEM_DEFAULT"));
        edit.putString(EDGE, sanitizeMode(support, EDGE,
                p.getString(EDGE, DEFAULT_EDGE.name()),
                "OEM_DEFAULT", "HIGH_QUALITY", "FAST", "OFF", "OEM_DEFAULT"));
        edit.putString(TONEMAP, sanitizeMode(support, TONEMAP,
                p.getString(TONEMAP, DEFAULT_TONEMAP.name()),
                "OEM_DEFAULT", "HIGH_QUALITY", "FAST", "OEM_DEFAULT"));
        edit.putString(HOT_PIXEL, sanitizeMode(support, HOT_PIXEL,
                p.getString(HOT_PIXEL, "FAST"),
                "OEM_DEFAULT", "FAST", "HIGH_QUALITY", "OFF", "OEM_DEFAULT"));
        edit.putString(SHADING, sanitizeMode(support, SHADING,
                p.getString(SHADING, "FAST"),
                "OEM_DEFAULT", "FAST", "HIGH_QUALITY", "OFF", "OEM_DEFAULT"));
        edit.putString(DISTORTION, sanitizeMode(support, DISTORTION,
                p.getString(DISTORTION, "FAST"),
                "OFF", "FAST", "HIGH_QUALITY", "OFF", "OEM_DEFAULT"));
        edit.putString(ABERRATION, sanitizeMode(support, ABERRATION,
                p.getString(ABERRATION, "FAST"),
                "OEM_DEFAULT", "FAST", "HIGH_QUALITY", "OFF", "OEM_DEFAULT"));

        int kelvin = p.getInt(COLOR_TEMPERATURE, 5000);
        int validKelvin = nearestSupportedInt(support, COLOR_TEMPERATURE, kelvin, 5000,
                new int[]{2700, 3200, 4000, 5000, 5600, 6500, 7500});
        if (validKelvin != kelvin) {
            edit.putInt(COLOR_TEMPERATURE, validKelvin);
        }

        edit.apply();

        int maxAllowed = maxAllowedDurationSeconds(context);
        if (p.getInt(MAX_DURATION, DEFAULT_MAX_DURATION_SECONDS) > maxAllowed) {
            p.edit().putInt(MAX_DURATION, maxAllowed).apply();
        }
    }

    /** CustomRecorder is the only selectable video-note recorder on Android 9+.
     *  Android 8 and older use the existing legacy path (AAR minSdk is 28).
     */
    public static boolean useCustomRecorder(Context c) {
        return Build.VERSION.SDK_INT >= 28;
    }

    /** Delay from camera-ready AND first preview frame to actual AAR recording start.
     *  0 ms disables the additional timer but retains the ready/first-frame gate.
     *  Applies to the next recording; never affects Telegram's Camera2 path.
     */
    public static long recordingStartDelayMs(Context context) {
        if (isAutomatic(context)) return DEFAULT_RECORDING_START_DELAY_MS;
        int saved = prefs(context).getInt(RECORDING_START_DELAY, DEFAULT_RECORDING_START_DELAY_MS);
        return Math.max(0, Math.min(MAX_RECORDING_START_DELAY_MS, saved));
    }

    /** A conservative 30s cap for the heaviest selectable Easygram profile.
     *  This is a product safeguard, not an official Telegram size guarantee.
     */
    public static boolean isThirtySecondProfile(Context context) {
        if (isAutomatic(context)) return true;
        SharedPreferences p = prefs(context);
        String quality = p.getString(QUALITY, DEFAULT_QUALITY);
        boolean highResolution = "MAX".equals(quality)
                || "TELEGRAM_NOTE_MAX".equals(quality)
                || "QHD".equals(quality);
        int bitrate = Math.min(2_000_000, p.getInt(BITRATE, 2_000_000));
        return highResolution && bitrate >= 2_000_000;
    }

    public static int maxAllowedDurationSeconds(Context context) {
        return isThirtySecondProfile(context) ? 30 : 60;
    }

    /** Always clamp persisted values: old preferences cannot bypass new limits. */
    public static int maxRecordingDurationSeconds(Context context) {
        if (isAutomatic(context)) return 30;
        int configured = prefs(context).getInt(MAX_DURATION, DEFAULT_MAX_DURATION_SECONDS);
        return Math.max(1, Math.min(configured, maxAllowedDurationSeconds(context)));
    }

    public static void setCustomRecorder(Context c, boolean enabled) {
        prefs(c).edit().putBoolean(RECORDER, enabled).apply();
    }

    public static String summary(Context c) {
        SharedPreferences p = prefs(c);
        return !p.getBoolean(MODE, DEFAULT_MANUAL_MODE) ? "Автоматически" :
                qualityLabel(p.getString(QUALITY, DEFAULT_QUALITY)) + ", " + p.getInt(FPS, DEFAULT_FPS) + " FPS";
    }

    private static String qualityLabel(String value) {
        if ("QHD".equals(value)) return "1080 × 1080 (вместо прежних 1440)";
        for (int i = 0; i < QUALITIES.length; i++) {
            if (QUALITIES[i].equals(value)) return QUALITY_LABELS[i];
        }
        return "480 × 480";
    }

    public static RecordingMode recordingMode(Context context) {
        RoundVideoCameraSupport support = new RoundVideoCameraSupport(context);
        SharedPreferences p = prefs(context);
        sanitizePreferences(context, support);
        boolean manualMode = p.getBoolean(MODE, DEFAULT_MANUAL_MODE);
        android.util.Log.i("RoundVideoDiag", "prefs dump: mode(manual)=" + manualMode
                + " allPrefs=" + p.getAll()
                + " startDelayMs=" + recordingStartDelayMs(context)
                + " maxDurationSec=" + maxRecordingDurationSeconds(context));
        if (!manualMode) {
            RecordingConfig autoCfg = RecordingMode.Auto.INSTANCE.getPreferredConfig();
            android.util.Log.i("RoundVideoDiag", "recordingMode=AUTO preferredConfig=" + autoCfg);
            return RecordingMode.Auto.INSTANCE;
        }

        RecordingConfig d = new RecordingConfig();
        String storedQuality = p.getString(QUALITY, DEFAULT_QUALITY);
        VideoQuality quality;
        switch (storedQuality == null ? "" : storedQuality) {
            case "DEFAULT_TELEGRAM": quality = VideoQuality.TELEGRAM; break;
            case "MEDIUM": quality = VideoQuality.IPHONE_LIKE; break;
            case "MAX": quality = VideoQuality.TELEGRAM_NOTE_MAX; break;
            default: quality = safeEnum(VideoQuality.class, storedQuality, VideoQuality.TELEGRAM_NOTE_MAX);
        }
        // Telegram MessageObject.isRoundVideoDocument rejects w/h > 1280.
        // Old 1440x1440 selection must not silently send a square regular video.
        if (quality.getOutputSize().getWidth() > 1280 || quality.getOutputSize().getHeight() > 1280) {
            quality = VideoQuality.FULL_HD;
        }
        AePrioritySetting aePriority = fromPref(p, AE_PRIORITY, AePrioritySetting.class, DEFAULT_AE_PRIORITY);
        AntibandingSetting antibanding = fromPref(p, ANTIBANDING, AntibandingSetting.class, DEFAULT_ANTIBANDING);
        NoiseReductionSetting noise = fromPref(p, NOISE, NoiseReductionSetting.class, DEFAULT_NOISE);
        HotPixelSetting hotPixel = fromPref(p, HOT_PIXEL, HotPixelSetting.class, d.getHotPixelCorrection());
        ShadingSetting shading = fromPref(p, SHADING, ShadingSetting.class, d.getLensShading());
        ColorCorrectionSetting colorCorrection = fromPref(p, COLOR_CORRECTION, ColorCorrectionSetting.class, DEFAULT_COLOR_CORRECTION);
        StabilizationSetting stabilization = fromPref(p, STABILIZATION, StabilizationSetting.class, DEFAULT_STABILIZATION);
        OpticalStabilizationSetting opticalStabilization = fromPref(p, OPTICAL_STABILIZATION, OpticalStabilizationSetting.class, DEFAULT_OPTICAL_STABILIZATION);
        EdgeSetting edge = fromPref(p, EDGE, EdgeSetting.class, DEFAULT_EDGE);
        DistortionCorrectionSetting distortion = fromPref(p, DISTORTION, DistortionCorrectionSetting.class, d.getDistortionCorrection());
        AberrationCorrectionSetting aberration = fromPref(p, ABERRATION, AberrationCorrectionSetting.class, d.getAberrationCorrection());
        TonemapSetting tonemap = fromPref(p, TONEMAP, TonemapSetting.class, DEFAULT_TONEMAP);
        AwbSetting awb = fromPref(p, AWB, AwbSetting.class, DEFAULT_AWB);
        ExposureMode exposureMode = fromPref(p, EXPOSURE_MODE, ExposureMode.class, d.getExposureMode());
        FocusSetting focus = fromPref(p, FOCUS, FocusSetting.class, DEFAULT_FOCUS);

        boolean enablePrewarm = p.getBoolean(ENABLE_CONCURRENT_PREWARM, DEFAULT_ENABLE_CONCURRENT_PREWARM)
                && support.isPrewarmAvailable();
        int prewarmFps = p.getInt(CONCURRENT_PREWARM_FPS, DEFAULT_CONCURRENT_PREWARM_FPS);

        int requestedFps = p.getInt(FPS, DEFAULT_FPS);
        boolean requestedHoldFps = p.getBoolean(HOLD_FPS, DEFAULT_HOLD_FPS) && support.canHoldFps(requestedFps);
        float requestedZoom = Float.intBitsToFloat(p.getInt(ZOOM, Float.floatToIntBits(DEFAULT_ZOOM)));
        boolean faceMetering = p.getBoolean(FACE_AWARE_METERING, DEFAULT_FACE_AWARE_METERING)
                && support.isFaceMeteringAvailable();
        boolean faceExposure = p.getBoolean(FACE_AWARE_EXPOSURE, DEFAULT_FACE_AWARE_EXPOSURE)
                && faceMetering && support.isFaceExposureAvailable();

        android.util.Log.i("RoundVideoDiag", "recordingMode=CUSTOM check vs hardware:"
                + " storedQuality=" + storedQuality + " -> " + quality + " (supported=" + support.optionAvailable("quality", storedQuality != null ? storedQuality : "MAX") + ")"
                + " requestedFps=" + requestedFps + " (supported=" + support.optionAvailable("fps", Integer.toString(requestedFps)) + ")"
                + " holdFps=" + requestedHoldFps + " (supported=" + support.canHoldFps(requestedFps) + ")"
                + " requestedZoom=" + requestedZoom + " (supported=" + support.optionAvailable("zoom", String.format(java.util.Locale.US, "%.1f", requestedZoom)) + ")"
                + " opticalStab=" + opticalStabilization + " (supported=" + support.optionAvailable("optical_stabilization", opticalStabilization.name()) + ")"
                + " stab=" + stabilization + " (supported=" + support.optionAvailable("stabilization", stabilization.name()) + ")"
                + " noise=" + noise + " (supported=" + support.optionAvailable("noise", noise.name()) + ")"
                + " faceMetering=" + faceMetering + " (avail=" + support.isFaceMeteringAvailable() + ")"
                + " faceExposure=" + faceExposure + " (avail=" + support.isFaceExposureAvailable() + ")");

        RecordingConfig customConfig = new RecordingConfig(
                quality,
                Math.max(500_000, Math.min(2_000_000, p.getInt(BITRATE, 2_000_000))),
                requestedFps,
                enablePrewarm,
                prewarmFps,
                d.getIFrameIntervalSeconds(),
                d.getMaxBFrames(),
                requestedHoldFps,
                p.getBoolean(LOW_LIGHT_BOOST, DEFAULT_LOW_LIGHT_BOOST) && support.optionAvailable(LOW_LIGHT_BOOST, "true"),
                aePriority,
                antibanding,
                noise,
                hotPixel,
                shading,
                colorCorrection,
                p.getInt(COLOR_TEMPERATURE, d.getColorTemperatureKelvin()),
                d.getColorTint(),
                stabilization,
                opticalStabilization,
                edge,
                distortion,
                aberration,
                tonemap,
                awb,
                p.getBoolean(AE_LOCK, d.getAeLock()) && support.optionAvailable(AE_LOCK, "true"),
                p.getBoolean(AWB_LOCK, d.getAwbLock()) && support.optionAvailable(AWB_LOCK, "true"),
                p.getInt(EXPOSURE_STEPS, DEFAULT_EXPOSURE_STEPS),
                exposureMode,
                (long) p.getInt(MANUAL_EXPOSURE_US, (int) d.getManualExposureTimeUs()),
                p.getInt(MANUAL_ISO, d.getManualIso()),
                focus,
                Float.intBitsToFloat(p.getInt(MANUAL_FOCUS_DISTANCE, Float.floatToIntBits(d.getManualFocusDistance()))),
                requestedZoom,
                p.getBoolean(REAR_GL_ZOOM, false) && support.rowAvailable(REAR_GL_ZOOM),
                d.getAperture(),
                false, // Never bake a circular mask into the MP4: Telegram clips the preview itself.
                d.getHidePreviewUntilRecording(),
                d.getAudioSampleRate(),
                p.getInt(AUDIO_BITRATE, DEFAULT_AUDIO_BITRATE),
                faceMetering,
                faceExposure
        );
        android.util.Log.i("RoundVideoDiag", "Built RecordingConfig=" + customConfig);
        return new RecordingMode.Custom(customConfig);
    }

    private static <T extends Enum<T>> T safeEnum(Class<T> cls, String name, T fallback) {
        if (name == null) return fallback;
        try { return Enum.valueOf(cls, name); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }

    private static <T extends Enum<T>> T fromPref(SharedPreferences p, String key, Class<T> cls, T fallback) {
        return safeEnum(cls, p.getString(key, fallback.name()), fallback);
    }

    public static void showDialog(Context context) {
        SharedPreferences p = prefs(context);
        boolean manual = p.getBoolean(MODE, DEFAULT_MANUAL_MODE);
        new AlertDialog.Builder(context)
                .setTitle("Настройки записи кружков")
                .setItems(new String[]{
                        "Режим: " + (manual ? "ручные настройки" : "автоматически"),
                        "Качество видео · " + summary(context),
                        "Фронтальная камера: свет и фокус",
                        "Обработка изображения и стабилизация",
                        "Качество звука",
                        "Задержка начала записи · " + recordingStartDelayMs(context) + " мс",
                        "Сбросить параметры CustomRecorder"
                }, (dialog, item) -> {
                    switch (item) {
                        case 0:
                            choose(context, MODE, "Режим записи", new String[]{
                                    "Автоматически · камера выбирает параметры",
                                    "Вручную · использовать настройки ниже"
                            }, new String[]{"false", "true"}, -1); break;
                        case 1: showVideo(context); break;
                        case 2: showFrontCamera(context); break;
                        case 3: showProcessing(context); break;
                        case 4: showAudio(context); break;
                        case 5: showRecordingStartDelay(context); break;
                        case 6:
                            new AlertDialog.Builder(context)
                                    .setTitle("Сброс настроек")
                                    .setMessage("Сбросить только параметры CustomRecorder? Выбор рекордера сохранится.")
                                    .setPositiveButton("Сбросить", (d, which) -> {
                                        boolean recorder = p.getBoolean(RECORDER, DEFAULT_RECORDER);
                                        p.edit().clear().putBoolean(RECORDER, recorder).apply();
                                        showDialog(context);
                                    })
                                    .setNegativeButton("Отмена", (d, which) -> showDialog(context))
                                    .show();
                            break;
                        default: break;
                    }
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private static void showVideo(Context context) {
        SharedPreferences p = prefs(context);
        new AlertDialog.Builder(context).setTitle("Качество видео")
                .setItems(new String[]{
                        "Разрешение · " + qualityLabel(p.getString(QUALITY, DEFAULT_QUALITY)),
                        "Частота кадров · " + p.getInt(FPS, DEFAULT_FPS) + " FPS",
                        "Битрейт · " + (p.getInt(BITRATE, 2_000_000) / 1_000_000f) + " Мбит/с"
                }, (d, i) -> {
                    switch (i) {
                        case 0: choose(context, QUALITY, "Разрешение (не более 1280 для кружка)", QUALITY_LABELS, QUALITIES, 1); break;
                        case 1: choose(context, FPS, "Частота кадров", new String[]{"24 FPS", "30 FPS", "60 FPS"}, new String[]{"24", "30", "60"}, 1); break;
                        case 2: choose(context, BITRATE, "Битрейт видео", new String[]{"0,5 Мбит/с", "1 Мбит/с", "1,5 Мбит/с", "2 Мбит/с"}, new String[]{"500000", "1000000", "1500000", "2000000"}, 1); break;
                    }
                }).setNegativeButton("Назад", (d, w) -> showDialog(context)).show();
    }

    private static void showFrontCamera(Context context) {
        SharedPreferences p = prefs(context);
        new AlertDialog.Builder(context).setTitle("Свет и фокус камеры")
                .setItems(new String[]{
                        "Усиление при слабом освещении · " + onOff(p, LOW_LIGHT_BOOST, DEFAULT_LOW_LIGHT_BOOST),
                        "Сохранять FPS при слабом свете · " + onOff(p, HOLD_FPS, DEFAULT_HOLD_FPS),
                        "Приоритет экспозиции · " + readableEnum(p.getString(AE_PRIORITY, DEFAULT_AE_PRIORITY.name())),
                        "Поправка экспозиции · " + p.getInt(EXPOSURE_STEPS, DEFAULT_EXPOSURE_STEPS) + " шагов",
                        "Баланс белого · " + readableEnum(p.getString(AWB, DEFAULT_AWB.name())),
                        "Фокусировка · " + readableEnum(p.getString(FOCUS, DEFAULT_FOCUS.name())),
                        "Подавление мерцания · " + readableEnum(p.getString(ANTIBANDING, DEFAULT_ANTIBANDING.name())),
                        "Начальное увеличение · " + Float.intBitsToFloat(p.getInt(ZOOM, Float.floatToIntBits(DEFAULT_ZOOM))) + "×",
                        "О совместимости фронтальной камеры"
                }, (dialog, i) -> {
                    switch (i) {
                        case 0: toggle(context, LOW_LIGHT_BOOST, DEFAULT_LOW_LIGHT_BOOST, 2); break;
                        case 1: toggle(context, HOLD_FPS, DEFAULT_HOLD_FPS, 2); break;
                        case 2: enumChoose(context, AE_PRIORITY, "Приоритет экспозиции", AePrioritySetting.values(), 2); break;
                        case 3:
                            choose(
                                    context,
                                    EXPOSURE_STEPS,
                                    "Поправка экспозиции",
                                    new String[]{
                                            "−7", "−6", "−5", "−4",
                                            "−3", "−2", "−1",
                                            "0 · по умолчанию",
                                            "+1", "+2", "+3"
                                    },
                                    new String[]{
                                            "-7", "-6", "-5", "-4",
                                            "-3", "-2", "-1",
                                            "0", "1", "2", "3"
                                    },
                                    2
                            );
                            break;
                        case 4: enumChoose(context, AWB, "Баланс белого", AwbSetting.values(), 2); break;
                        case 5: enumChoose(context, FOCUS, "Фокусировка", FocusSetting.values(), 2); break;
                        case 6: enumChoose(context, ANTIBANDING, "Подавление мерцания", AntibandingSetting.values(), 2); break;
                        case 7: choose(context, ZOOM, "Начальное увеличение",
                                new String[]{
                                        "0,8× · широкий угол",
                                        "1,0× · без увеличения",
                                        "1,2×",
                                        "1,5×",
                                        "2,0×"
                                },
                                new String[]{
                                        "0.8",
                                        "1.0",
                                        "1.2",
                                        "1.5",
                                        "2.0"
                                }, 2); break;
                        case 8: new AlertDialog.Builder(context)
                                .setTitle("Фронтальная камера")
                                .setMessage("Настройки применяются к обеим камерам. Некоторые функции могут быть недоступны на фронтальной камере. Отдельных профилей и настройки зеркальности нет в публичном RecordingConfig AAR 0.0.2.")
                                .setPositiveButton("Понятно", (d2, w) -> showFrontCamera(context))
                                .show(); break;
                        default: break;
                    }
                }).setNegativeButton("Назад", (dialog, which) -> showDialog(context)).show();
    }

    private static void showProcessing(Context context) {
        SharedPreferences p = prefs(context);
        new AlertDialog.Builder(context).setTitle("Обработка изображения и зум")
                .setItems(new String[]{
                        "GL-зум для основной (1×) · " + onOff(p, REAR_GL_ZOOM, false),
                        "Шумоподавление · " + readableEnum(p.getString(NOISE, DEFAULT_NOISE.name())),
                        "Электронная стабилизация · " + readableEnum(p.getString(STABILIZATION, DEFAULT_STABILIZATION.name())),
                        "Оптическая стабилизация · " + readableEnum(p.getString(OPTICAL_STABILIZATION, DEFAULT_OPTICAL_STABILIZATION.name())),
                        "Резкость и контуры · " + readableEnum(p.getString(EDGE, DEFAULT_EDGE.name())),
                        "Коррекция цвета · " + readableEnum(p.getString(COLOR_CORRECTION, DEFAULT_COLOR_CORRECTION.name())),
                        "Тональная обработка · " + readableEnum(p.getString(TONEMAP, DEFAULT_TONEMAP.name()))
                }, (d, i) -> {
                    switch (i) {
                        case 0: toggle(context, REAR_GL_ZOOM, false, 3); break;
                        case 1: enumChoose(context, NOISE, "Шумоподавление", NoiseReductionSetting.values(), 3); break;
                        case 2: enumChoose(context, STABILIZATION, "Электронная стабилизация", StabilizationSetting.values(), 3); break;
                        case 3: enumChoose(context, OPTICAL_STABILIZATION, "Оптическая стабилизация", OpticalStabilizationSetting.values(), 3); break;
                        case 4: enumChoose(context, EDGE, "Резкость и контуры", EdgeSetting.values(), 3); break;
                        case 5: enumChoose(context, COLOR_CORRECTION, "Коррекция цвета", ColorCorrectionSetting.values(), 3); break;
                        case 6: enumChoose(context, TONEMAP, "Тональная обработка", TonemapSetting.values(), 3); break;
                    }
                }).setNegativeButton("Назад", (dialog, which) -> showDialog(context)).show();
    }

    private static void showRecordingStartDelay(Context context) {
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setText(Long.toString(recordingStartDelayMs(context)));
        input.setSelection(input.length());

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Задержка начала записи")
                .setMessage("Время после готовности камеры и первого кадра до начала записи. "
                        + "0 мс — без дополнительной задержки. Допустимо: 0–"
                        + MAX_RECORDING_START_DELAY_MS + " мс. Работает только для CustomRecorder.")
                .setView(input)
                .setPositiveButton("Сохранить", null)
                .setNegativeButton("Назад", (d, which) -> showDialog(context))
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String raw = input.getText().toString().trim();
                    int delay;
                    try {
                        delay = Integer.parseInt(raw);
                    } catch (NumberFormatException ex) {
                        input.setError("Введите целое число от 0 до " + MAX_RECORDING_START_DELAY_MS);
                        return;
                    }
                    if (delay < 0 || delay > MAX_RECORDING_START_DELAY_MS) {
                        input.setError("Допустимо: 0–" + MAX_RECORDING_START_DELAY_MS + " мс");
                        return;
                    }
                    prefs(context).edit().putInt(RECORDING_START_DELAY, delay).apply();
                    dialog.dismiss();
                    showDialog(context);
                }));
        dialog.show();
    }

    private static void showAudio(Context context) {
        SharedPreferences p = prefs(context);
        choose(context, AUDIO_BITRATE, "Битрейт звука (сейчас " + p.getInt(AUDIO_BITRATE, DEFAULT_AUDIO_BITRATE) / 1000 + " кбит/с)",
                new String[]{"64 кбит/с", "96 кбит/с", "128 кбит/с", "192 кбит/с"},
                new String[]{"64000", "96000", "128000", "192000"}, 4);
    }

    private static String onOff(SharedPreferences p, String key, boolean def) {
        return p.getBoolean(key, def) ? "вкл." : "выкл.";
    }

    private static void toggle(Context c, String key, boolean def, int section) {
        SharedPreferences p = prefs(c);
        p.edit().putBoolean(key, !p.getBoolean(key, def)).apply();
        reopen(c, section);
    }

    private static String readableEnum(String name) {
        if (name == null) return "по умолчанию";
        switch (name) {
            case "OEM_DEFAULT": return "как настроено в телефоне";
            case "OFF": return "выключено";
            case "ON": return "включено";
            case "AUTO": return "автоматически";
            case "FAST": return "быстрая обработка";
            case "HIGH_QUALITY": return "максимальное качество";
            case "MINIMAL": return "минимально";
            case "CONTINUOUS_VIDEO": return "непрерывный автофокус";
            case "MANUAL": return "вручную";
            case "VIDEO": return "для видеозаписи";
            case "PREVIEW": return "для предпросмотра";
            case "ISO": return "приоритет ISO";
            case "SHUTTER": return "приоритет выдержки";
            case "HZ_50": return "50 Гц";
            case "HZ_60": return "60 Гц";
            case "DAYLIGHT": return "дневной свет";
            case "CLOUDY_DAYLIGHT": return "облачно";
            case "INCANDESCENT": return "лампы накаливания";
            case "FLUORESCENT": return "люминесцентный свет";
            case "WARM_FLUORESCENT": return "тёплый люминесцентный свет";
            case "TWILIGHT": return "сумерки";
            case "SHADE": return "тень";
            case "CCT": return "цветовая температура";
            default: return name.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        }
    }

    private static <T extends Enum<T>> void enumChoose(Context c, String key, String title, T[] choices, int section) {
        String[] raw = new String[choices.length];
        String[] labels = new String[choices.length];
        for (int i = 0; i < choices.length; i++) {
            raw[i] = choices[i].name();
            labels[i] = readableEnum(raw[i]);
        }
        choose(c, key, title, labels, raw, section);
    }

    private static void choose(Context context, String key, String title,
                               String[] labels, String[] values, int section) {
        new AlertDialog.Builder(context).setTitle(title)
                .setItems(labels, (dialog, index) -> {
                    SharedPreferences.Editor editor = prefs(context).edit();
                    String selected = values[index];
                    if (MODE.equals(key)) editor.putBoolean(key, Boolean.parseBoolean(selected));
                    else if (FPS.equals(key) || BITRATE.equals(key) || EXPOSURE_STEPS.equals(key) || AUDIO_BITRATE.equals(key))
                        editor.putInt(key, Integer.parseInt(selected));
                    else if (ZOOM.equals(key)) editor.putInt(key, Float.floatToIntBits(Float.parseFloat(selected)));
                    else editor.putString(key, selected);
                    editor.apply();
                    reopen(context, section);
                })
                .setNegativeButton("Назад", (dialog, which) -> reopen(context, section))
                .show();
    }

    private static void reopen(Context c, int section) {
        switch (section) {
            case 1: showVideo(c); break;
            case 2: showFrontCamera(c); break;
            case 3: showProcessing(c); break;
            case 4: showDialog(c); break;
            default: showDialog(c); break;
        }
    }
}
