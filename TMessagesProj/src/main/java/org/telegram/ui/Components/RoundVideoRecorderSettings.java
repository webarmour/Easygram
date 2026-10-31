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
    private static final int DEFAULT_MAX_DURATION_SECONDS = 60;
    private static final int DEFAULT_RECORDING_START_DELAY_MS = 200;
    private static final int MAX_RECORDING_START_DELAY_MS = 2000;
    public static final boolean DEFAULT_ENABLE_CONCURRENT_PREWARM = false;
    public static final int DEFAULT_CONCURRENT_PREWARM_FPS = 30;

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
                    if (!p.contains(QUALITY)) e.putString(QUALITY, "TELEGRAM_NOTE_MAX");
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
                    e.putBoolean("easygram_native_defaults_initialized_v1", true).apply();
                }
            }
        }
        return p;
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
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(MODE, DEFAULT_MANUAL_MODE)) return RecordingMode.Auto.INSTANCE;

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

        boolean enablePrewarm = p.getBoolean(ENABLE_CONCURRENT_PREWARM, DEFAULT_ENABLE_CONCURRENT_PREWARM);
        int prewarmFps = DEFAULT_CONCURRENT_PREWARM_FPS;
        try {
            prewarmFps = p.getInt(CONCURRENT_PREWARM_FPS, DEFAULT_CONCURRENT_PREWARM_FPS);
        } catch (Exception e) {
            try {
                prewarmFps = Integer.parseInt(p.getString(CONCURRENT_PREWARM_FPS, "30"));
            } catch (Exception ignore) {}
            p.edit().putInt(CONCURRENT_PREWARM_FPS, prewarmFps).apply();
        }

        return new RecordingMode.Custom(new RecordingConfig(
                quality,
                Math.max(500_000, Math.min(2_000_000, p.getInt(BITRATE, 2_000_000))),
                p.getInt(FPS, DEFAULT_FPS),
                enablePrewarm,
                prewarmFps,
                d.getIFrameIntervalSeconds(),
                d.getMaxBFrames(),
                p.getBoolean(HOLD_FPS, DEFAULT_HOLD_FPS),
                p.getBoolean(LOW_LIGHT_BOOST, DEFAULT_LOW_LIGHT_BOOST),
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
                p.getBoolean(AE_LOCK, d.getAeLock()),
                p.getBoolean(AWB_LOCK, d.getAwbLock()),
                p.getInt(EXPOSURE_STEPS, DEFAULT_EXPOSURE_STEPS),
                exposureMode,
                (long) p.getInt(MANUAL_EXPOSURE_US, (int) d.getManualExposureTimeUs()),
                p.getInt(MANUAL_ISO, d.getManualIso()),
                focus,
                Float.intBitsToFloat(p.getInt(MANUAL_FOCUS_DISTANCE, Float.floatToIntBits(d.getManualFocusDistance()))),
                Float.intBitsToFloat(p.getInt(ZOOM, Float.floatToIntBits(DEFAULT_ZOOM))),
                d.getAperture(),
                false, // Never bake a circular mask into the MP4: Telegram clips the preview itself.
                d.getHidePreviewUntilRecording(),
                d.getAudioSampleRate(),
                p.getInt(AUDIO_BITRATE, DEFAULT_AUDIO_BITRATE)
        ));
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
        new AlertDialog.Builder(context).setTitle("Обработка изображения")
                .setItems(new String[]{
                        "Шумоподавление · " + readableEnum(p.getString(NOISE, DEFAULT_NOISE.name())),
                        "Электронная стабилизация · " + readableEnum(p.getString(STABILIZATION, DEFAULT_STABILIZATION.name())),
                        "Оптическая стабилизация · " + readableEnum(p.getString(OPTICAL_STABILIZATION, DEFAULT_OPTICAL_STABILIZATION.name())),
                        "Резкость и контуры · " + readableEnum(p.getString(EDGE, DEFAULT_EDGE.name())),
                        "Коррекция цвета · " + readableEnum(p.getString(COLOR_CORRECTION, DEFAULT_COLOR_CORRECTION.name())),
                        "Тональная обработка · " + readableEnum(p.getString(TONEMAP, DEFAULT_TONEMAP.name()))
                }, (d, i) -> {
                    switch (i) {
                        case 0: enumChoose(context, NOISE, "Шумоподавление", NoiseReductionSetting.values(), 3); break;
                        case 1: enumChoose(context, STABILIZATION, "Электронная стабилизация", StabilizationSetting.values(), 3); break;
                        case 2: enumChoose(context, OPTICAL_STABILIZATION, "Оптическая стабилизация (на фронталке может отсутствовать)", OpticalStabilizationSetting.values(), 3); break;
                        case 3: enumChoose(context, EDGE, "Резкость", EdgeSetting.values(), 3); break;
                        case 4: enumChoose(context, COLOR_CORRECTION, "Коррекция цвета", ColorCorrectionSetting.values(), 3); break;
                        case 5: enumChoose(context, TONEMAP, "Тональная обработка", TonemapSetting.values(), 3); break;
                    }
                }).setNegativeButton("Назад", (d, w) -> showDialog(context)).show();
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
