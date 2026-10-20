package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.Switch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Easygram settings for optional app integrations, stories, ads, and navigation. */
public class OtherSettingsActivity extends BaseFragment {
    public static final String DEFAULT_GALLERY_PACKAGE = "easygram_default_gallery_package";
    public static final String EMBEDDED_PHOTO_PICKER = "__embedded_photo_picker__";

    public static final String KEY_TAB_CHATS = "easygram_tab_chats";
    public static final String KEY_TAB_CONTACTS = "easygram_tab_contacts";
    public static final String KEY_TAB_CALLS = "easygram_tab_calls";
    public static final String KEY_TAB_SETTINGS = "easygram_tab_settings";
    public static final String KEY_TAB_PROFILE = "easygram_tab_profile";

    public static final String KEY_TAB_ORDER = "easygram_tab_order";
    public static final String DEFAULT_TAB_ORDER = "0,1,2,3,4";

    public static final int INDEX_CHATS = 0;
    public static final int INDEX_CONTACTS = 1;
    public static final int INDEX_CALLS = 2;
    public static final int INDEX_SETTINGS = 3;
    public static final int INDEX_PROFILE = 4;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private ItemTouchHelper itemTouchHelper;

    private int headerRow;
    private int galleryRow;
    private int galleryInfoRow;
    private int sendMediaHdRow;
    private int sendMediaHdInfoRow;
    private int disableStoriesRow;
    private int storiesInfoRow;
    private int disableAdsRow;
    private int adsInfoRow;
    private int navHeaderRow;
    private int navStartRow;
    private int navEndRow;
    private int navInfoRow;
    private int rowCount;

    private final ArrayList<Integer> navItems = new ArrayList<>();

