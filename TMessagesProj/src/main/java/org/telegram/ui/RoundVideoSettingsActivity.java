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

import java.util.ArrayList;
import java.util.Locale;

/** Easygram: all video-note controls in native Telegram settings cells (API 28+). */
public class RoundVideoSettingsActivity extends BaseFragment {
    private static final String PREFS = "round_video_custom_recorder";
    private static final String MODE = "mode";
    private static final String QUALITY = "quality";
    private static final String FPS = "fps";
    private static final String BITRATE = "bitrate";
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
        addHeader("Свет и фокус камеры");
        addCheck(LOW_LIGHT_BOOST, "Усиление при слабом освещении");
        addCheck(HOLD_FPS, "Сохранять FPS при слабом свете");
        addValue(AE_PRIORITY, "Приоритет экспозиции");
        addValue(EXPOSURE_STEPS, "Поправка экспозиции");
        addValue(AWB, "Баланс белого");
        addValue(FOCUS, "Фокусировка");
        addValue(ANTIBANDING, "Подавление мерцания");
        addValue(ZOOM, "Начальное увеличение");
        addHeader("Обработка изображения и стабилизация");
        addValue(NOISE, "Шумоподавление");
        addValue(STABILIZATION, "Электронная стабилизация");
        addValue(OPTICAL_STABILIZATION, "Оптическая стабилизация");
        addValue(EDGE, "Резкость и контуры");
        addValue(COLOR_CORRECTION, "Коррекция цвета");
        addValue(TONEMAP, "Тональная обработка");
        addHeader("Качество звука");
        addValue(AUDIO_BITRATE, "Битрейт звука");
        addHeader("Запуск записи");
        addValue(RECORDING_START_DELAY, "Задержка начала записи");
        rows.add(new Row(ACTION, RESET, "Сбросить настройки записи"));
        rows.add(new Row(NOTE, INFO,
                "Запись видеосообщений использует CustomRecorder (Android 9+). "
                        + "Настройки применяются к обеим камерам. Некоторые режимы "
                        + "могут не поддерживаться конкретным устройством."));
    }

    private SharedPreferences prefs() {
        Context context = getParentActivity();
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public View createView(Context context) {
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
        if (row.type == ACTION || MODE.equals(row.key)) return true;
        return prefs().getBoolean(MODE, true);
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
                choose(key, "Битрейт видео", new String[]{"0,5 Мбит/с", "1 Мбит/с", "1,5 Мбит/с", "2 Мбит/с", "3 Мбит/с", "4 Мбит/с", "6 Мбит/с", "8 Мбит/с", "10 Мбит/с"},
                        new String[]{"500000", "1000000", "1500000", "2000000", "3000000", "4000000", "6000000", "8000000", "10000000"}); break;
            case AE_PRIORITY:
                chooseEnums(key, "Приоритет экспозиции", AePrioritySetting.values()); break;
            case EXPOSURE_STEPS:
                choose(key, "Поправка экспозиции", new String[]{"−7", "−6", "−5", "−4", "−3", "−2", "−1", "0", "+1", "+2", "+3"},
                        new String[]{"-7", "-6", "-5", "-4", "-3", "-2", "-1", "0", "1", "2", "3"}); break;
            case AWB:
                chooseEnums(key, "Баланс белого", AwbSetting.values()); break;
            case FOCUS:
                chooseEnums(key, "Фокусировка", FocusSetting.values()); break;
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
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(title);
        builder.setItems(labels, (dialog, index) -> {
            String selected = values[index];
            SharedPreferences.Editor edit = prefs().edit();
            switch (key) {
                case MODE:
                    edit.putBoolean(key, Boolean.parseBoolean(selected)); break;
                case FPS:
                case BITRATE:
                case EXPOSURE_STEPS:
                case AUDIO_BITRATE:
                    edit.putInt(key, Integer.parseInt(selected)); break;
                case ZOOM:
                    edit.putInt(key, Float.floatToIntBits(Float.parseFloat(selected))); break;
                default:
                    edit.putString(key, selected); break;
            }
            edit.apply();
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
                int bps = p.getInt(BITRATE, 2_000_000);
                return (bps % 1_000_000 == 0
                        ? String.valueOf(bps / 1_000_000)
                        : String.format(Locale.US, "%.1f", bps / 1_000_000f).replace('.', ',')) + " Мбит/с";
            }
            case AE_PRIORITY: return readableEnum(p.getString(AE_PRIORITY, "OFF"));
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
            case AUDIO_BITRATE: return (p.getInt(AUDIO_BITRATE, 128_000) / 1000) + " кбит/с";
            case RECORDING_START_DELAY: return RoundVideoRecorderSettings.recordingStartDelayMs(getParentActivity()) + " мс";
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
                ((TextInfoPrivacyCell) holder.itemView).setText(row.title);
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
