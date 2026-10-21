package org.telegram.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RoundVideoCameraSupport;
import org.telegram.ui.Components.RoundVideoRecorderSettings;
import org.telegram.ui.Components.RecyclerListView;

import io.github.webarmour.videomessagerecorder.camera.AePrioritySetting;
import io.github.webarmour.videomessagerecorder.camera.AntibandingSetting;
import io.github.webarmour.videomessagerecorder.camera.AwbSetting;
import io.github.webarmour.videomessagerecorder.camera.ColorCorrectionSetting;
import io.github.webarmour.videomessagerecorder.camera.EdgeSetting;
import io.github.webarmour.videomessagerecorder.camera.FocusSetting;
import io.github.webarmour.videomessagerecorder.camera.NoiseReductionSetting;
import io.github.webarmour.videomessagerecorder.camera.OpticalStabilizationSetting;
import io.github.webarmour.videomessagerecorder.camera.StabilizationSetting;
import io.github.webarmour.videomessagerecorder.camera.TonemapSetting;
import io.github.webarmour.videomessagerecorder.camera.HotPixelSetting;
import io.github.webarmour.videomessagerecorder.camera.ShadingSetting;
import io.github.webarmour.videomessagerecorder.camera.DistortionCorrectionSetting;
import io.github.webarmour.videomessagerecorder.camera.AberrationCorrectionSetting;
import io.github.webarmour.videomessagerecorder.camera.ExposureMode;

import java.util.ArrayList;
import java.util.Locale;

/** Easygram: all video-note controls in native Telegram settings cells (API 28+). */
public class RoundVideoSettingsActivity extends BaseFragment {
    private static final String PREFS = "round_video_custom_recorder";
    private static final String MODE = "mode";
    private static final String REMEMBER_LAST_CAMERA = "remember_last_camera";
    private static final String ENABLE_CONCURRENT_PREWARM = "enable_concurrent_prewarm";
    private static final String CONCURRENT_PREWARM_FPS = "concurrent_prewarm_fps";
    private static final String QUALITY = "quality";
    private static final String FPS = "fps";
    private static final String BITRATE = "bitrate";
    private static final String MAX_DURATION = "max_duration_seconds";
    private static final String LIMIT_INFO = "duration_limit_info";
    private static final String LOW_LIGHT_BOOST = "low_light_boost";
    private static final String HOLD_FPS = "hold_fps";
    private static final String AE_PRIORITY = "ae_priority";
    private static final String EXPOSURE_STEPS = "exposure_steps";
    private static final String AWB = "awb";
    private static final String FOCUS = "focus";
    private static final String ANTIBANDING = "antibanding";
    private static final String ZOOM = "zoom";
    private static final String NOISE = "noise";
    private static final String STABILIZATION = "stabilization";
    private static final String OPTICAL_STABILIZATION = "optical_stabilization";
    private static final String EDGE = "edge";
    private static final String COLOR_CORRECTION = "color_correction";
    private static final String TONEMAP = "tonemap";
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
    private static final String AUDIO_BITRATE = "audio_bitrate";
    private static final String RECORDING_START_DELAY = "recording_start_delay_ms";
    private static final String RESET = "reset";
    private static final String INFO = "info";

    private static final int HEADER = 0;
    private static final int VALUE = 1;
    private static final int CHECK = 2;
    private static final int NOTE = 3;
    private static final int ACTION = 4;

    private static final String[] QUALITIES = {
            "COMPACT", "DEFAULT_TELEGRAM", "MEDIUM", "HIGH", "MAX"
    };
    private static final String[] QUALITY_LABELS = {
            "320 × 320", "384 × 384", "400 × 400", "480 × 480", "640 × 640"
    };

    private final ArrayList<Row> rows = new ArrayList<>();
    private RecyclerListView listView;
    private ListAdapter adapter;
    private RoundVideoCameraSupport cameraSupport;

    private String getString(int resource) {
        return LocaleController.getString(resource);
    }

    private String getString(int resource, Object... args) {
        return LocaleController.formatString(resource, args);
    }

    private static final class Row {
        final int type;
        final String key;
        final String title;
        Row(int type, String key, String title) {
            this.type = type;
            this.key = key;
            this.title = title;
        }
    }