    private SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
    }

    public static boolean isTabEnabled(int index, SharedPreferences prefs, int currentAccount) {
        switch (index) {
            case INDEX_CHATS:
                return prefs.getBoolean(KEY_TAB_CHATS, true);
            case INDEX_CONTACTS:
                return prefs.getBoolean(KEY_TAB_CONTACTS, true);
            case INDEX_CALLS:
                return prefs.getBoolean(KEY_TAB_CALLS, UserConfig.getInstance(currentAccount).showCallsTab);
            case INDEX_SETTINGS:
                return prefs.getBoolean(KEY_TAB_SETTINGS, true);
            case INDEX_PROFILE:
                return prefs.getBoolean(KEY_TAB_PROFILE, true);
            default:
                return true;
        }
    }

    public static int getEnabledTabsCount(SharedPreferences prefs, int currentAccount) {
        int count = 0;
        for (int i = 0; i <= INDEX_PROFILE; i++) {
            if (isTabEnabled(i, prefs, currentAccount)) count++;
        }
        return count;
    }

    public static List<Integer> getTabOrder(SharedPreferences prefs) {
        String orderStr = prefs.getString(KEY_TAB_ORDER, DEFAULT_TAB_ORDER);
        List<Integer> order = new ArrayList<>();
        if (orderStr != null && !orderStr.isEmpty()) {
            String[] parts = orderStr.split(",");
            for (String part : parts) {
                try {
                    int idx = Integer.parseInt(part.trim());
                    if (idx >= 0 && idx <= INDEX_PROFILE && !order.contains(idx)) {
                        order.add(idx);
                    }
                } catch (Exception ignored) {}
            }
        }
        for (int i = 0; i <= INDEX_PROFILE; i++) {
            if (!order.contains(i)) {
                order.add(i);
            }
        }
        return order;
    }

    public static void saveTabOrder(SharedPreferences prefs, List<Integer> order) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < order.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(order.get(i));
        }
        prefs.edit().putString(KEY_TAB_ORDER, sb.toString()).apply();
    }

    private String getSelectedGalleryLabel() {
        String packageName = preferences().getString(DEFAULT_GALLERY_PACKAGE, null);
        if (packageName == null) {
            return LocaleController.getString(R.string.EasygramBuiltInGallery);
        }
        if (EMBEDDED_PHOTO_PICKER.equals(packageName)) {
            return LocaleController.getString(R.string.EasygramEmbeddedPhotoPicker);
        }
        try {
            return getParentActivity().getPackageManager()
                    .getApplicationLabel(getParentActivity().getPackageManager().getApplicationInfo(packageName, 0))
                    .toString();
        } catch (Exception ignored) {
            return LocaleController.getString(R.string.EasygramBuiltInGallery);
        }
    }

    private void updateRows() {
        rowCount = 0;
        headerRow = rowCount++;
        galleryRow = rowCount++;
        galleryInfoRow = rowCount++;
        sendMediaHdRow = rowCount++;
        sendMediaHdInfoRow = rowCount++;
        disableStoriesRow = rowCount++;
        storiesInfoRow = rowCount++;
        disableAdsRow = rowCount++;
        adsInfoRow = rowCount++;
        navHeaderRow = rowCount++;
        navStartRow = rowCount;
        rowCount += navItems.size();
        navEndRow = rowCount;
        navInfoRow = rowCount++;
    }

    @Override
    public boolean onFragmentCreate() {
        navItems.clear();
        navItems.addAll(getTabOrder(preferences()));
        updateRows();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.EasygramOther));
        actionBar.setActionBarMenuOnItemClick(new org.telegram.ui.ActionBar.ActionBar.ActionBarMenuOnItemClick() {
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

        itemTouchHelper = new ItemTouchHelper(new TouchHelperCallback());
        itemTouchHelper.attachToRecyclerView(listView);

        listView.setOnItemClickListener((view, position) -> {
            if (position == galleryRow) {
                showGalleryChooser(view);
            } else if (position == sendMediaHdRow) {
                boolean current = preferences().getBoolean("easygram_send_media_hd", SharedConfig.photoHighQualityDefault);
                boolean next = !current;
                preferences().edit().putBoolean("easygram_send_media_hd", next).apply();
                SharedConfig.photoHighQualityDefault = next;
                ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                        .edit().putBoolean("photoHighQualityDefault", next).apply();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(next);
                }
            } else if (position == disableStoriesRow) {
                boolean current = preferences().getBoolean("easygram_disable_stories", false);
                boolean next = !current;
                preferences().edit().putBoolean("easygram_disable_stories", next).apply();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(next);
                }
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesUpdated);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesEnabledUpdate);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.mainTabsLayoutChanged);
            } else if (position == disableAdsRow) {
                boolean current = preferences().getBoolean("easygram_disable_ads", false);
                boolean next = !current;
                preferences().edit().putBoolean("easygram_disable_ads", next).apply();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(next);
                }
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, org.telegram.messenger.MessagesController.UPDATE_MASK_ALL);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesUpdated);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.mainTabsLayoutChanged);
            } else if (position >= navStartRow && position < navEndRow) {
                int index = navItems.get(position - navStartRow);
                if (view instanceof ReorderCheckCell) {
                    toggleTab(index, (ReorderCheckCell) view);
                }
            }
        });

        fragmentView = root;
        return fragmentView;
    }

    private void toggleTab(int index, ReorderCheckCell cell) {
        boolean current = isTabEnabled(index, preferences(), currentAccount);
        if (current && getEnabledTabsCount(preferences(), currentAccount) <= 1) {
            BulletinFactory.of(this).createSimpleBulletin(
                    R.drawable.msg_info,
                    LocaleController.getString(R.string.EasygramNavMinError)
            ).show();
            return;
        }
        boolean next = !current;
        SharedPreferences.Editor editor = preferences().edit();
        switch (index) {
            case INDEX_CHATS:
                editor.putBoolean(KEY_TAB_CHATS, next);
                break;
            case INDEX_CONTACTS:
                editor.putBoolean(KEY_TAB_CONTACTS, next);
                break;
            case INDEX_CALLS:
                editor.putBoolean(KEY_TAB_CALLS, next);
                UserConfig.getInstance(currentAccount).setShowCallsTab(next);
                break;
            case INDEX_SETTINGS:
                editor.putBoolean(KEY_TAB_SETTINGS, next);
                break;
            case INDEX_PROFILE:
                editor.putBoolean(KEY_TAB_PROFILE, next);
                break;
        }
        editor.apply();
        cell.setChecked(next);
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.mainTabsLayoutChanged);
    }

    private void showGalleryChooser(View view) {
        if (getParentActivity() == null) return;
        PackageManager packageManager = getParentActivity().getPackageManager();
        Map<String, ResolveInfo> uniqueApps = new LinkedHashMap<>();
        String[] mediaTypes = {"image/*", "video/*", "*/*"};
        for (String mediaType : mediaTypes) {
            Intent queryIntent = new Intent(Intent.ACTION_GET_CONTENT);
            queryIntent.setType(mediaType);
            List<ResolveInfo> resolved = packageManager.queryIntentActivities(queryIntent, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo info : resolved) {
                if (info.activityInfo != null && info.activityInfo.packageName != null) {
                    uniqueApps.putIfAbsent(info.activityInfo.packageName, info);
                }
            }
        }
        ArrayList<ResolveInfo> apps = new ArrayList<>(uniqueApps.values());
        Collections.sort(apps, Comparator.comparing(
                info -> info.loadLabel(packageManager).toString(), String.CASE_INSENSITIVE_ORDER
        ));

        String[] labels = new String[apps.size() + 2];
        labels[0] = LocaleController.getString(R.string.EasygramBuiltInGallery);
        labels[1] = LocaleController.getString(R.string.EasygramEmbeddedPhotoPicker);
        for (int i = 0; i < apps.size(); i++) labels[i + 2] = apps.get(i).loadLabel(packageManager).toString();

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.EasygramDefaultGallery));
        builder.setItems(labels, (dialog, index) -> {
            SharedPreferences.Editor editor = preferences().edit();
            if (index == 0) {
                editor.remove(DEFAULT_GALLERY_PACKAGE);
            } else if (index == 1) {
                editor.putString(DEFAULT_GALLERY_PACKAGE, EMBEDDED_PHOTO_PICKER);
            } else {
                editor.putString(DEFAULT_GALLERY_PACKAGE, apps.get(index - 2).activityInfo.packageName);
            }
            editor.apply();
            if (adapter != null) {
                adapter.notifyItemChanged(galleryRow);
            }
        });
        builder.show();
    }

    public static class ReorderCheckCell extends FrameLayout {
        private final TextView textView;
        private final Switch switchView;
        private final ImageView moveImageView;
        private boolean needDivider;

        public ReorderCheckCell(Context context) {
            super(context);
            setWillNotDraw(false);

            moveImageView = new ImageView(context);
            moveImageView.setScaleType(ImageView.ScaleType.CENTER);
            moveImageView.setImageResource(R.drawable.list_reorder);
            moveImageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_stickers_menu), PorterDuff.Mode.MULTIPLY));
            moveImageView.setFocusable(false);
            addView(moveImageView, LayoutHelper.createFrame(48, 48, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, 6, 0, 6, 0));

            switchView = new Switch(context);
            switchView.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
            switchView.setFocusable(false);
            addView(switchView, LayoutHelper.createFrame(37, 20, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 22, 0, 22, 0));

            textView = new TextView(context);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            textView.setLines(1);
            textView.setMaxLines(1);
            textView.setSingleLine(true);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, LocaleController.isRTL ? 60 : 72, 0, LocaleController.isRTL ? 72 : 60, 0));
        }

        public void setItem(String title, boolean checked, boolean divider) {
            textView.setText(title);
            switchView.setChecked(checked, false);
            needDivider = divider;
            invalidate();
        }

        public void setChecked(boolean checked) {
            switchView.setChecked(checked, true);
        }

        public ImageView getMoveImageView() {
            return moveImageView;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(50), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (needDivider) {
                canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(72), getHeight() - 1, getWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(72) : 0), getHeight() - 1, Theme.dividerPaint);
            }
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == 1 || type == 3 || type == 4;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == headerRow || position == navHeaderRow) return 0;
            if (position == galleryRow) return 1;
            if (position == galleryInfoRow || position == sendMediaHdInfoRow || position == storiesInfoRow || position == adsInfoRow || position == navInfoRow) return 2;
            if (position == sendMediaHdRow || position == disableStoriesRow || position == disableAdsRow) return 3;
            if (position >= navStartRow && position < navEndRow) return 4;
            return 0;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 1:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 2:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case 3:
                    view = new TextCheckCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 4:
                default:
                    view = new ReorderCheckCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 0:
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == headerRow) {
                        headerCell.setText(LocaleController.getString(R.string.EasygramOther));
                    } else if (position == navHeaderRow) {
                        headerCell.setText(LocaleController.getString(R.string.EasygramNavigationBar));
                    }
                    break;
                case 1:
                    TextSettingsCell textCell = (TextSettingsCell) holder.itemView;
                    textCell.setTextAndValue(
                            LocaleController.getString(R.string.EasygramDefaultGallery),
                            getSelectedGalleryLabel(),
                            false
                    );
                    break;
                case 2:
                    TextInfoPrivacyCell infoCell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == galleryInfoRow) {
                        infoCell.setText(LocaleController.getString(R.string.EasygramDefaultGalleryInfo));
                    } else if (position == sendMediaHdInfoRow) {
                        infoCell.setText(LocaleController.getString(R.string.EasygramSendMediaHdInfo));
                    } else if (position == storiesInfoRow) {
                        infoCell.setText(LocaleController.getString(R.string.EasygramDisableStoriesInfo));
                    } else if (position == adsInfoRow) {
                        infoCell.setText(LocaleController.getString(R.string.EasygramDisableAdsInfo));
                    } else if (position == navInfoRow) {
                        infoCell.setText(LocaleController.getString(R.string.EasygramNavigationBarInfo));
                    }
                    break;
                case 3:
                    TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                    if (position == sendMediaHdRow) {
                        boolean sendMediaHd = preferences().getBoolean("easygram_send_media_hd", SharedConfig.photoHighQualityDefault);
                        checkCell.setTextAndCheck(
                                LocaleController.getString(R.string.EasygramSendMediaHd),
                                sendMediaHd,
                                false
                        );
                    } else if (position == disableStoriesRow) {
                        boolean disabledStories = preferences().getBoolean("easygram_disable_stories", false);
                        checkCell.setTextAndCheck(
                                LocaleController.getString(R.string.EasygramDisableStories),
                                disabledStories,
                                true
                        );
                    } else if (position == disableAdsRow) {
                        boolean disabledAds = preferences().getBoolean("easygram_disable_ads", false);
                        checkCell.setTextAndCheck(
                                LocaleController.getString(R.string.EasygramDisableAds),
                                disabledAds,
                                false
                        );
                    }
                    break;
                case 4:
                    ReorderCheckCell checkCell2 = (ReorderCheckCell) holder.itemView;
                    int index = navItems.get(position - navStartRow);
                    String title;
                    switch (index) {
                        case INDEX_CHATS:
                            title = LocaleController.getString(R.string.MainTabsChats);
                            break;
                        case INDEX_CONTACTS:
                            title = LocaleController.getString(R.string.MainTabsContacts);
                            break;
                        case INDEX_CALLS:
                            title = LocaleController.getString(R.string.MainTabsCalls);
                            break;
                        case INDEX_SETTINGS:
                            title = LocaleController.getString(R.string.Settings);
                            break;
                        case INDEX_PROFILE:
                        default:
                            title = LocaleController.getString(R.string.MainTabsProfile);
                            break;
                    }
                    boolean checked = isTabEnabled(index, preferences(), currentAccount);
                    checkCell2.setItem(title, checked, position != navEndRow - 1);
                    checkCell2.getMoveImageView().setOnTouchListener((v, event) -> {
                        if (event.getAction() == MotionEvent.ACTION_DOWN) {
                            itemTouchHelper.startDrag(holder);
                        }
                        return false;
                    });
                    break;
            }
        }
    }

    public class TouchHelperCallback extends ItemTouchHelper.Callback {

        @Override
        public boolean isLongPressDragEnabled() {
            return true;
        }

        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            if (viewHolder.getItemViewType() != 4) {
                return makeMovementFlags(0, 0);
            }
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder source, @NonNull RecyclerView.ViewHolder target) {
            if (source.getItemViewType() != target.getItemViewType()) {
                return false;
            }
            int fromPos = source.getAdapterPosition();
            int toPos = target.getAdapterPosition();
            if (fromPos >= navStartRow && fromPos < navEndRow && toPos >= navStartRow && toPos < navEndRow) {
                int fromIdx = fromPos - navStartRow;
                int toIdx = toPos - navStartRow;
                Collections.swap(navItems, fromIdx, toIdx);
                saveTabOrder(preferences(), navItems);
                adapter.notifyItemMoved(fromPos, toPos);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.mainTabsLayoutChanged);
                return true;
            }
            return false;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {

        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
            if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                listView.cancelClickRunnables(false);
                viewHolder.itemView.setPressed(true);
            }
            super.onSelectedChanged(viewHolder, actionState);
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            viewHolder.itemView.setPressed(false);
        }
    }
}
