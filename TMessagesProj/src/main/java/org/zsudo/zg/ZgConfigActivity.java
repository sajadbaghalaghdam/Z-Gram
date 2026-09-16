/*
 * ZG: add/edit one VLESS/REALITY server, the Xray counterpart of ProxySettingsActivity.
 *
 * Reached from "Add Proxy" in the proxy list (new server) and from the (i) button of an Xray row
 * (existing server). Saving a new server also makes it the active proxy, exactly like adding a
 * SOCKS5 or MTProto proxy does.
 */

package org.zsudo.zg;

import static org.telegram.messenger.LocaleController.getString;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

public class ZgConfigActivity extends BaseFragment {

    private static final int done_button = 1;

    private static final int FIELD_LINK = 0;
    private static final int FIELD_NAME = 1;
    private static final int FIELD_FRAGMENT = 2;

    private ScrollView scrollView;
    private LinearLayout linearLayout2;
    private LinearLayout inputFieldsContainer;
    private EditTextBoldCursor[] inputFields;
    private ShadowSectionCell[] sectionCell = new ShadowSectionCell[2];
    private TextInfoPrivacyCell bottomCell;
    private TextSettingsCell pasteCell;
    private TextSettingsCell deleteCell;
    private ActionBarMenuItem doneItem;

    private ClipboardManager clipboardManager;
    private String pasteString;
    private String pasteLink;

    private final ZgProxyController controller = ZgProxyController.getInstance();
    private final ZgConfig currentConfig;
    private final boolean addingNewConfig;
    private boolean applying;

    public ZgConfigActivity() {
        super();
        currentConfig = new ZgConfig(0, "", "", ZgProxyController.getInstance().getDefaultFragmentSpec());
        addingNewConfig = true;
    }

    public ZgConfigActivity(ZgConfig config) {
        super();
        currentConfig = config;
        addingNewConfig = false;
    }

