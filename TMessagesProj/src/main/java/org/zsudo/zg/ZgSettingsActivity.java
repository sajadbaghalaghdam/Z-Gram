/*
 * ZG: settings screen for the built-in VLESS/REALITY tunnel.
 * Reachable from Settings -> Data and Storage -> Proxy Settings -> "ZG Proxy".
 */

package org.zsudo.zg;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

public class ZgSettingsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final long STATS_REFRESH_INTERVAL = 2000;

    private ListAdapter listAdapter;
    private RecyclerListView listView;

    private int rowCount;
    private int enableRow;
    private int enableInfoRow;
    private int settingsHeaderRow;
    private int linkRow;
    private int linkInfoRow;
    private int fragmentRow;
    private int fragmentInfoRow;
    private int portRow;
    private int portInfoRow;
    private int statusHeaderRow;
    private int statusRow;
    private int routingRow;
    private int versionRow;
    private int statsRow;
    private int statusShadowRow;
    private int errorInfoRow;

    private final ZgProxyController controller = ZgProxyController.getInstance();

    private final Runnable statsRefresher = new Runnable() {
        @Override
        public void run() {
            if (listAdapter == null || isPaused()) {
                return;
            }
            if (controller.isRunning()) {
                listAdapter.notifyItemChanged(statsRow);
            }
            AndroidUtilities.runOnUIThread(this, STATS_REFRESH_INTERVAL);
        }
    };

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.zgProxyStateChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
        updateRows();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        AndroidUtilities.cancelRunOnUIThread(statsRefresher);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.zgProxyStateChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxySettingsChanged);
    }

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.cancelRunOnUIThread(statsRefresher);
        AndroidUtilities.runOnUIThread(statsRefresher, STATS_REFRESH_INTERVAL);
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(statsRefresher);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(getString(R.string.ZgProxy));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        listAdapter = new ListAdapter(context);

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));
        listView.setAdapter(listAdapter);
        listView.setOnItemClickListener((view, position) -> {
            if (position == enableRow) {
                toggleEnabled((TextCheckCell) view);
            } else if (position == linkRow) {
                editLink();
            } else if (position == fragmentRow) {
                editFragmentSpec();
            } else if (position == portRow) {
                editPort();
            }
        });

        return fragmentView;
    }

    private void showError(String title, String text) {
        if (getParentActivity() == null) {
            return;
        }
        showDialog(AlertsCreator.createSimpleAlert(getParentActivity(), title, text).create());
    }

    private void toggleEnabled(TextCheckCell cell) {
        if (controller.isBusy()) {
            return;
        }
        boolean enable = !controller.isEnabled();
        if (enable && !ZgProxyController.isValidVlessUrl(controller.getVlessUrl())) {
            showError(getString(R.string.ZgProxy), getString(R.string.ZgVlessLinkInvalid));
            return;
        }
        cell.setChecked(enable);
        updateStatusRows();
        controller.setEnabled(enable, (running, error) -> {
            if (listAdapter == null) {
                return;
            }
            listAdapter.notifyItemChanged(enableRow, ListAdapter.PAYLOAD_CHECKED_CHANGED);
            updateRows();
            if (enable && !running) {
                showError(getString(R.string.ZgStartFailed), TextUtils.isEmpty(error) ? getString(R.string.ZgStatusError) : error);
            }
        });
    }

    private void editLink() {
        if (getParentActivity() == null) {
            return;
        }
        AlertsCreator.createSimpleTextInputAlert(getParentActivity(), this, getString(R.string.ZgVlessLink), getString(R.string.ZgVlessLinkInfo), getString(R.string.ZgVlessLinkHint), controller.getVlessUrl(), 4096, getString(R.string.Save), getResourceProvider(), text -> {
            if (!ZgProxyController.isValidVlessUrl(text)) {
                showError(getString(R.string.ZgProxy), getString(R.string.ZgVlessLinkInvalid));
                return;
            }
            controller.setVlessUrl(text);
            listAdapter.notifyItemChanged(linkRow);
            controller.restartIfEnabled(null);
        });
    }

    private void editFragmentSpec() {
        if (getParentActivity() == null) {
            return;
        }
        AlertsCreator.createSimpleTextInputAlert(getParentActivity(), this, getString(R.string.ZgFragmentSpec), getString(R.string.ZgFragmentSpecInfo), ZgProxyController.DEFAULT_FRAGMENT_SPEC, controller.getFragmentSpec(), 128, getString(R.string.Save), getResourceProvider(), text -> {
            controller.setFragmentSpec(text);
            listAdapter.notifyItemChanged(fragmentRow);
            controller.restartIfEnabled(null);
        });
    }

    private void editPort() {
        if (getParentActivity() == null) {
            return;
        }
        int current = controller.getListenPort();
        AlertsCreator.createSimpleTextInputAlert(getParentActivity(), this, getString(R.string.ZgListenPort), getString(R.string.ZgListenPortInfo), "0", String.valueOf(current), 5, getString(R.string.Save), getResourceProvider(), text -> {
            int port;
            try {
                port = Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                port = -1;
            }
            if (port < 0 || port > 65535) {
                showError(getString(R.string.ZgProxy), getString(R.string.ZgListenPortInvalid));
                return;
            }
            controller.setListenPort(port);
            listAdapter.notifyItemChanged(portRow);
            controller.restartIfEnabled(null);
        });
    }

    private void updateRows() {
        rowCount = 0;
        enableRow = rowCount++;
        enableInfoRow = rowCount++;
        settingsHeaderRow = rowCount++;
        linkRow = rowCount++;
        linkInfoRow = rowCount++;
        fragmentRow = rowCount++;
        fragmentInfoRow = rowCount++;
        portRow = rowCount++;
        portInfoRow = rowCount++;
        statusHeaderRow = rowCount++;
        statusRow = rowCount++;
        routingRow = rowCount++;
        versionRow = rowCount++;
        statsRow = rowCount++;
        if (!TextUtils.isEmpty(controller.getLastError()) || !controller.isNativeAvailable()) {
            statusShadowRow = -1;
            errorInfoRow = rowCount++;
        } else {
            statusShadowRow = rowCount++;
            errorInfoRow = -1;
        }
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void updateStatusRows() {
        if (listAdapter == null) {
            return;
        }
        listAdapter.notifyItemRangeChanged(statusRow, 4);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.zgProxyStateChanged || id == NotificationCenter.proxySettingsChanged) {
            updateRows();
        }
    }

    private String statusText() {
        if (!controller.isNativeAvailable()) {
            return getString(R.string.ZgStatusUnavailable);
        }
        if (controller.isBusy()) {
            return getString(R.string.ZgStatusStarting);
        }
        if (controller.isRunning()) {
            return LocaleController.formatString(R.string.ZgStatusRunning, ZgProxyController.LOCAL_HOST + ":" + controller.getBoundPort());
        }
        if (!TextUtils.isEmpty(controller.getLastError())) {
            return getString(R.string.ZgStatusError);
        }
        return getString(R.string.ZgStatusStopped);
    }

    private String statsText() {
        long[] stats = controller.getStats();
        if (stats == null) {
            return "—";
        }
        return LocaleController.formatString(R.string.ZgStatsValue, (int) stats[0], AndroidUtilities.formatFileSize(stats[1]), AndroidUtilities.formatFileSize(stats[2]));
    }

    private String linkValueText() {
        String url = controller.getVlessUrl();
        if (TextUtils.isEmpty(url)) {
            return getString(R.string.ZgVlessLinkNotSet);
        }
        // never show the uuid in the list: only host:port after '@'
        int at = url.indexOf('@');
        int q = url.indexOf('?');
        if (at > 0) {
            return url.substring(at + 1, q > at ? q : url.length());
        }
        return "vless://…";
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private static final int VIEW_TYPE_SHADOW = 0;
        private static final int VIEW_TYPE_TEXT_SETTING = 1;
        private static final int VIEW_TYPE_HEADER = 2;
        private static final int VIEW_TYPE_TEXT_CHECK = 3;
        private static final int VIEW_TYPE_INFO = 4;

        public static final int PAYLOAD_CHECKED_CHANGED = 0;

        private final Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == enableRow || position == linkRow || position == fragmentRow || position == portRow;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_SHADOW:
                    view = new ShadowSectionCell(mContext);
                    break;
                case VIEW_TYPE_TEXT_SETTING:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_TEXT_CHECK:
                    view = new TextCheckCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_INFO:
                default:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_TEXT_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == enableRow) {
                        cell.setTextAndCheck(getString(R.string.ZgEnable), controller.isEnabled(), false);
                    }
                    break;
                }
                case VIEW_TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == settingsHeaderRow) {
                        cell.setText(getString(R.string.ZgProxy));
                    } else if (position == statusHeaderRow) {
                        cell.setText(getString(R.string.ZgStatus));
                    }
                    break;
                }
                case VIEW_TYPE_TEXT_SETTING: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    if (position == linkRow) {
                        cell.setTextAndValue(getString(R.string.ZgVlessLink), linkValueText(), false);
                    } else if (position == fragmentRow) {
                        cell.setTextAndValue(getString(R.string.ZgFragmentSpec), controller.getFragmentSpec(), false);
                    } else if (position == portRow) {
                        int port = controller.getListenPort();
                        cell.setTextAndValue(getString(R.string.ZgListenPort), port > 0 ? String.valueOf(port) : getString(R.string.ZgListenPortAuto), false);
                    } else if (position == statusRow) {
                        cell.setTextAndValue(getString(R.string.ZgStatus), statusText(), true);
                    } else if (position == routingRow) {
                        cell.setTextAndValue(getString(R.string.ZgRouting), getString(controller.isTelegramRoutedThroughZg() ? R.string.ZgRoutingThroughZg : R.string.ZgRoutingDirect), true);
                    } else if (position == versionRow) {
                        String version = controller.getVersion();
                        cell.setTextAndValue(getString(R.string.ZgVersion), TextUtils.isEmpty(version) ? "—" : version, true);
                    } else if (position == statsRow) {
                        cell.setTextAndValue(getString(R.string.ZgStats), statsText(), false);
                    }
                    break;
                }
                case VIEW_TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == enableInfoRow) {
                        cell.setText(getString(R.string.ZgInfo));
                    } else if (position == linkInfoRow) {
                        cell.setText(getString(R.string.ZgVlessLinkInfo));
                    } else if (position == fragmentInfoRow) {
                        cell.setText(getString(R.string.ZgFragmentSpecInfo));
                    } else if (position == portInfoRow) {
                        cell.setText(getString(R.string.ZgListenPortInfo));
                    } else if (position == errorInfoRow) {
                        String error = controller.isNativeAvailable() ? controller.getLastError() : controller.getNativeLoadError();
                        cell.setText(LocaleController.formatString(R.string.ZgLastError, error));
                    }
                    break;
                }
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position, java.util.List payloads) {
            if (holder.getItemViewType() == VIEW_TYPE_TEXT_CHECK && payloads.contains(PAYLOAD_CHECKED_CHANGED)) {
                ((TextCheckCell) holder.itemView).setChecked(controller.isEnabled());
            } else {
                super.onBindViewHolder(holder, position, payloads);
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == enableRow) {
                return VIEW_TYPE_TEXT_CHECK;
            } else if (position == settingsHeaderRow || position == statusHeaderRow) {
                return VIEW_TYPE_HEADER;
            } else if (position == linkRow || position == fragmentRow || position == portRow || position == statusRow || position == routingRow || position == versionRow || position == statsRow) {
                return VIEW_TYPE_TEXT_SETTING;
            } else if (position == statusShadowRow) {
                return VIEW_TYPE_SHADOW;
            }
            return VIEW_TYPE_INFO;
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{TextSettingsCell.class, TextCheckCell.class, HeaderCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));

        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrack));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrackChecked));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{TextInfoPrivacyCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));

        return themeDescriptions;
    }
}
