/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.ui;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EasygramLoaderPreviewCell;
import org.telegram.ui.Cells.EasygramM3eOverviewCell;
import org.telegram.ui.Cells.EasygramMiniWavePreviewCell;
import org.telegram.ui.Cells.EasygramWavePreviewCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/**
 * Settings → Material 3 Expressive: the on/off switch, a live preview of everything it turns on, then the parts
 * that can be tuned separately (voice messages, mini player wave, download haptic).
 */
public class M3ExpressiveSettingsActivity extends BaseFragment {

    private static final int TYPE_HEADER = 0, TYPE_CHECK = 1, TYPE_INFO = 2, TYPE_SHADOW = 3,
            TYPE_VOICE = 4, TYPE_MINI_WAVES = 5, TYPE_LOADERS = 6, TYPE_OVERVIEW = 7, TYPE_MINI_VOICE_WAVES = 8;

    private RecyclerListView listView;
    private ListAdapter adapter;

    private int rowCount;
    private int enableRow, overviewHeaderRow, overviewRow, loadersPreviewRow, enableInfoRow;
    private int voiceHeaderRow, voicePreviewRow, voiceRow, voiceShadowRow;
    private int miniHeaderRow, miniDetailedRow, miniPreviewRow, miniVoiceDetailedRow, miniVoicePreviewRow, miniShadowRow;
    private int downloadHeaderRow, hapticRow, hapticInfoRow;

    private void updateRows() {
        rowCount = 0;
        enableRow = rowCount++;
        // always shown: what the main switch turns on
        overviewHeaderRow = rowCount++;
        overviewRow = rowCount++;
        loadersPreviewRow = rowCount++;
        enableInfoRow = rowCount++;
        // separately tunable parts, only while M3E is on
        boolean on = OtherSettingsActivity.isMaterialExpressiveEnabled();
        voiceHeaderRow = on ? rowCount++ : -1;
        voicePreviewRow = on ? rowCount++ : -1;
        voiceRow = on ? rowCount++ : -1;
        voiceShadowRow = on ? rowCount++ : -1;
        miniHeaderRow = on ? rowCount++ : -1;
        miniDetailedRow = on ? rowCount++ : -1;
        miniPreviewRow = on ? rowCount++ : -1;
        miniVoiceDetailedRow = on ? rowCount++ : -1;
        miniVoicePreviewRow = on ? rowCount++ : -1;
        miniShadowRow = on ? rowCount++ : -1;
        downloadHeaderRow = on ? rowCount++ : -1;
        hapticRow = on ? rowCount++ : -1;
        hapticInfoRow = on ? rowCount++ : -1;
    }

