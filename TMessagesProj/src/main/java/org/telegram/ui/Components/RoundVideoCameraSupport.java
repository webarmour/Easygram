package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Range;
import android.util.SizeF;
import android.util.Size;

import java.util.ArrayList;
import java.util.List;

/** Camera2 capability intersection: settings are shared by front and rear cameras. */
public final class RoundVideoCameraSupport {
    private final List<CameraCharacteristics> cameras = new ArrayList<>(2);
    private final boolean prewarmAvailable;

    public RoundVideoCameraSupport(Context context) {
        boolean canPrewarm = false;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                if (manager != null) {
                    java.util.Set<java.util.Set<String>> concurrent = manager.getConcurrentCameraIds();
                    if (concurrent != null && !concurrent.isEmpty()) {
                        canPrewarm = true;
                    }
                }
            } catch (Throwable ignore) {}
        }
        this.prewarmAvailable = canPrewarm;
        if (Build.VERSION.SDK_INT < 28) return;
        CameraManager manager = context.getSystemService(CameraManager.class);
        if (manager == null) return;
        try {
            CameraCharacteristics front = null;
            CameraCharacteristics rear = null;
            float rearScore = Float.POSITIVE_INFINITY;
            boolean logicalRearSelected = false;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraMetadata.LENS_FACING_FRONT && front == null) front = c;
                if (facing != null && facing == CameraMetadata.LENS_FACING_BACK) {
                    boolean logical = has(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA);
                    float score = focalLengthScore(c);
                    if ((!logicalRearSelected && logical) ||
                            (!logicalRearSelected && rear == null) ||
                            (!logicalRearSelected && score < rearScore)) {
                        rear = c;
                        rearScore = score;
                        logicalRearSelected = logical;
                    }
                }
            }
            if (front != null) cameras.add(front);
            if (rear != null) cameras.add(rear);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException error) {
            cameras.clear();
        }
    }

    private static float focalLengthScore(CameraCharacteristics c) {
        SizeF sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (sensor == null || focal == null || focal.length == 0) return Float.POSITIVE_INFINITY;
        float side = Math.max(sensor.getWidth(), sensor.getHeight());
        if (side <= 0) return Float.POSITIVE_INFINITY;
        float min = focal[0];
        for (float f : focal) min = Math.min(min, f);
        return Math.abs(min * 36f / side - 26f);
    }

    public boolean isPrewarmAvailable() {
        return prewarmAvailable;
    }

    public boolean rowAvailable(String key) {
        if ("enable_concurrent_prewarm".equals(key) || "concurrent_prewarm_fps".equals(key)) {
            return isPrewarmAvailable();
        }
        if (cameras.isEmpty()) return false;
        switch (key) {
            case "quality": return countSupported(key,
                    new String[]{"COMPACT", "DEFAULT_TELEGRAM", "MEDIUM", "HIGH", "MAX"}) > 1;
            case "fps": return countSupported(key, new String[]{"24", "30", "60"}) > 1;
            case "low_light_boost": return optionAvailable(key, "true");
            case "hold_fps": return optionAvailable(key, "true");
            case "ae_lock": case "awb_lock": return optionAvailable(key, "true");
            case "exposure_steps": return optionAvailable(key, "-1") || optionAvailable(key, "1");
            case "zoom": return optionAvailable(key, "1.2") || optionAvailable(key, "0.8");
            case "manual_iso":
                for (String iso : new String[]{"100", "200", "400", "800", "1600", "3200"})
                    if (optionAvailable(key, iso)) return true;
                return false;
            case "manual_exposure_us":
                for (String us : new String[]{"1000", "2000", "4000", "8000", "10000", "16667", "33333"})
                    if (optionAvailable(key, us)) return true;
                return false;
            case "manual_focus_distance": return optionAvailable(key, "0.5");
            case "color_temperature_kelvin": return optionAvailable(key, "5000");
            case "ae_priority": return optionAvailable(key, "ISO") || optionAvailable(key, "SHUTTER");
            case "exposure_mode": return optionAvailable(key, "MANUAL");
            default:
                String[] values = allModes(key);
                if (values == null) return true; // Software-only settings.
                int count = 0;
                for (String value : values) {
                    if (optionAvailable(key, value) && ++count >= 2) return true;
                }
                return false;
        }
    }

    private int countSupported(String key, String[] values) {
        int count = 0;
        for (String value : values) if (optionAvailable(key, value)) count++;
        return count;
    }

    /** Unknown/undisclosed hardware capabilities are treated as unsupported. */
    public boolean optionAvailable(String key, String value) {
        if (cameras.isEmpty()) return false;
        for (CameraCharacteristics c : cameras) {
            if (!onCamera(c, key, value)) return false;
        }
        return true;
    }

    private static boolean onCamera(CameraCharacteristics c, String key, String value) {
        switch (key) {
            case "quality": {
                int size;
                switch (value) {
                    case "COMPACT": size = 320; break;
                    case "DEFAULT_TELEGRAM": case "TELEGRAM": size = 384; break;
                    case "MEDIUM": case "IPHONE_LIKE": size = 400; break;
                    case "HIGH": size = 480; break;
                    case "MAX": case "TELEGRAM_NOTE_MAX": size = 640; break;
                    default: return false;
                }
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) return false;
                Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
                if (sizes == null) return false;
                for (Size supported : sizes)
                    if (Math.min(supported.getWidth(), supported.getHeight()) >= size) return true;
                return false;
            }
            case "fps": {
                int requested = Integer.parseInt(value);
                Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                if (ranges == null) return false;
                for (Range<Integer> supported : ranges) if (supported.contains(requested)) return true;
                return false;
            }
            case "low_light_boost":
                return Build.VERSION.SDK_INT >= 35 && has(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES),
                        CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY);
            case "hold_fps": {
                Range<Integer>[] fps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                if (fps == null) return false;
                for (Range<Integer> range : fps) if (range.getLower() >= 24) return true;
                return false;
            }
            case "ae_lock": return Boolean.TRUE.equals(c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE));
            case "awb_lock": return Boolean.TRUE.equals(c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE));
            case "exposure_steps": {
                Range<Integer> range = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                return range != null && range.contains(Integer.parseInt(value));
            }
            case "zoom": {
                float zoom = Float.parseFloat(value);
                if (Build.VERSION.SDK_INT >= 30) {
                    Range<Float> range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                    return range != null && range.contains(zoom);
                }
                Float max = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                return zoom >= 1f && max != null && zoom <= max;
            }
            case "exposure_mode":
                return "AUTO".equals(value) || manualSensor(c);
            case "manual_iso": {
                Range<Integer> range = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                return manualSensor(c) && range != null && range.contains(Integer.parseInt(value));
            }
            case "manual_exposure_us": {
                Range<Long> range = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                return manualSensor(c) && range != null && range.contains(Long.parseLong(value) * 1000L);
            }
            case "manual_focus_distance": {
                Float max = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                return max != null && max > 0 && Float.parseFloat(value) >= 0 && Float.parseFloat(value) <= max;
            }
            case "color_temperature_kelvin": {
                if (Build.VERSION.SDK_INT < 36) return false;
                Range<Integer> range = c.get(CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE);
                return range != null && range.contains(Integer.parseInt(value));
            }
            case "ae_priority":
                return "OFF".equals(value) || Build.VERSION.SDK_INT >= 36 &&
                        has(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES),
                                "ISO".equals(value) ? 1 : 2);
            case "noise": return nullableMode(value, c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
                    new String[]{"OFF", "MINIMAL", "FAST", "HIGH_QUALITY"}, new int[]{0, 3, 1, 2});
            case "hot_pixel": return nullableMode(value, c.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES),
                    new String[]{"OFF", "FAST", "HIGH_QUALITY"}, new int[]{0, 1, 2});
            case "shading": return nullableMode(value, c.get(CameraCharacteristics.SHADING_AVAILABLE_MODES),
                    new String[]{"OFF", "FAST", "HIGH_QUALITY"}, new int[]{0, 1, 2});
            case "stabilization":
                if ("PREVIEW".equals(value) && Build.VERSION.SDK_INT < 33) return false;
                return nullableMode(value, c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
                        new String[]{"OFF", "VIDEO", "PREVIEW"}, new int[]{0, 1, 2});
            case "optical_stabilization": return nullableMode(value, c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
                    new String[]{"OFF", "ON"}, new int[]{0, 1});
            case "edge": return nullableMode(value, c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
                    new String[]{"OFF", "FAST", "HIGH_QUALITY"}, new int[]{0, 1, 2});
            case "distortion": return nullableMode(value, c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES),
                    new String[]{"OFF", "FAST", "HIGH_QUALITY"}, new int[]{0, 1, 2});
            case "aberration": return nullableMode(value, c.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES),
                    new String[]{"OFF", "FAST", "HIGH_QUALITY"}, new int[]{0, 1, 2});
            case "tonemap": return nullableMode(value, c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES),
                    new String[]{"FAST", "HIGH_QUALITY"}, new int[]{1, 2});
            case "color_correction":
                if ("CCT".equals(value) && Build.VERSION.SDK_INT < 36) return false;
                // The available-modes key is only published from API 36. Earlier versions
                // expose no reliable per-mode capabilities: do not disable FAST/HIGH_QUALITY.
                if (Build.VERSION.SDK_INT < 36) return "OEM_DEFAULT".equals(value);
                if ("CCT".equals(value) && !has(c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),
                        CameraMetadata.CONTROL_AWB_MODE_OFF)) return false;
                return nullableMode(value, c.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES),
                        new String[]{"FAST", "HIGH_QUALITY", "CCT"}, new int[]{1, 2, 3});
            case "awb": return nullableMode(value, c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),
                    new String[]{"AUTO", "INCANDESCENT", "FLUORESCENT", "WARM_FLUORESCENT", "DAYLIGHT", "CLOUDY_DAYLIGHT", "TWILIGHT", "SHADE"},
                    new int[]{1, 2, 3, 4, 5, 6, 7, 8});
            case "antibanding": return nullableMode(value, c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES),
                    new String[]{"OFF", "HZ_50", "HZ_60", "AUTO"}, new int[]{0, 1, 2, 3});
            case "focus": {
                int mode = "CONTINUOUS_VIDEO".equals(value) ? 3 : "AUTO".equals(value) ? 1 : 0;
                if ("MANUAL".equals(value)) {
                    Float max = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                    if (max == null || max <= 0) return false;
                }
                return has(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES), mode);
            }
            default: return true;
        }
    }

    private static boolean manualSensor(CameraCharacteristics c) {
        int[] capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        return has(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
    }

    private static boolean nullableMode(String value, int[] supported, String[] names, int[] codes) {
        if ("OEM_DEFAULT".equals(value)) return supported != null && supported.length > 0;
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(value)) return has(supported, codes[i]);
        }
        return false;
    }

    private static boolean has(int[] values, int mode) {
        if (values == null) return false;
        for (int value : values) if (value == mode) return true;
        return false;
    }

    private static String[] allModes(String key) {
        switch (key) {
            case "noise": return new String[]{"OEM_DEFAULT", "OFF", "MINIMAL", "FAST", "HIGH_QUALITY"};
            case "hot_pixel": case "shading": case "distortion": case "aberration": case "edge":
                return new String[]{"OEM_DEFAULT", "OFF", "FAST", "HIGH_QUALITY"};
            case "color_correction": return new String[]{"OEM_DEFAULT", "FAST", "HIGH_QUALITY", "CCT"};
            case "stabilization": return new String[]{"OEM_DEFAULT", "OFF", "VIDEO", "PREVIEW"};
            case "optical_stabilization": return new String[]{"OEM_DEFAULT", "OFF", "ON"};
            case "tonemap": return new String[]{"OEM_DEFAULT", "FAST", "HIGH_QUALITY"};
            case "awb": return new String[]{"AUTO", "INCANDESCENT", "FLUORESCENT", "WARM_FLUORESCENT", "DAYLIGHT", "CLOUDY_DAYLIGHT", "TWILIGHT", "SHADE"};
            case "antibanding": return new String[]{"AUTO", "OFF", "HZ_50", "HZ_60"};
            case "focus": return new String[]{"CONTINUOUS_VIDEO", "AUTO", "MANUAL"};
            default: return null;
        }
    }
}