    private void addHeader(String title) { rows.add(new Row(HEADER, null, title)); }
    private void addValue(String key, String title) { rows.add(new Row(VALUE, key, title)); }
    private void addCheck(String key, String title) { rows.add(new Row(CHECK, key, title)); }

    private int descriptionFor(String key) {
        switch (key) {
            case MODE: return R.string.RVDescMode;
            case REMEMBER_LAST_CAMERA: return R.string.RVDescRememberCamera;
            case ENABLE_CONCURRENT_PREWARM: return R.string.RVDescEnableConcurrentPrewarm;
            case CONCURRENT_PREWARM_FPS: return R.string.RVDescConcurrentPrewarmFps;
            case QUALITY: return R.string.RVDescQuality;
            case FPS: return R.string.RVDescFps;
            case BITRATE: return R.string.RVDescBitrate;
            case MAX_DURATION: return R.string.RVDescDuration;
            case LOW_LIGHT_BOOST: return R.string.RVDescLowLight;
            case HOLD_FPS: return R.string.RVDescHoldFps;
            case AE_PRIORITY: return R.string.RVDescAePriority;
            case EXPOSURE_MODE: return R.string.RVDescExposureMode;
            case AE_LOCK: return R.string.RVDescAeLock;
            case AWB_LOCK: return R.string.RVDescAwbLock;
            case EXPOSURE_STEPS: return R.string.RVDescExposureSteps;
            case AWB: return R.string.RVDescAwb;
            case FOCUS: return R.string.RVDescFocus;
            case MANUAL_ISO: return R.string.RVDescIso;
            case MANUAL_EXPOSURE_US: return R.string.RVDescShutter;
            case MANUAL_FOCUS_DISTANCE: return R.string.RVDescFocusDistance;
            case ANTIBANDING: return R.string.RVDescAntibanding;
            case ZOOM: return R.string.RVDescZoom;
            case NOISE: return R.string.RVDescNoise;
            case STABILIZATION: return R.string.RVDescStabilization;
            case OPTICAL_STABILIZATION: return R.string.RVDescOpticalStabilization;
            case EDGE: return R.string.RVDescEdge;
            case COLOR_CORRECTION: return R.string.RVDescColorCorrection;
            case TONEMAP: return R.string.RVDescTonemap;
            case HOT_PIXEL: return R.string.RVDescHotPixel;
            case SHADING: return R.string.RVDescShading;
            case DISTORTION: return R.string.RVDescDistortion;
            case ABERRATION: return R.string.RVDescAberration;
            case COLOR_TEMPERATURE: return R.string.RVDescColorTemperature;
            case AUDIO_BITRATE: return R.string.RVDescAudioBitrate;
            case RECORDING_START_DELAY: return R.string.RVDescStartDelay;
            default: return 0;
        }
    }