    @Override
    public boolean onFragmentCreate() {
        updateRows();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.EasygramM3eTitle));
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
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position == enableRow) {
                boolean next = !OtherSettingsActivity.isMaterialExpressiveEnabled();
                OtherSettingsActivity.setMaterialExpressiveEnabled(next);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(next);
                }
                int oldCount = rowCount;
                updateRows();
                if (next) {
                    adapter.notifyItemRangeInserted(enableInfoRow + 1, rowCount - oldCount);
                } else {
                    adapter.notifyItemRangeRemoved(enableInfoRow + 1, oldCount - rowCount);
                }
                notifyChats();
            } else if (position == voiceRow) {
                boolean next = !OtherSettingsActivity.isM3eVoiceEnabled();
                OtherSettingsActivity.setM3eVoiceEnabled(next);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(next);
                }
                adapter.notifyItemChanged(voicePreviewRow); // dims / restores the voice preview
                notifyChats();
            } else if (position == miniDetailedRow) {
                setMiniDetailed(false, !OtherSettingsActivity.isMiniPlayerDetailedWaveEnabled(false));
            } else if (position == miniVoiceDetailedRow) {
                setMiniDetailed(true, !OtherSettingsActivity.isMiniPlayerDetailedWaveEnabled(true));
            } else if (position == hapticRow) {
                boolean next = !OtherSettingsActivity.isDownloadHapticEnabled();
                OtherSettingsActivity.setDownloadHapticEnabled(next);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(next);
                }
                if (next) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                }
            }
        });

        fragmentView = root;
        return root;
    }

    private void setMiniDetailed(boolean voice, boolean detailed) {
        OtherSettingsActivity.setMiniPlayerDetailedWaveEnabled(voice, detailed);
        adapter.notifyItemChanged(voice ? miniVoiceDetailedRow : miniDetailedRow);
        adapter.notifyItemChanged(voice ? miniVoicePreviewRow : miniPreviewRow);
    }

    private void notifyChats() {
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == TYPE_CHECK;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == overviewHeaderRow || position == voiceHeaderRow || position == miniHeaderRow || position == downloadHeaderRow) return TYPE_HEADER;
            if (position == enableRow || position == voiceRow || position == miniDetailedRow || position == miniVoiceDetailedRow || position == hapticRow) return TYPE_CHECK;
            if (position == enableInfoRow || position == hapticInfoRow) return TYPE_INFO;
            if (position == overviewRow) return TYPE_OVERVIEW;
            if (position == voicePreviewRow) return TYPE_VOICE;
            if (position == miniPreviewRow) return TYPE_MINI_WAVES;
            if (position == miniVoicePreviewRow) return TYPE_MINI_VOICE_WAVES;
            if (position == loadersPreviewRow) return TYPE_LOADERS;
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
                case TYPE_OVERVIEW:
                    view = new EasygramM3eOverviewCell(context);
                    break;
                case TYPE_VOICE:
                    view = new EasygramWavePreviewCell(context);
                    break;
                case TYPE_MINI_WAVES:
                case TYPE_MINI_VOICE_WAVES: {
                    final boolean voice = viewType == TYPE_MINI_VOICE_WAVES;
                    EasygramMiniWavePreviewCell mini = new EasygramMiniWavePreviewCell(context, voice);
                    mini.setListener(detailed -> setMiniDetailed(voice, detailed));
                    view = mini;
                    break;
                }
                case TYPE_LOADERS:
                    view = new EasygramLoaderPreviewCell(context);
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
                    if (position == overviewHeaderRow) {
                        cell.setText(LocaleController.getString(R.string.EasygramM3eOverviewHeader));
                    } else if (position == voiceHeaderRow) {
                        cell.setText(LocaleController.getString(R.string.EasygramM3eVoiceHeader));
                    } else if (position == miniHeaderRow) {
                        cell.setText(LocaleController.getString(R.string.EasygramM3eMiniPlayerHeader));
                    } else if (position == downloadHeaderRow) {
                        cell.setText(LocaleController.getString(R.string.EasygramM3eDownloadHeader));
                    }
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == enableRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.EasygramMaterialExpressive),
                                OtherSettingsActivity.isMaterialExpressiveEnabled(), false);
                    } else if (position == voiceRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.EasygramM3eVoice),
                                OtherSettingsActivity.isM3eVoiceEnabled(), false);
                    } else if (position == miniDetailedRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.EasygramM3eMiniMusicDetailed),
                                OtherSettingsActivity.isMiniPlayerDetailedWaveEnabled(false), false);
                    } else if (position == miniVoiceDetailedRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.EasygramM3eMiniVoiceDetailed),
                                OtherSettingsActivity.isMiniPlayerDetailedWaveEnabled(true), false);
                    } else if (position == hapticRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.EasygramDownloadHaptic),
                                OtherSettingsActivity.isDownloadHapticEnabled(), false);
                    }
                    break;
                }
                case TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(LocaleController.getString(position == hapticInfoRow
                            ? R.string.EasygramDownloadHapticInfo : R.string.EasygramM3eOverviewInfo));
                    break;
                }
                case TYPE_VOICE:
                case TYPE_MINI_WAVES:
                case TYPE_MINI_VOICE_WAVES:
                    holder.itemView.invalidate();
                    break;
            }
        }
    }
}
