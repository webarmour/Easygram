/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.asr.EasygramAsrModelManager;
import org.telegram.messenger.asr.EasygramAsrModels;
import org.telegram.messenger.asr.EasygramAsrSettings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EasygramAsrModelCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.List;

/**
 * Settings → Voice transcription: on-device speech recognition on/off, the downloadable models of each language
 * (choose, download, cancel, delete) and the language and performance options. Opened from Local AI.
 */
public class EasygramAsrSettingsActivity extends BaseFragment implements EasygramAsrModelManager.Listener {

    private static final int TYPE_HEADER = 0, TYPE_CHECK = 1, TYPE_INFO = 2, TYPE_SHADOW = 3, TYPE_MODEL = 4, TYPE_SETTING = 5;

    private RecyclerListView listView;
    private ListAdapter adapter;

    private final List<EasygramAsrModels.Model> ruModels = EasygramAsrModels.forLang(EasygramAsrModels.LANG_RU);
    private final List<EasygramAsrModels.Model> enModels = EasygramAsrModels.forLang(EasygramAsrModels.LANG_EN);
    private final List<EasygramAsrModels.Model> lidModels = EasygramAsrModels.forLang(EasygramAsrModels.LANG_ID);

    private int rowCount;
    private int enableRow, enableInfoRow;
    private int ruHeaderRow, ruStartRow, ruShadowRow;
    private int enHeaderRow, enStartRow, enInfoRow;
    private int lidHeaderRow, lidStartRow, lidInfoRow;
    private int settingsHeaderRow, langRow, perfRow, hapticRow, wifiRow, langInfoRow;

    private void updateRows() {
        rowCount = 0;
        enableRow = rowCount++;
        enableInfoRow = rowCount++;
        ruHeaderRow = rowCount++;
        ruStartRow = rowCount;
        rowCount += ruModels.size();
        ruShadowRow = rowCount++;
        enHeaderRow = rowCount++;
        enStartRow = rowCount;
        rowCount += enModels.size();
        enInfoRow = rowCount++;
        lidHeaderRow = rowCount++;
        lidStartRow = rowCount;
        rowCount += lidModels.size();
        lidInfoRow = rowCount++;
        settingsHeaderRow = rowCount++;
        langRow = rowCount++;
        perfRow = rowCount++;
        hapticRow = rowCount++;
        wifiRow = rowCount++;
        langInfoRow = rowCount++;
    }

    private EasygramAsrModels.Model modelAt(int position) {
        if (position >= ruStartRow && position < ruStartRow + ruModels.size()) {
            return ruModels.get(position - ruStartRow);
        }
        if (position >= enStartRow && position < enStartRow + enModels.size()) {
            return enModels.get(position - enStartRow);
        }
        if (position >= lidStartRow && position < lidStartRow + lidModels.size()) {
            return lidModels.get(position - lidStartRow);
        }
        return null;
    }

    @Override
    public boolean onFragmentCreate() {
        updateRows();
        EasygramAsrModelManager.addListener(this);
        EasygramAsrModelManager.checkUpdates();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        EasygramAsrModelManager.removeListener(this);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.EasygramAsrTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setVerticalScrollBarEnabled(false);
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setSupportsChangeAnimations(false);
        listView.setItemAnimator(animator);
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            EasygramAsrModels.Model model = modelAt(position);
            if (model != null) {
                if (!EasygramAsrModels.LANG_ID.equals(model.lang)) {
                    selectModel(model);
                }
                if (EasygramAsrModelManager.getState(model) == EasygramAsrModelManager.STATE_NONE) {
                    startDownload(model);
                }
            } else if (position == enableRow) {
                boolean next = !EasygramAsrSettings.isEnabled();
                EasygramAsrSettings.setEnabled(next);
                ((TextCheckCell) view).setChecked(next);
                notifyChats();
            } else if (position == hapticRow) {
                boolean next = !EasygramAsrSettings.isRevealHaptic();
                EasygramAsrSettings.setRevealHaptic(next);
                ((TextCheckCell) view).setChecked(next);
                if (next) {
                    view.performHapticFeedback(android.os.Build.VERSION.SDK_INT >= 30 ? android.view.HapticFeedbackConstants.CONFIRM
                            : android.view.HapticFeedbackConstants.KEYBOARD_TAP, android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                }
            } else if (position == wifiRow) {
                boolean next = !EasygramAsrSettings.isWifiOnly();
                EasygramAsrSettings.setWifiOnly(next);
                ((TextCheckCell) view).setChecked(next);
            } else if (position == langRow) {
                CharSequence[] items = {
                        LocaleController.getString(R.string.EasygramAsrLangAuto),
                        LocaleController.getString(R.string.EasygramAsrRussian),
                        LocaleController.getString(R.string.EasygramAsrEnglish)
                };
                showChooser(LocaleController.getString(R.string.EasygramAsrLanguage), items, which -> {
                    EasygramAsrSettings.setLangMode(which);
                    adapter.notifyItemChanged(langRow);
                });
            } else if (position == perfRow) {
                CharSequence[] items = {
                        LocaleController.getString(R.string.EasygramAsrPerfGentle),
                        LocaleController.getString(R.string.EasygramAsrPerfEconomy),
                        LocaleController.getString(R.string.EasygramAsrPerfFast)
                };
                final int[] values = {EasygramAsrSettings.PERF_GENTLE, EasygramAsrSettings.PERF_BALANCED, EasygramAsrSettings.PERF_FAST};
                showChooser(LocaleController.getString(R.string.EasygramAsrPerformance), items, which -> {
                    EasygramAsrSettings.setPerformance(values[which]);
                    adapter.notifyItemChanged(perfRow);
                });
            }
        });