    private void createRows() {
        rows.clear();
        addHeader(getString(R.string.RVHeaderMode));
        addValue(MODE, getString(R.string.RVMode));
        addHeader(getString(R.string.RVHeaderCamera));
        addCheck(REMEMBER_LAST_CAMERA, getString(R.string.RVRememberCamera));
        addHeader(getString(R.string.RVHeaderPrewarm));
        addCheck(ENABLE_CONCURRENT_PREWARM, getString(R.string.RVEnableConcurrentPrewarm));
        addValue(CONCURRENT_PREWARM_FPS, getString(R.string.RVConcurrentPrewarmFps));
        addHeader(getString(R.string.RVHeaderVideoQuality));
        addValue(QUALITY, getString(R.string.RVQuality));
        addValue(FPS, getString(R.string.RVFps));
        addValue(BITRATE, getString(R.string.RVBitrate));
        addHeader(getString(R.string.RVHeaderDuration));
        addValue(MAX_DURATION, getString(R.string.RVDuration));
        rows.add(new Row(NOTE, LIMIT_INFO, ""));
        addHeader(getString(R.string.RVHeaderLightFocus));
        addCheck(LOW_LIGHT_BOOST, getString(R.string.RVLowLight));
        addCheck(HOLD_FPS, getString(R.string.RVHoldFps));
        addValue(AE_PRIORITY, getString(R.string.RVAePriority));
        addValue(EXPOSURE_MODE, getString(R.string.RVExposureMode));
        addCheck(AE_LOCK, getString(R.string.RVAeLock));
        addCheck(AWB_LOCK, getString(R.string.RVAwbLock));
        addValue(EXPOSURE_STEPS, getString(R.string.RVExposureSteps));
        addValue(AWB, getString(R.string.RVAwb));
        addValue(FOCUS, getString(R.string.RVFocus));
        addValue(MANUAL_ISO, getString(R.string.RVIso));
        addValue(MANUAL_EXPOSURE_US, getString(R.string.RVShutter));
        addValue(MANUAL_FOCUS_DISTANCE, getString(R.string.RVFocusDistance));
        addValue(ANTIBANDING, getString(R.string.RVAntibanding));
        addValue(ZOOM, getString(R.string.RVZoom));
        addHeader(getString(R.string.RVHeaderProcessing));
        addValue(NOISE, getString(R.string.RVNoise));
        addValue(STABILIZATION, getString(R.string.RVStabilization));
        addValue(OPTICAL_STABILIZATION, getString(R.string.RVOpticalStabilization));
        addValue(EDGE, getString(R.string.RVEdge));
        addValue(COLOR_CORRECTION, getString(R.string.RVColorCorrection));
        addValue(TONEMAP, getString(R.string.RVTonemap));
        addValue(HOT_PIXEL, getString(R.string.RVHotPixel));
        addValue(SHADING, getString(R.string.RVShading));
        addValue(DISTORTION, getString(R.string.RVDistortion));
        addValue(ABERRATION, getString(R.string.RVAberration));
        addValue(COLOR_TEMPERATURE, getString(R.string.RVColorTemperature));
        addHeader(getString(R.string.RVHeaderAudio));
        addValue(AUDIO_BITRATE, getString(R.string.RVAudioBitrate));
        addHeader(getString(R.string.RVHeaderStart));
        addValue(RECORDING_START_DELAY, getString(R.string.RVStartDelay));
        rows.add(new Row(ACTION, RESET, getString(R.string.RVReset)));
        rows.add(new Row(NOTE, INFO, getString(R.string.RVInfo)));
    }

