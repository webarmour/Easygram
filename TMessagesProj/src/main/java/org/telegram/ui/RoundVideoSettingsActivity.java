package org.telegram.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
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

    private void createRows() {
        rows.clear();
        addHeader("Режим записи");
        addValue(MODE, "Режим");
        addHeader("Качество видео");
        addValue(QUALITY, "Разрешение выхода");
        addValue(FPS, "Частота кадров");
        addValue(BITRATE, "Битрейт видео");
        addHeader("Длительность видеосообщения");
        addValue(MAX_DURATION, "Максимальная длина");
        rows.add(new Row(NOTE, LIMIT_INFO, ""));
        addHeader("Свет и фокус камеры");
        addCheck(LOW_LIGHT_BOOST, "Усиление при слабом освещении");
        addCheck(HOLD_FPS, "Сохранять FPS при слабом свете");
        addValue(AE_PRIORITY, "Приоритет экспозиции");
        addValue(EXPOSURE_MODE, "Режим экспозиции");
        addCheck(AE_LOCK, "Блокировка автоэкспозиции");
        addCheck(AWB_LOCK, "Блокировка автобаланса белого");
        addValue(EXPOSURE_STEPS, "Поправка экспозиции");
        addValue(AWB, "Баланс белого");
        addValue(FOCUS, "Фокусировка");
        addValue(MANUAL_ISO, "ISO (вручную или по приоритету)");
        addValue(MANUAL_EXPOSURE_US, "Выдержка (вручную или по приоритету)");
        addValue(MANUAL_FOCUS_DISTANCE, "Дистанция фокусировки (диоптрии)");
        addValue(ANTIBANDING, "Подавление мерцания");
        addValue(ZOOM, "Начальное увеличение");
        addHeader("Обработка изображения и стабилизация");
        addValue(NOISE, "Шумоподавление");
        addValue(STABILIZATION, "Электронная стабилизация");
        addValue(OPTICAL_STABILIZATION, "Оптическая стабилизация");
        addValue(EDGE, "Резкость и контуры");
        addValue(COLOR_CORRECTION, "Коррекция цвета");
        addValue(TONEMAP, "Тональная обработка");
        addValue(HOT_PIXEL, "Коррекция горячих пикселей");
        addValue(SHADING, "Коррекция виньетирования");
        addValue(DISTORTION, "Исправление геометрии объектива");
        addValue(ABERRATION, "Коррекция цветных ореолов");
        addValue(COLOR_TEMPERATURE, "Температура цвета (K)");
        addHeader("Качество звука");
        addValue(AUDIO_BITRATE, "Битрейт звука");
        addHeader("Запуск записи");
        addValue(RECORDING_START_DELAY, "Задержка начала записи");
        rows.add(new Row(ACTION, RESET, "Сбросить настройки записи"));
        rows.add(new Row(NOTE, INFO,
                "Запись видеосообщений использует CustomRecorder (Android 9+). "
                        + "Настройки применяются к обеим камерам. Некоторые режимы "
                        + "доступны только если поддерживаются фронтальной и основной камерами. "
                        + "Неизвестные возможности считаются недоступными."));
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
        actionBar.setTitle("Видеосообщения");
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
        if (row.type == ACTION || MODE.equals(row.key) || MAX_DURATION.equals(row.key)) return true;
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
                choose(key, "Режим записи", new String[]{"Автоматически", "Ручные настройки"},
                        new String[]{"false", "true"}); break;
            case QUALITY:
                choose(key, "Разрешение выхода", QUALITY_LABELS, QUALITIES); break;
            case FPS:
                choose(key, "Частота кадров", new String[]{"24 FPS", "30 FPS", "60 FPS"},
                        new String[]{"24", "30", "60"}); break;
            case BITRATE:
                choose(key, "Битрейт видео", new String[]{"0,5 Мбит/с", "1 Мбит/с", "1,5 Мбит/с", "2 Мбит/с"},
                        new String[]{"500000", "1000000", "1500000", "2000000"}); break;
            case MAX_DURATION: {
                int maximum = RoundVideoRecorderSettings.maxAllowedDurationSeconds(getParentActivity());
                if (maximum == 30) {
                    choose(key, "Максимальная длина", new String[]{"5 секунд", "10 секунд", "15 секунд", "20 секунд", "25 секунд", "30 секунд"},
                            new String[]{"5", "10", "15", "20", "25", "30"});
                } else {
                    choose(key, "Максимальная длина", new String[]{"5 секунд", "10 секунд", "15 секунд", "20 секунд", "25 секунд", "30 секунд", "45 секунд", "60 секунд"},
                            new String[]{"5", "10", "15", "20", "25", "30", "45", "60"});
                }
                break;
            }
            case AE_PRIORITY:
                chooseEnums(key, "Приоритет экспозиции", AePrioritySetting.values()); break;
            case EXPOSURE_MODE:
                chooseEnums(key, "Режим экспозиции", ExposureMode.values()); break;
            case MANUAL_ISO:
                choose(key, "Чувствительность ISO", new String[]{"ISO 100", "ISO 200", "ISO 400", "ISO 800", "ISO 1600", "ISO 3200"},
                        new String[]{"100", "200", "400", "800", "1600", "3200"}); break;
            case MANUAL_EXPOSURE_US:
                choose(key, "Выдержка", new String[]{"1/1000 с", "1/500 с", "1/250 с", "1/125 с", "1/100 с", "1/60 с", "1/30 с"},
                        new String[]{"1000", "2000", "4000", "8000", "10000", "16667", "33333"}); break;
            case EXPOSURE_STEPS:
                choose(key, "Поправка экспозиции", new String[]{"−7", "−6", "−5", "−4", "−3", "−2", "−1", "0", "+1", "+2", "+3"},
                        new String[]{"-7", "-6", "-5", "-4", "-3", "-2", "-1", "0", "1", "2", "3"}); break;
            case AWB:
                chooseEnums(key, "Баланс белого", AwbSetting.values()); break;
            case FOCUS:
                chooseEnums(key, "Фокусировка", FocusSetting.values()); break;
            case MANUAL_FOCUS_DISTANCE:
                choose(key, "Фокус (0 = бесконечность)", new String[]{"∞ (0)", "0,5 дптр", "1 дптр", "2 дптр", "5 дптр", "10 дптр"},
                        new String[]{"0", "0.5", "1", "2", "5", "10"}); break;
            case ANTIBANDING:
                chooseEnums(key, "Подавление мерцания", AntibandingSetting.values()); break;
            case ZOOM:
                choose(key, "Начальное увеличение", new String[]{"0,8×", "1,0×", "1,2×", "1,5×", "2,0×"},
                        new String[]{"0.8", "1.0", "1.2", "1.5", "2.0"}); break;
            case NOISE:
                chooseEnums(key, "Шумоподавление", NoiseReductionSetting.values()); break;
            case STABILIZATION:
                chooseEnums(key, "Электронная стабилизация", StabilizationSetting.values()); break;
            case OPTICAL_STABILIZATION:
                chooseEnums(key, "Оптическая стабилизация", OpticalStabilizationSetting.values()); break;
            case EDGE:
                chooseEnums(key, "Резкость и контуры", EdgeSetting.values()); break;
            case COLOR_CORRECTION:
                chooseEnums(key, "Коррекция цвета", ColorCorrectionSetting.values()); break;
            case TONEMAP:
                chooseEnums(key, "Тональная обработка", TonemapSetting.values()); break;
            case HOT_PIXEL:
                chooseEnums(key, "Коррекция горячих пикселей", HotPixelSetting.values()); break;
            case SHADING:
                chooseEnums(key, "Коррекция виньетирования", ShadingSetting.values()); break;
            case DISTORTION:
                chooseEnums(key, "Геометрические искажения", DistortionCorrectionSetting.values()); break;
            case ABERRATION:
                chooseEnums(key, "Хроматические аберрации", AberrationCorrectionSetting.values()); break;
            case COLOR_TEMPERATURE:
                choose(key, "Температура цвета", new String[]{"2700 K", "3200 K", "4000 K", "5000 K", "5600 K", "6500 K", "7500 K"},
                        new String[]{"2700", "3200", "4000", "5000", "5600", "6500", "7500"}); break;
            case AUDIO_BITRATE:
                choose(key, "Битрейт звука", new String[]{"64 кбит/с", "96 кбит/с", "128 кбит/с", "192 кбит/с"},
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

    private void choose(String key, String title, String[] labels, String[] values) {
        if (labels.length != values.length) throw new IllegalArgumentException("Unequal choices");
        // Show unavailable values, but do not allow them to be persisted.
        boolean[] supported = new boolean[values.length];
        String[] display = labels.clone();
        for (int i = 0; i < values.length; i++) {
            supported[i] = cameraSupport == null || cameraSupport.optionAvailable(key, values[i]);
            if (!supported[i]) display[i] += " · недоступно";
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
                .setTitle("Задержка начала записи")
                .setMessage("0–2000 мс после готовности камеры и первого кадра.")
                .setView(input)
                .setPositiveButton("Сохранить", null)
                .setNegativeButton("Отмена", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            View save = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (save == null) return;
            save.setOnClickListener(v -> {
                int ms;
                try {
                    ms = Integer.parseInt(input.getText().toString().trim());
                } catch (NumberFormatException e) {
                    input.setError("Введите целое число от 0 до 2000");
                    return;
                }
                if (ms < 0 || ms > 2000) {
                    input.setError("Допустимо от 0 до 2000 мс");
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
        builder.setTitle("Сбросить настройки записи?");
        builder.setMessage("Все параметры CustomRecorder вернутся к значениям Easygram по умолчанию.");
        builder.setPositiveButton("Сбросить", (dialog, which) -> {
            prefs().edit().clear().apply();
            // Re-create defaults immediately, so UI and actual recording stay in sync.
            RoundVideoRecorderSettings.recordingStartDelayMs(getParentActivity());
            refresh();
        });
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }

    private static String readableEnum(String value) {
        switch (value) {
            case "OEM_DEFAULT": return "Как настроено в телефоне";
            case "OFF": return "Выключено";
            case "ON": return "Включено";
            case "AUTO": return "Автоматически";
            case "FAST": return "Быстрая обработка";
            case "HIGH_QUALITY": return "Максимальное качество";
            case "MINIMAL": return "Минимально";
            case "CONTINUOUS_VIDEO": return "Непрерывный автофокус";
            case "MANUAL": return "Вручную";
            case "VIDEO": return "Для видеозаписи";
            case "PREVIEW": return "Для предпросмотра";
            case "ISO": return "Приоритет ISO";
            case "SHUTTER": return "Приоритет выдержки";
            case "HZ_50": return "50 Гц";
            case "HZ_60": return "60 Гц";
            case "DAYLIGHT": return "Дневной свет";
            case "CLOUDY_DAYLIGHT": return "Облачно";
            case "INCANDESCENT": return "Лампы накаливания";
            case "FLUORESCENT": return "Люминесцентный свет";
            case "WARM_FLUORESCENT": return "Тёплый люминесцентный свет";
            case "TWILIGHT": return "Сумерки";
            case "SHADE": return "Тень";
            case "CCT": return "Цветовая температура";
            default: return value.toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    private String valueFor(String key, SharedPreferences p) {
        switch (key) {
            case MODE: return p.getBoolean(MODE, true) ? "Ручные настройки" : "Автоматически";
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
            case BITRATE: {
                int bps = Math.min(2_000_000, p.getInt(BITRATE, 2_000_000));
                return (bps % 1_000_000 == 0
                        ? String.valueOf(bps / 1_000_000)
                        : String.format(Locale.US, "%.1f", bps / 1_000_000f).replace('.', ',')) + " Мбит/с";
            }
            case AE_PRIORITY: return readableEnum(p.getString(AE_PRIORITY, "OFF"));
            case EXPOSURE_MODE: return readableEnum(p.getString(EXPOSURE_MODE, "AUTO"));
            case AE_LOCK: return p.getBoolean(AE_LOCK, false) ? "Включено" : "Выключено";
            case AWB_LOCK: return p.getBoolean(AWB_LOCK, false) ? "Включено" : "Выключено";
            case MANUAL_ISO: return "ISO " + p.getInt(MANUAL_ISO, 400);
            case MANUAL_EXPOSURE_US: return p.getInt(MANUAL_EXPOSURE_US, 10000) + " мкс";
            case MANUAL_FOCUS_DISTANCE: return Float.intBitsToFloat(p.getInt(MANUAL_FOCUS_DISTANCE, Float.floatToIntBits(0f))) + " дптр";
            case EXPOSURE_STEPS: return p.getInt(EXPOSURE_STEPS, -4) + " шага";
            case AWB: return readableEnum(p.getString(AWB, "AUTO"));
            case FOCUS: return readableEnum(p.getString(FOCUS, "CONTINUOUS_VIDEO"));
            case ANTIBANDING: return readableEnum(p.getString(ANTIBANDING, "AUTO"));
            case ZOOM: return String.format(Locale.US, "%.1f×", Float.intBitsToFloat(
                    p.getInt(ZOOM, Float.floatToIntBits(0.8f))));
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
            case AUDIO_BITRATE: return (p.getInt(AUDIO_BITRATE, 128_000) / 1000) + " кбит/с";
            case RECORDING_START_DELAY: return RoundVideoRecorderSettings.recordingStartDelayMs(getParentActivity()) + " мс";
            case MAX_DURATION: return RoundVideoRecorderSettings.maxRecordingDurationSeconds(getParentActivity()) + " сек.";
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
            } else if (type == CHECK) {
                view = new TextCheckCell(context);
            } else if (type == NOTE) {
                view = new TextInfoPrivacyCell(context);
            } else {
                TextSettingsCell cell = new TextSettingsCell(context);
                cell.setCanDisable(true);
                view = cell;
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
                            ? "При разрешении 640 × 640 и битрейте 2 Мбит/с максимальная длительность ограничена 30 секундами, чтобы снизить риск превышения 10 МБ. "
                            : "Максимальная длительность видеосообщения — 60 секунд. ";
                    text += "Внимание: ограничение длительности не гарантирует размер файла менее 10 МБ. "
                            + "По результатам тестов, при превышении 10 МБ Telegram может показать кружок обычным квадратным видео. "
                            + "Проверьте запись и при необходимости уменьшите разрешение, битрейт видео, битрейт звука или максимальную длину.";
                    ((TextInfoPrivacyCell) holder.itemView).setText(text);
                } else {
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.title);
                }
            } else if (row.type == CHECK) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                cell.setEnabled(editable(row));
                cell.setTextAndCheck(row.title, prefs().getBoolean(row.key,
                        HOLD_FPS.equals(row.key)), true);
            } else if (row.type == ACTION) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setEnabled(editable(row));
                cell.setText(row.title, false);
            } else {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setEnabled(editable(row));
                cell.setTextAndValue(row.title, valueFor(row.key, prefs()), true);
            }
        }
        @Override public int getItemViewType(int position) { return rows.get(position).type; }
    }
}