        fragmentView = root;
        return root;
    }

    private void showChooser(CharSequence title, CharSequence[] items, Utilities.Callback<Integer> onSelect) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(title);
        builder.setItems(items, (dialog, which) -> onSelect.run(which));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void selectModel(EasygramAsrModels.Model model) {
        EasygramAsrSettings.setSelectedModelId(model.lang, model.id);
        List<EasygramAsrModels.Model> list = model.lang.equals(EasygramAsrModels.LANG_RU) ? ruModels : enModels;
        for (EasygramAsrModels.Model m : list) {
            updateModelCell(m);
        }
    }

    private void onModelAction(EasygramAsrModels.Model model, int state) {
        if (state == EasygramAsrModelManager.STATE_NONE) {
            if (!EasygramAsrModels.LANG_ID.equals(model.lang)) {
                selectModel(model);
            }
            startDownload(model);
        } else if (state == EasygramAsrModelManager.STATE_UPDATE) {
            startDownload(model);
        } else if (state == EasygramAsrModelManager.STATE_READY) {
            AlertDialog dialog = new AlertDialog.Builder(getParentActivity())
                    .setTitle(LocaleController.getString(R.string.Delete))
                    .setMessage(LocaleController.formatString(R.string.EasygramAsrDeleteModel, model.title))
                    .setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
                        EasygramAsrModelManager.delete(model);
                        notifyChats();
                    })
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .create();
            showDialog(dialog);
            dialog.redPositive();
        } else {
            EasygramAsrModelManager.cancel(model);
        }
    }

    private void startDownload(EasygramAsrModels.Model model) {
        if (EasygramAsrModelManager.download(model, false) == EasygramAsrModelManager.ERROR_WIFI_REQUIRED) {
            AlertDialog dialog = new AlertDialog.Builder(getParentActivity())
                    .setTitle(LocaleController.getString(R.string.EasygramAsrMeteredTitle))
                    .setMessage(LocaleController.formatString(R.string.EasygramAsrMeteredText, AndroidUtilities.formatFileSize(EasygramAsrModelManager.downloadSize(model))))
                    .setPositiveButton(LocaleController.getString(R.string.EasygramAsrDownload), (d, w) -> EasygramAsrModelManager.download(model, true))
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .create();
            showDialog(dialog);
        }
    }

    @Override
    public void onAsrModelChanged(String modelId) {
        EasygramAsrModels.Model model = EasygramAsrModels.byId(modelId);
        if (model == null || listView == null) {
            return;
        }
        int state = EasygramAsrModelManager.getState(model);
        if (state == EasygramAsrModelManager.STATE_READY || state == EasygramAsrModelManager.STATE_NONE) {
            notifyChats(); // the transcribe button appears / disappears in chats
        }
        updateModelCell(model);
    }

    private void updateModelCell(EasygramAsrModels.Model model) {
        if (listView == null) {
            return;
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child instanceof EasygramAsrModelCell && ((EasygramAsrModelCell) child).getModel() == model) {
                int position = listView.getChildAdapterPosition(child);
                ((EasygramAsrModelCell) child).set(model, hasDivider(position), true);
            }
        }
    }

    private boolean hasDivider(int position) {
        return position != ruStartRow + ruModels.size() - 1 && position != enStartRow + enModels.size() - 1
                && position != lidStartRow + lidModels.size() - 1;
    }

    private void notifyChats() {
        for (int a = 0; a < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (org.telegram.messenger.UserConfig.getInstance(a).isClientActivated()) {
                NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
            }
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == TYPE_CHECK || type == TYPE_MODEL || type == TYPE_SETTING;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public int getItemViewType(int position) {
            if (modelAt(position) != null) return TYPE_MODEL;
            if (position == ruHeaderRow || position == enHeaderRow || position == lidHeaderRow || position == settingsHeaderRow) return TYPE_HEADER;
            if (position == enableRow || position == hapticRow || position == wifiRow) return TYPE_CHECK;
            if (position == langRow || position == perfRow) return TYPE_SETTING;
            if (position == enableInfoRow || position == enInfoRow || position == lidInfoRow || position == langInfoRow) return TYPE_INFO;
            return TYPE_SHADOW;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_HEADER:
                    view = new HeaderCell(context);
                    break;
                case TYPE_CHECK:
                    view = new TextCheckCell(context);
                    break;
                case TYPE_INFO:
                    view = new TextInfoPrivacyCell(context);
                    break;
                case TYPE_MODEL: {
                    EasygramAsrModelCell cell = new EasygramAsrModelCell(context);
                    cell.setDelegate(EasygramAsrSettingsActivity.this::onModelAction);
                    view = cell;
                    break;
                }
                case TYPE_SETTING:
                    view = new TextSettingsCell(context);
                    break;
                case TYPE_SHADOW:
                default:
                    view = new ShadowSectionCell(context);
                    break;
            }
            if (viewType != TYPE_INFO && viewType != TYPE_SHADOW) {
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == ruHeaderRow) {
                        cell.setText(LocaleController.getString(R.string.EasygramAsrRussian));
                    } else if (position == enHeaderRow) {
                        cell.setText(LocaleController.getString(R.string.EasygramAsrEnglish));
                    } else if (position == lidHeaderRow) {
                        cell.setText(LocaleController.getString(R.string.EasygramAsrLidHeader));
                    } else {
                        cell.setText(LocaleController.getString(R.string.EasygramAsrSettingsHeader));
                    }
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == enableRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.EasygramAsrEnable), EasygramAsrSettings.isEnabled(), false);
                    } else if (position == hapticRow) {
                        cell.setTextAndValueAndCheck(LocaleController.getString(R.string.EasygramAsrHaptic),
                                LocaleController.getString(R.string.EasygramAsrHapticInfo), EasygramAsrSettings.isRevealHaptic(), true, true);
                    } else if (position == wifiRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.EasygramAsrWifiOnly), EasygramAsrSettings.isWifiOnly(), false);
                    }
                    break;
                }
                case TYPE_MODEL:
                    ((EasygramAsrModelCell) holder.itemView).set(modelAt(position), hasDivider(position), false);
                    break;
                case TYPE_SETTING: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    if (position == langRow) {
                        int mode = EasygramAsrSettings.getLangMode();
                        cell.setTextAndValue(LocaleController.getString(R.string.EasygramAsrLanguage), LocaleController.getString(
                                mode == EasygramAsrSettings.LANG_RU ? R.string.EasygramAsrRussian
                                        : mode == EasygramAsrSettings.LANG_EN ? R.string.EasygramAsrEnglish : R.string.EasygramAsrLangAuto), true);
                    } else if (position == perfRow) {
                        cell.setTextAndValue(LocaleController.getString(R.string.EasygramAsrPerformance), LocaleController.getString(
                                EasygramAsrSettings.getPerformance() == EasygramAsrSettings.PERF_FAST ? R.string.EasygramAsrPerfFast
                                        : EasygramAsrSettings.isGentle() ? R.string.EasygramAsrPerfGentle : R.string.EasygramAsrPerfEconomy), true);
                    }
                    break;
                }
                case TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    int res;
                    if (position == enableInfoRow) {
                        res = R.string.EasygramAsrEnableInfo;
                    } else if (position == enInfoRow) {
                        res = R.string.EasygramAsrModelsInfo;
                    } else if (position == lidInfoRow) {
                        res = R.string.EasygramAsrLidInfo;
                    } else {
                        cell.setText(LocaleController.getString(R.string.EasygramAsrLanguageInfo) + "\n\n" + LocaleController.getString(R.string.EasygramAsrPerfInfo));
                        break;
                    }
                    cell.setText(LocaleController.getString(res));
                    break;
                }
            }
        }
    }
}