    private SharedPreferences prefs() {
        Context context = getParentActivity();
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public View createView(Context context) {
        cameraSupport = new RoundVideoCameraSupport(context);
        createRows();
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.EasygramVideoMessagesTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;
        listView = new RecyclerListView(context);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> onRowClicked(position));
        frameLayout.addView(listView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private boolean editable(Row row) {
        if (Build.VERSION.SDK_INT < 28) return false;
        if (row.type == ACTION || MODE.equals(row.key) || MAX_DURATION.equals(row.key)
                || REMEMBER_LAST_CAMERA.equals(row.key)) return true;
        if (ENABLE_CONCURRENT_PREWARM.equals(row.key)) {
            return cameraSupport != null && cameraSupport.isPrewarmAvailable();
        }
        if (CONCURRENT_PREWARM_FPS.equals(row.key)) {
            return cameraSupport != null && cameraSupport.isPrewarmAvailable()
                    && prefs().getBoolean(ENABLE_CONCURRENT_PREWARM, false);
        }
        if (!prefs().getBoolean(MODE, true)) return false;
        boolean manualExposure = "MANUAL".equals(prefs().getString(EXPOSURE_MODE, "AUTO"));
        if (manualExposure && (EXPOSURE_STEPS.equals(row.key) || AE_PRIORITY.equals(row.key)
                || AE_LOCK.equals(row.key) || LOW_LIGHT_BOOST.equals(row.key))) return false;
        if (LOW_LIGHT_BOOST.equals(row.key) && prefs().getInt(FPS, 60) > 30) return false;
        if (AE_PRIORITY.equals(row.key) && prefs().getBoolean(LOW_LIGHT_BOOST, false)) return false;
        String priority = prefs().getString(AE_PRIORITY, "OFF");
        if (MANUAL_ISO.equals(row.key) && !manualExposure && !"ISO".equals(priority)) return false;
        if (MANUAL_EXPOSURE_US.equals(row.key) && !manualExposure && !"SHUTTER".equals(priority)) return false;
        if (AWB.equals(row.key) && "CCT".equals(prefs().getString(COLOR_CORRECTION, "HIGH_QUALITY"))) return false;
        if (MANUAL_FOCUS_DISTANCE.equals(row.key)
                && !"MANUAL".equals(prefs().getString(FOCUS, "CONTINUOUS_VIDEO"))) return false;
        if (COLOR_TEMPERATURE.equals(row.key)
                && !"CCT".equals(prefs().getString(COLOR_CORRECTION, "HIGH_QUALITY"))) return false;
        return cameraSupport != null && cameraSupport.rowAvailable(row.key);
    }

    private void refresh() {
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void onRowClicked(int position) {
        if (position < 0 || position >= rows.size()) return;
        Row row = rows.get(position);
        if ((row.type != VALUE && row.type != CHECK && row.type != ACTION) || !editable(row)) return;
        SharedPreferences p = prefs();
        String key = row.key;
        if (row.type == CHECK) {
            boolean fallback = HOLD_FPS.equals(key);
            p.edit().putBoolean(key, !p.getBoolean(key, fallback)).apply();
            refresh();
            return;
        }
        switch (key) {
            case MODE:
                choose(key, getString(R.string.RVHeaderMode), new String[]{getString(R.string.RVAutomatic), getString(R.string.RVManualSettings)},
                        new String[]{"false", "true"}); break;
            case QUALITY:
                choose(key, row.title, QUALITY_LABELS, QUALITIES); break;
            case FPS:
                choose(key, row.title, new String[]{"24 FPS", "30 FPS", "60 FPS"},
                        new String[]{"24", "30", "60"}); break;
            case CONCURRENT_PREWARM_FPS:
                choose(key, row.title, new String[]{"15 FPS", "30 FPS", "60 FPS"},
                        new String[]{"15", "30", "60"}); break;
            case BITRATE:
                choose(key, row.title, new String[]{bitrateLabel(0.5f), bitrateLabel(1f), bitrateLabel(1.5f), bitrateLabel(2f)},
                        new String[]{"500000", "1000000", "1500000", "2000000"}); break;
            case MAX_DURATION: {
                int maximum = RoundVideoRecorderSettings.maxAllowedDurationSeconds(getParentActivity());
                if (maximum == 30) {
                    choose(key, row.title, secondsLabels(5, 10, 15, 20, 25, 30),
                            new String[]{"5", "10", "15", "20", "25", "30"});
                } else {
                    choose(key, row.title, secondsLabels(5, 10, 15, 20, 25, 30, 45, 60),
                            new String[]{"5", "10", "15", "20", "25", "30", "45", "60"});
                }
                break;
            }
            case AE_PRIORITY:
                chooseEnums(key, row.title, AePrioritySetting.values()); break;
            case EXPOSURE_MODE:
                chooseEnums(key, row.title, ExposureMode.values()); break;
            case MANUAL_ISO:
                choose(key, row.title, new String[]{"ISO 100", "ISO 200", "ISO 400", "ISO 800", "ISO 1600", "ISO 3200"},
                        new String[]{"100", "200", "400", "800", "1600", "3200"}); break;
            case MANUAL_EXPOSURE_US:
                choose(key, row.title, new String[]{fractionLabel(1000), fractionLabel(500), fractionLabel(250), fractionLabel(125), fractionLabel(100), fractionLabel(60), fractionLabel(30)},
                        new String[]{"1000", "2000", "4000", "8000", "10000", "16667", "33333"}); break;
            case EXPOSURE_STEPS:
                choose(key, row.title, new String[]{"−7", "−6", "−5", "−4", "−3", "−2", "−1", "0", "+1", "+2", "+3"},
                        new String[]{"-7", "-6", "-5", "-4", "-3", "-2", "-1", "0", "1", "2", "3"}); break;
            case AWB:
                chooseEnums(key, row.title, AwbSetting.values()); break;
            case FOCUS:
                chooseEnums(key, row.title, FocusSetting.values()); break;
            case MANUAL_FOCUS_DISTANCE:
                choose(key, row.title, new String[]{"∞ (0)", dioptersLabel(0.5f), dioptersLabel(1f), dioptersLabel(2f), dioptersLabel(5f), dioptersLabel(10f)},
                        new String[]{"0", "0.5", "1", "2", "5", "10"}); break;
            case ANTIBANDING:
                chooseEnums(key, row.title, AntibandingSetting.values()); break;
            case ZOOM:
                choose(key, row.title, new String[]{zoomLabel(0.8f), zoomLabel(1f), zoomLabel(1.2f), zoomLabel(1.5f), zoomLabel(2f)},
                        new String[]{"0.8", "1.0", "1.2", "1.5", "2.0"}); break;
            case NOISE:
                chooseEnums(key, row.title, NoiseReductionSetting.values()); break;
            case STABILIZATION:
                chooseEnums(key, row.title, StabilizationSetting.values()); break;
            case OPTICAL_STABILIZATION:
                chooseEnums(key, row.title, OpticalStabilizationSetting.values()); break;
            case EDGE:
                chooseEnums(key, row.title, EdgeSetting.values()); break;
            case COLOR_CORRECTION:
                chooseEnums(key, row.title, ColorCorrectionSetting.values()); break;
            case TONEMAP:
                chooseEnums(key, row.title, TonemapSetting.values()); break;
            case HOT_PIXEL:
                chooseEnums(key, row.title, HotPixelSetting.values()); break;
            case SHADING:
                chooseEnums(key, row.title, ShadingSetting.values()); break;
            case DISTORTION:
                chooseEnums(key, row.title, DistortionCorrectionSetting.values()); break;
            case ABERRATION:
                chooseEnums(key, row.title, AberrationCorrectionSetting.values()); break;
            case COLOR_TEMPERATURE:
                choose(key, row.title, new String[]{"2700 K", "3200 K", "4000 K", "5000 K", "5600 K", "6500 K", "7500 K"},
                        new String[]{"2700", "3200", "4000", "5000", "5600", "6500", "7500"}); break;
            case AUDIO_BITRATE:
                choose(key, row.title, new String[]{audioBitrateLabel(64), audioBitrateLabel(96), audioBitrateLabel(128), audioBitrateLabel(192)},
                        new String[]{"64000", "96000", "128000", "192000"}); break;
            case RECORDING_START_DELAY:
                showDelay(); break;
            case RESET:
                showReset(); break;
            default: break;
        }
    }

    private <T extends Enum<T>> void chooseEnums(String key, String title, T[] choices) {
        String[] values = new String[choices.length];
        String[] labels = new String[choices.length];
        for (int i = 0; i < choices.length; i++) {
            values[i] = choices[i].name();
            labels[i] = readableEnum(values[i]);
        }
        choose(key, title, labels, values);
    }

    private String[] secondsLabels(int... seconds) {
        String[] labels = new String[seconds.length];
        for (int i = 0; i < seconds.length; i++) {
            labels[i] = getString(R.string.RVSeconds, seconds[i]);
        }
        return labels;
    }

    private String bitrateLabel(float mbps) {
        return getString(R.string.RVMbps, decimal(mbps));
    }

    private String audioBitrateLabel(int kbps) {
        return getString(R.string.RVKbps, kbps);
    }

    private String fractionLabel(int denominator) {
        return getString(R.string.RVShutterFraction, denominator);
    }

    private String dioptersLabel(float value) {
        return getString(R.string.RVDiopters, decimal(value));
    }

    private String zoomLabel(float value) {
        return getString(R.string.RVZoomValue, decimal(value));
    }

    private String decimal(float value) {
        return String.format(Locale.getDefault(), "%.1f", value);
    }

    private void choose(String key, String title, String[] labels, String[] values) {
        if (labels.length != values.length) throw new IllegalArgumentException("Unequal choices");
        // Show unavailable values, but do not allow them to be persisted.
        boolean[] supported = new boolean[values.length];
        String[] display = labels.clone();
        for (int i = 0; i < values.length; i++) {
            supported[i] = cameraSupport == null || cameraSupport.optionAvailable(key, values[i]);
            if (!supported[i]) display[i] += getString(R.string.RVUnavailable);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(title);
        builder.setItems(display, (dialog, index) -> {
            if (!supported[index]) return;
            String selected = values[index];
            SharedPreferences.Editor edit = prefs().edit();
            switch (key) {
                case MODE:
                    edit.putBoolean(key, Boolean.parseBoolean(selected)); break;
                case FPS:
                case CONCURRENT_PREWARM_FPS:
                case BITRATE:
                case MAX_DURATION:
                case EXPOSURE_STEPS:
                case MANUAL_ISO:
                case MANUAL_EXPOSURE_US:
                case COLOR_TEMPERATURE:
                case AUDIO_BITRATE:
                    edit.putInt(key, Integer.parseInt(selected)); break;
                case ZOOM:
                case MANUAL_FOCUS_DISTANCE:
                    edit.putInt(key, Float.floatToIntBits(Float.parseFloat(selected))); break;
                default:
                    edit.putString(key, selected); break;
            }
            edit.apply();
            if (QUALITY.equals(key) || BITRATE.equals(key)) {
                int maximum = RoundVideoRecorderSettings.maxAllowedDurationSeconds(getParentActivity());
                SharedPreferences current = prefs();
                if (current.getInt(MAX_DURATION, 60) > maximum) {
                    current.edit().putInt(MAX_DURATION, maximum).apply();
                }
            }
            refresh();
        });
        showDialog(builder.create());
    }

    private void showDelay() {
        Context context = getParentActivity();
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(Long.toString(RoundVideoRecorderSettings.recordingStartDelayMs(context)));
        input.setSelectAllOnFocus(true);
        input.setSelection(input.length());
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(getString(R.string.RVStartDelay))
                .setMessage(getString(R.string.RVDelayDialogInfo))
                .setView(input)
                .setPositiveButton(getString(R.string.RVSave), null)
                .setNegativeButton(getString(R.string.RVCancel), null)
                .create();
        dialog.setOnShowListener(ignored -> {
            View save = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (save == null) return;
            save.setOnClickListener(v -> {
                int ms;
                try {
                    ms = Integer.parseInt(input.getText().toString().trim());
                } catch (NumberFormatException e) {
                    input.setError(getString(R.string.RVDelayInvalid));
                    return;
                }
                if (ms < 0 || ms > 2000) {
                    input.setError(getString(R.string.RVDelayRange));
                    return;
                }
                prefs().edit().putInt(RECORDING_START_DELAY, ms).apply();
                dialog.dismiss();
                refresh();
            });
        });
        showDialog(dialog);
    }

    private void showReset() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.RVResetConfirm));
        builder.setMessage(getString(R.string.RVResetInfo));
        builder.setPositiveButton(getString(R.string.RVResetAction), (dialog, which) -> {
            prefs().edit().clear().apply();
            // Re-create defaults immediately, so UI and actual recording stay in sync.
            RoundVideoRecorderSettings.recordingStartDelayMs(getParentActivity());
            refresh();
        });
        builder.setNegativeButton(getString(R.string.RVCancel), null);
        showDialog(builder.create());
    }

    private String readableEnum(String value) {
        switch (value) {
            case "OEM_DEFAULT": return getString(R.string.RVOemDefault);
            case "OFF": return getString(R.string.RVOff);
            case "ON": return getString(R.string.RVOn);
            case "AUTO": return getString(R.string.RVAutomatic);
            case "FAST": return getString(R.string.RVFast);
            case "HIGH_QUALITY": return getString(R.string.RVHighQuality);
            case "MINIMAL": return getString(R.string.RVMinimal);
            case "CONTINUOUS_VIDEO": return getString(R.string.RVContinuousFocus);
            case "MANUAL": return getString(R.string.RVManual);
            case "VIDEO": return getString(R.string.RVForVideo);
            case "PREVIEW": return getString(R.string.RVForPreview);
            case "ISO": return getString(R.string.RVIsoPriority);
            case "SHUTTER": return getString(R.string.RVShutterPriority);
            case "HZ_50": return getString(R.string.RV50Hz);
            case "HZ_60": return getString(R.string.RV60Hz);
            case "DAYLIGHT": return getString(R.string.RVDaylight);
            case "CLOUDY_DAYLIGHT": return getString(R.string.RVCloudy);
            case "INCANDESCENT": return getString(R.string.RVIncandescent);
            case "FLUORESCENT": return getString(R.string.RVFluorescent);
            case "WARM_FLUORESCENT": return getString(R.string.RVWarmFluorescent);
            case "TWILIGHT": return getString(R.string.RVTwilight);
            case "SHADE": return getString(R.string.RVShade);
            case "CCT": return getString(R.string.RVColorTemperature);
            default: return value.toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    private String valueFor(String key, SharedPreferences p) {
        switch (key) {
            case MODE: return p.getBoolean(MODE, true) ? getString(R.string.RVManualSettings) : getString(R.string.RVAutomatic);
            case QUALITY: {
                String value = p.getString(QUALITY, "MAX");
                // Display legacy selections from older Easygram versions as the new labels.
                if ("TELEGRAM".equals(value)) value = "DEFAULT_TELEGRAM";
                if ("IPHONE_LIKE".equals(value)) value = "MEDIUM";
                if ("TELEGRAM_NOTE_MAX".equals(value)) value = "MAX";
                if ("QHD".equals(value)) value = "MAX";
                for (int i = 0; i < QUALITIES.length; i++) {
                    if (QUALITIES[i].equals(value)) return QUALITY_LABELS[i];
                }
                return "640 × 640";
            }
            case FPS: return p.getInt(FPS, 60) + " FPS";
            case CONCURRENT_PREWARM_FPS: {
                int fps = 30;
                try {
                    fps = p.getInt(CONCURRENT_PREWARM_FPS, 30);
                } catch (Exception e) {
                    try {
                        fps = Integer.parseInt(p.getString(CONCURRENT_PREWARM_FPS, "30"));
                    } catch (Exception ignore) {}
                    p.edit().putInt(CONCURRENT_PREWARM_FPS, fps).apply();
                }
                return fps + " FPS";
            }
            case BITRATE: {
                int bps = Math.min(2_000_000, p.getInt(BITRATE, 2_000_000));
                return bitrateLabel(bps / 1_000_000f);
            }
            case AE_PRIORITY: return readableEnum(p.getString(AE_PRIORITY, "OFF"));
            case EXPOSURE_MODE: return readableEnum(p.getString(EXPOSURE_MODE, "AUTO"));
            case AE_LOCK: return p.getBoolean(AE_LOCK, false) ? getString(R.string.RVOn) : getString(R.string.RVOff);
            case AWB_LOCK: return p.getBoolean(AWB_LOCK, false) ? getString(R.string.RVOn) : getString(R.string.RVOff);
            case MANUAL_ISO: return "ISO " + p.getInt(MANUAL_ISO, 400);
            case MANUAL_EXPOSURE_US: return getString(R.string.RVMicroseconds, p.getInt(MANUAL_EXPOSURE_US, 10000));
            case MANUAL_FOCUS_DISTANCE: return dioptersLabel(Float.intBitsToFloat(p.getInt(MANUAL_FOCUS_DISTANCE, Float.floatToIntBits(0f))));
            case EXPOSURE_STEPS: return getString(R.string.RVSteps, p.getInt(EXPOSURE_STEPS, -4));
            case AWB: return readableEnum(p.getString(AWB, "AUTO"));
            case FOCUS: return readableEnum(p.getString(FOCUS, "CONTINUOUS_VIDEO"));
            case ANTIBANDING: return readableEnum(p.getString(ANTIBANDING, "AUTO"));
            case ZOOM: return zoomLabel(Float.intBitsToFloat(p.getInt(ZOOM, Float.floatToIntBits(0.8f))));
            case NOISE: return readableEnum(p.getString(NOISE, "OFF"));
            case STABILIZATION: return readableEnum(p.getString(STABILIZATION, "OEM_DEFAULT"));
            case OPTICAL_STABILIZATION: return readableEnum(p.getString(OPTICAL_STABILIZATION, "ON"));
            case EDGE: return readableEnum(p.getString(EDGE, "HIGH_QUALITY"));
            case COLOR_CORRECTION: return readableEnum(p.getString(COLOR_CORRECTION, "HIGH_QUALITY"));
            case TONEMAP: return readableEnum(p.getString(TONEMAP, "HIGH_QUALITY"));
            case HOT_PIXEL: return readableEnum(p.getString(HOT_PIXEL, "FAST"));
            case SHADING: return readableEnum(p.getString(SHADING, "FAST"));
            case DISTORTION: return readableEnum(p.getString(DISTORTION, "FAST"));
            case ABERRATION: return readableEnum(p.getString(ABERRATION, "FAST"));
            case COLOR_TEMPERATURE: return p.getInt(COLOR_TEMPERATURE, 5000) + " K";
            case AUDIO_BITRATE: return audioBitrateLabel(p.getInt(AUDIO_BITRATE, 128_000) / 1000);
            case RECORDING_START_DELAY: return getString(R.string.RVMilliseconds, RoundVideoRecorderSettings.recordingStartDelayMs(getParentActivity()));
            case MAX_DURATION: return getString(R.string.RVSeconds, RoundVideoRecorderSettings.maxRecordingDurationSeconds(getParentActivity()));
            default: return "";
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;
        ListAdapter(Context context) { this.context = context; }
        @Override public int getItemCount() { return rows.size(); }
        @Override public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            if (position < 0 || position >= rows.size()) return false;
            Row row = rows.get(position);
            return (row.type == VALUE || row.type == CHECK || row.type == ACTION) && editable(row);
        }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View view;
            if (type == HEADER) {
                view = new HeaderCell(context);
            } else if (type == NOTE) {
                view = new TextInfoPrivacyCell(context);
            } else {
                View cell;
                if (type == CHECK) {
                    cell = new TextCheckCell(context);
                } else {
                    TextSettingsCell settingsCell = new TextSettingsCell(context);
                    settingsCell.setCanDisable(true);
                    cell = settingsCell;
                }
                if (type == ACTION) {
                    view = cell;
                } else {
                    LinearLayout container = new LinearLayout(context);
                    container.setOrientation(LinearLayout.VERTICAL);
                    container.addView(cell, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    TextView description = new TextView(context);
                    description.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                    description.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
                    description.setMaxLines(2);
                    description.setEllipsize(TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    params.setMargins(AndroidUtilities.dp(21), 0, AndroidUtilities.dp(21), AndroidUtilities.dp(10));
                    container.addView(description, params);
                    view = container;
                }
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            if (row.type == HEADER) {
                ((HeaderCell) holder.itemView).setText(row.title);
            } else if (row.type == NOTE) {
                if (LIMIT_INFO.equals(row.key)) {
                    boolean capped = RoundVideoRecorderSettings.isThirtySecondProfile(getParentActivity());
                    String text = capped
                            ? getString(R.string.RVDurationCapInfo)
                            : getString(R.string.RVDurationDefaultInfo);
                    text += getString(R.string.RVDurationSizeInfo);
                    ((TextInfoPrivacyCell) holder.itemView).setText(text);
                } else {
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.title);
                }
            } else if (row.type == CHECK) {
                LinearLayout container = (LinearLayout) holder.itemView;
                TextCheckCell cell = (TextCheckCell) container.getChildAt(0);
                cell.setEnabled(editable(row));
                cell.setTextAndCheck(row.title, prefs().getBoolean(row.key,
                        HOLD_FPS.equals(row.key)), true);
                TextView description = (TextView) container.getChildAt(1);
                description.setText(getString(descriptionFor(row.key)));
                description.setAlpha(editable(row) ? 1f : 0.5f);
            } else if (row.type == ACTION) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setEnabled(editable(row));
                cell.setText(row.title, false);
            } else {
                LinearLayout container = (LinearLayout) holder.itemView;
                TextSettingsCell cell = (TextSettingsCell) container.getChildAt(0);
                cell.setEnabled(editable(row));
                cell.setTextAndValue(row.title, valueFor(row.key, prefs()), true);
                TextView description = (TextView) container.getChildAt(1);
                description.setText(getString(descriptionFor(row.key)));
                description.setAlpha(editable(row) ? 1f : 0.5f);
            }
        }
        @Override public int getItemViewType(int position) { return rows.get(position).type; }
    }
}
