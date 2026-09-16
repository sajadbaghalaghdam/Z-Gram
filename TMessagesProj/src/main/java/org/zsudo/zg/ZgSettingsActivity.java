/*
 * ZG: status and tunnel settings of the built-in VLESS/REALITY core.
 * Reachable from Settings -> Data and Storage -> Proxy Settings -> "ZG Proxy".
 *
 * The servers themselves live in the proxy list (ZgConfigActivity adds and edits them); this
 * screen only shows what the core is doing and the settings that are not per-server.
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
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
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
    private int statusHeaderRow;
    private int serverRow;
    private int statusRow;
    private int routingRow;
    private int versionRow;
    private int statsRow;
    private int statusShadowRow;
    private int errorInfoRow;
    private int tunnelHeaderRow;
    private int fragmentRow;
    private int fragmentInfoRow;
    private int portRow;
    private int portInfoRow;
    private int batteryHeaderRow;
    private int refreshRateRow;
    private int refreshRateInfoRow;
    private int batteryDefaultsInfoRow;

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
            if (position == fragmentRow) {
                editFragmentSpec();
            } else if (position == portRow) {
                editPort();
            } else if (position == refreshRateRow) {
                chooseRefreshRate();
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

    private void restartAfterSettingsChange() {
        controller.restartActive((running, error) -> {
            if (listAdapter == null) {
                return;
            }
            updateRows();
            if (!running && !TextUtils.isEmpty(error)) {
                showError(getString(R.string.ZgStartFailed), error);
            }
        });
    }

    private void editFragmentSpec() {
        if (getParentActivity() == null) {
            return;
        }
        AlertsCreator.createSimpleTextInputAlert(getParentActivity(), this, getString(R.string.ZgFragmentSpec), getString(R.string.ZgFragmentSpecInfo), ZgProxyController.DEFAULT_FRAGMENT_SPEC, controller.getActiveFragmentSpec(), 128, getString(R.string.Save), getResourceProvider(), text -> {
            controller.setActiveFragmentSpec(text);
            listAdapter.notifyItemChanged(fragmentRow);
            restartAfterSettingsChange();
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
            restartAfterSettingsChange();
        });
    }

    private void chooseRefreshRate() {
        if (getParentActivity() == null) {
            return;
        }
        String[] options = new String[]{getString(R.string.ZgRefreshRateAdaptive), getString(R.string.ZgRefreshRateMax)};
        showDialog(AlertsCreator.createSingleChoiceDialog(getParentActivity(), options, getString(R.string.ZgRefreshRate), SharedConfig.zgRefreshRateMode, (dialog, which) -> {
            SharedConfig.setZgRefreshRateMode(which == 1 ? SharedConfig.ZG_REFRESH_RATE_MAX : SharedConfig.ZG_REFRESH_RATE_ADAPTIVE);
            if (getParentActivity() != null) {
                if (SharedConfig.zgRefreshRateMode == SharedConfig.ZG_REFRESH_RATE_MAX) {
                    AndroidUtilities.setPreferredMaxRefreshRate(getParentActivity().getWindow());
                } else {
                    AndroidUtilities.clearPreferredRefreshRate(getParentActivity().getWindow());
                }
            }
            if (listAdapter != null) {
                listAdapter.notifyItemChanged(refreshRateRow);
            }
        }));
    }

    private void updateRows() {
        rowCount = 0;
        statusHeaderRow = rowCount++;
        serverRow = rowCount++;
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
        tunnelHeaderRow = rowCount++;
        fragmentRow = rowCount++;
        fragmentInfoRow = rowCount++;
        portRow = rowCount++;
        portInfoRow = rowCount++;
        batteryHeaderRow = rowCount++;
        refreshRateRow = rowCount++;
        refreshRateInfoRow = rowCount++;
        batteryDefaultsInfoRow = rowCount++;
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
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

    private String serverText() {
        ZgConfig config = controller.getActiveConfig();
        return config == null ? getString(R.string.ZgServerNone) : config.getTitle();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private static final int VIEW_TYPE_SHADOW = 0;
        private static final int VIEW_TYPE_TEXT_SETTING = 1;
        private static final int VIEW_TYPE_HEADER = 2;
        private static final int VIEW_TYPE_INFO = 3;

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
            return position == fragmentRow || position == portRow || position == refreshRateRow;
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
                case VIEW_TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == statusHeaderRow) {
                        cell.setText(getString(R.string.ZgStatus));
                    } else if (position == tunnelHeaderRow) {
                        cell.setText(getString(R.string.ZgTunnel));
                    } else if (position == batteryHeaderRow) {
                        cell.setText(getString(R.string.ZgBattery));
                    }
                    break;
                }
                case VIEW_TYPE_TEXT_SETTING: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    if (position == serverRow) {
                        cell.setTextAndValue(getString(R.string.ZgServer), serverText(), true);
                    } else if (position == statusRow) {
                        cell.setTextAndValue(getString(R.string.ZgStatus), statusText(), true);
                    } else if (position == routingRow) {
                        cell.setTextAndValue(getString(R.string.ZgRouting), getString(controller.isTelegramRoutedThroughZg() ? R.string.ZgRoutingThroughZg : R.string.ZgRoutingDirect), true);
                    } else if (position == versionRow) {
                        String version = controller.getVersion();
                        cell.setTextAndValue(getString(R.string.ZgVersion), TextUtils.isEmpty(version) ? "—" : version, true);
                    } else if (position == statsRow) {
                        cell.setTextAndValue(getString(R.string.ZgStats), statsText(), false);
                    } else if (position == fragmentRow) {
                        cell.setTextAndValue(getString(R.string.ZgFragmentSpec), controller.getActiveFragmentSpec(), false);
                    } else if (position == portRow) {
                        int port = controller.getListenPort();
                        cell.setTextAndValue(getString(R.string.ZgListenPort), port > 0 ? String.valueOf(port) : getString(R.string.ZgListenPortAuto), false);
                    } else if (position == refreshRateRow) {
                        cell.setTextAndValue(getString(R.string.ZgRefreshRate), getString(SharedConfig.zgRefreshRateMode == SharedConfig.ZG_REFRESH_RATE_MAX ? R.string.ZgRefreshRateMax : R.string.ZgRefreshRateAdaptive), false);
                    }
                    break;
                }
                case VIEW_TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == errorInfoRow) {
                        String error = controller.isNativeAvailable() ? controller.getLastError() : controller.getNativeLoadError();
                        cell.setText(LocaleController.formatString(R.string.ZgLastError, error));
                    } else if (position == fragmentInfoRow) {
                        cell.setText(getString(R.string.ZgFragmentSpecInfo));
                    } else if (position == portInfoRow) {
                        cell.setText(getString(R.string.ZgListenPortInfo));
                    } else if (position == refreshRateInfoRow) {
                        cell.setText(getString(R.string.ZgRefreshRateInfo));
                    } else if (position == batteryDefaultsInfoRow) {
                        cell.setText(getString(R.string.ZgBatteryDefaultsInfo));
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == statusHeaderRow || position == tunnelHeaderRow || position == batteryHeaderRow) {
                return VIEW_TYPE_HEADER;
            } else if (position == serverRow || position == statusRow || position == routingRow || position == versionRow || position == statsRow || position == fragmentRow || position == portRow || position == refreshRateRow) {
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

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{TextSettingsCell.class, HeaderCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
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

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{TextInfoPrivacyCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));

        return themeDescriptions;
    }
}