    private final ClipboardManager.OnPrimaryClipChangedListener clipChangedListener = this::updatePasteCell;

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
        clipboardManager.addPrimaryClipChangedListener(clipChangedListener);
        updatePasteCell();
    }

    @Override
    public void onPause() {
        super.onPause();
        clipboardManager.removePrimaryClipChangedListener(clipChangedListener);
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle(getString(R.string.ZgProxyTitle));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        if (parentLayout != null && parentLayout.isLayersLayout()) {
            actionBar.setOccupyStatusBar(false);
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == done_button) {
                    saveConfig();
                }
            }
        });

        doneItem = actionBar.createMenu().addItemWithWidth(done_button, R.drawable.ic_ab_done, AndroidUtilities.dp(56));
        doneItem.setContentDescription(getString(R.string.Done));

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        AndroidUtilities.setScrollViewEdgeEffectColor(scrollView, Theme.getColor(Theme.key_actionBarDefault));
        frameLayout.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        linearLayout2 = new LinearLayout(context);
        linearLayout2.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(linearLayout2, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pasteCell = new TextSettingsCell(context);
        pasteCell.setBackground(Theme.getSelectorDrawable(true));
        pasteCell.setText(getString(R.string.PasteFromClipboard), false);
        pasteCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        pasteCell.setOnClickListener(v -> {
            if (TextUtils.isEmpty(pasteLink)) {
                return;
            }
            inputFields[FIELD_LINK].setText(pasteLink);
            inputFields[FIELD_LINK].setSelection(inputFields[FIELD_LINK].length());
            AndroidUtilities.hideKeyboard(inputFieldsContainer.findFocus());
        });
        pasteCell.setVisibility(View.GONE);
        linearLayout2.addView(pasteCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        sectionCell[0] = new ShadowSectionCell(context);
        sectionCell[0].setVisibility(View.GONE);
        linearLayout2.addView(sectionCell[0], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        inputFieldsContainer = new LinearLayout(context);
        inputFieldsContainer.setOrientation(LinearLayout.VERTICAL);
        inputFieldsContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        linearLayout2.addView(inputFieldsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        inputFields = new EditTextBoldCursor[3];
        for (int a = 0; a < inputFields.length; a++) {
            FrameLayout container = new FrameLayout(context);
            inputFieldsContainer.addView(container, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));

            inputFields[a] = new EditTextBoldCursor(context);
            inputFields[a].setTag(a);
            inputFields[a].setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            inputFields[a].setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
            inputFields[a].setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            inputFields[a].setBackground(null);
            inputFields[a].setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            inputFields[a].setCursorSize(AndroidUtilities.dp(20));
            inputFields[a].setCursorWidth(1.5f);
            inputFields[a].setSingleLine(true);
            inputFields[a].setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            inputFields[a].setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
            inputFields[a].setTransformHintToHeader(true);
            inputFields[a].setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
            if (a == FIELD_LINK) {
                inputFields[a].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI);
            } else {
                inputFields[a].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            }
            inputFields[a].setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            switch (a) {
                case FIELD_LINK:
                    inputFields[a].setHintText(getString(R.string.ZgLink));
                    inputFields[a].setText(currentConfig.uri);
                    break;
                case FIELD_NAME:
                    inputFields[a].setHintText(getString(R.string.ZgConfigName));
                    inputFields[a].setText(currentConfig.name);
                    break;
                case FIELD_FRAGMENT:
                    inputFields[a].setHintText(getString(R.string.ZgFragmentSpec));
                    inputFields[a].setText(currentConfig.fragmentSpec);
                    break;
            }
            inputFields[a].setSelection(inputFields[a].length());
            inputFields[a].setPadding(0, 0, 0, 0);
            container.addView(inputFields[a], LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 17, a == FIELD_LINK ? 12 : 0, 17, 0));

            inputFields[a].setOnEditorActionListener((textView, i, keyEvent) -> {
                if (i == EditorInfo.IME_ACTION_NEXT) {
                    int num = (Integer) textView.getTag();
                    if (num + 1 < inputFields.length) {
                        inputFields[num + 1].requestFocus();
                    }
                    return true;
                } else if (i == EditorInfo.IME_ACTION_DONE) {
                    saveConfig();
                    return true;
                }
                return false;
            });
        }

        bottomCell = new TextInfoPrivacyCell(context);
        bottomCell.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
        bottomCell.setText(getString(R.string.ZgLinkInfo));
        linearLayout2.addView(bottomCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (!addingNewConfig) {
            deleteCell = new TextSettingsCell(context);
            deleteCell.setBackground(Theme.getSelectorDrawable(true));
            deleteCell.setText(getString(R.string.DeleteProxyTitle), false);
            deleteCell.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
            deleteCell.setOnClickListener(v -> confirmDelete());
            linearLayout2.addView(deleteCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            sectionCell[1] = new ShadowSectionCell(context);
            sectionCell[1].setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
            linearLayout2.addView(sectionCell[1], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        pasteString = null;
        pasteLink = null;
        updatePasteCell();

        return fragmentView;
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        if (isOpen && !backward && addingNewConfig) {
            inputFields[FIELD_LINK].requestFocus();
            AndroidUtilities.showKeyboard(inputFields[FIELD_LINK]);
        }
    }

    private void updatePasteCell() {
        if (pasteCell == null || clipboardManager == null) {
            return;
        }
        final ClipData clip = clipboardManager.getPrimaryClip();
        String clipText;
        if (clip != null && clip.getItemCount() > 0) {
            try {
                clipText = clip.getItemAt(0).coerceToText(fragmentView.getContext()).toString();
            } catch (Exception e) {
                clipText = null;
            }
        } else {
            clipText = null;
        }
        if (TextUtils.equals(clipText, pasteString)) {
            return;
        }
        pasteString = clipText;
        pasteLink = null;
        if (clipText != null) {
            int index = clipText.indexOf("vless://");
            if (index >= 0) {
                String link = clipText.substring(index).trim();
                int space = link.indexOf('\n');
                if (space > 0) {
                    link = link.substring(0, space).trim();
                }
                if (ZgConfig.isValidUri(link)) {
                    pasteLink = link;
                }
            }
        }
        boolean visible = pasteLink != null;
        pasteCell.setVisibility(visible ? View.VISIBLE : View.GONE);
        sectionCell[0].setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Greys the done button out while the core is being started with the new settings. */
    private void checkDone() {
        if (doneItem == null) {
            return;
        }
        doneItem.setEnabled(!applying);
        doneItem.setAlpha(applying ? 0.5f : 1f);
    }

    private void showError(String text) {
        if (getParentActivity() == null) {
            return;
        }
        showDialog(AlertsCreator.createSimpleAlert(getParentActivity(), getString(R.string.ZgProxyTitle), text).create());
    }

    private void saveConfig() {
        if (getParentActivity() == null || applying) {
            return;
        }
        final String link = inputFields[FIELD_LINK].getText().toString().trim();
        final int error = ZgConfig.checkUri(link);
        if (error != 0) {
            showError(getString(error));
            return;
        }
        String name = inputFields[FIELD_NAME].getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            name = ZgConfig.nameFromUri(link);
        }
        String spec = inputFields[FIELD_FRAGMENT].getText().toString().trim();
        if (TextUtils.isEmpty(spec)) {
            spec = ZgProxyController.DEFAULT_FRAGMENT_SPEC;
        }

        currentConfig.name = name;
        currentConfig.uri = link;
        currentConfig.fragmentSpec = spec;
        controller.addOrUpdateConfig(currentConfig);

        final boolean shouldConnect = addingNewConfig || controller.getActiveConfigId() == currentConfig.id;
        if (!shouldConnect) {
            finishFragment();
            return;
        }

        AndroidUtilities.hideKeyboard(inputFieldsContainer.findFocus());
        applying = true;
        checkDone();
        controller.activate(currentConfig, (running, activateError) -> {
            applying = false;
            checkDone();
            if (running) {
                finishFragment();
            } else {
                showError(TextUtils.isEmpty(activateError) ? getString(R.string.ZgStatusError) : LocaleController.formatString(R.string.ZgLastError, activateError));
            }
        });
    }

    private void confirmDelete() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.DeleteProxyTitle));
        builder.setMessage(getString(R.string.DeleteProxyConfirm));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
            controller.deleteConfig(currentConfig.id, null);
            finishFragment();
        });
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        final ThemeDescription.ThemeDescriptionDelegate delegate = () -> {
            if (inputFields != null) {
                for (int a = 0; a < inputFields.length; a++) {
                    inputFields[a].setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                            Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                            Theme.getColor(Theme.key_text_RedRegular));
                }
            }
        };
        ArrayList<ThemeDescription> arrayList = new ArrayList<>();
        arrayList.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        arrayList.add(new ThemeDescription(scrollView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        arrayList.add(new ThemeDescription(inputFieldsContainer, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(linearLayout2, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));

        arrayList.add(new ThemeDescription(pasteCell, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(pasteCell, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_listSelector));
        arrayList.add(new ThemeDescription(pasteCell, 0, new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueText4));

        if (deleteCell != null) {
            arrayList.add(new ThemeDescription(deleteCell, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_windowBackgroundWhite));
            arrayList.add(new ThemeDescription(deleteCell, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_listSelector));
            arrayList.add(new ThemeDescription(deleteCell, 0, new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_text_RedRegular));
        }

        if (inputFields != null) {
            for (int a = 0; a < inputFields.length; a++) {
                arrayList.add(new ThemeDescription(inputFields[a], ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
                arrayList.add(new ThemeDescription(inputFields[a], ThemeDescription.FLAG_HINTTEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteHintText));
                arrayList.add(new ThemeDescription(inputFields[a], ThemeDescription.FLAG_HINTTEXTCOLOR | ThemeDescription.FLAG_PROGRESSBAR, null, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
                arrayList.add(new ThemeDescription(inputFields[a], ThemeDescription.FLAG_CURSORCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
                arrayList.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhiteInputField));
                arrayList.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhiteInputFieldActivated));
                arrayList.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_text_RedRegular));
            }
        }
        for (int a = 0; a < sectionCell.length; a++) {
            if (sectionCell[a] != null) {
                arrayList.add(new ThemeDescription(sectionCell[a], ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{ShadowSectionCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
            }
        }
        arrayList.add(new ThemeDescription(bottomCell, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{TextInfoPrivacyCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        arrayList.add(new ThemeDescription(bottomCell, 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));

        return arrayList;
    }
}
