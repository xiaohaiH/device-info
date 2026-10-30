package com.gathin.deviceinfo;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Framework-only UI for Android API 16+: no Material theme, vectors, or AndroidX required. */
public final class MainActivity extends Activity {
    private static final String LAST_RESULT = "settings_result";
    // Keep the saved-state key compatible; it now includes completed-but-blocking outcomes.
    private static final String REBOOT_BLOCKED = "reboot_in_progress";
    private static final RebootSession REBOOT_SESSION = new RebootSession();
    private static final Executor REBOOT_EXECUTOR = new Executor() {
        @Override public void execute(Runnable work) { new Thread(work, "device-reboot").start(); }
    };
    private TextView report;
    private ScrollView scroll;
    private AndroidSettingsAccess settings;
    private AndroidSettingsAccess runningServices;
    private RebootController rebooter;
    private AndroidRebootAccess rebootAccess;
    private Button rebootButton;
    private AlertDialog rebootConfirmation;
    private long rebootConsent;
    private boolean destroyed;
    private boolean compactActions;
    private boolean resumed;
    private String lastResult;
    private RebootController.Result displayedRebootResult;
    private final Runnable rebootObserver = new Runnable() {
        @Override public void run() {
            if (!resumed || destroyed) return;
            // Read busy first: if completion races this poll, another poll is still scheduled.
            boolean inProgress = rebooter.isInProgress();
            RebootController.Result result = rebooter.result();
            if (result != null && result != displayedRebootResult) {
                displayedRebootResult = result;
                showResult(result.requested, result.message, R.string.reboot_result);
            }
            if (inProgress) report.postDelayed(this, 250);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) lastResult = savedInstanceState.getString(LAST_RESULT);
        settings = new AndroidSettingsAccess(this);
        runningServices = new AndroidSettingsAccess(this, AndroidSettingsAccess.Destination.RUNNING_SERVICES);
        rebootAccess = new AndroidRebootAccess(this);
        boolean restoredBlocked = savedInstanceState != null && savedInstanceState.getBoolean(REBOOT_BLOCKED, false);
        rebooter = REBOOT_SESSION.attach(rebootAccess, restoredBlocked);
        Object retained = getLastNonConfigurationInstance();
        if (retained instanceof RebootState && ((RebootState) retained).controller == rebooter) {
            displayedRebootResult = ((RebootState) retained).displayedResult;
        } else if (restoredBlocked && rebooter.blocksRequests()) {
            lastResult = getString(R.string.reboot_state_unknown);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 23, 32));
        int padding = dp(20);
        root.setPadding(padding, padding, padding, padding);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 21) Api21.applyInsets(root, padding);

        root.addView(text(getString(R.string.title), 25, Color.WHITE), matchWrap());
        root.addView(text(getString(R.string.hint), 14, Color.LTGRAY), matchWrap());

        compactActions = getResources().getConfiguration().screenWidthDp < 600;
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row = matchWrap();
        row.setMargins(0, dp(12), 0, dp(12));
        root.addView(actions, row);
        LinearLayout secondaryActions = actions;
        if (compactActions) {
            secondaryActions = new LinearLayout(this);
            secondaryActions.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams secondRow = matchWrap();
            secondRow.bottomMargin = dp(12);
            root.addView(secondaryActions, secondRow);
        }
        Button open = button(R.string.open_settings, R.id.settings_button);
        Button taskManager = button(R.string.open_task_manager, R.id.task_manager_button);
        Button refresh = button(R.string.refresh, R.id.refresh_button);
        rebootButton = button(R.string.reboot_device, R.id.reboot_button);
        rebootButton.setBackgroundResource(R.drawable.reboot_button);
        rebootButton.setTextColor(getResources().getColorStateList(R.color.reboot_button_text));
        LinearLayout.LayoutParams openSize = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f);
        openSize.rightMargin = dp(10);
        actions.addView(open, openSize);
        LinearLayout.LayoutParams taskManagerSize = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f);
        taskManagerSize.rightMargin = compactActions ? 0 : dp(10);
        actions.addView(taskManager, taskManagerSize);
        LinearLayout.LayoutParams refreshSize = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        refreshSize.rightMargin = dp(10);
        secondaryActions.addView(refresh, refreshSize);
        secondaryActions.addView(rebootButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout appActions = new LinearLayout(this);
        appActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams appRow = matchWrap();
        appRow.bottomMargin = dp(12);
        root.addView(appActions, appRow);
        Button installApk = button(R.string.install_apk, R.id.install_apk_button);
        Button exitApp = button(R.string.exit_app, R.id.exit_app_button);
        LinearLayout.LayoutParams installSize = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        installSize.rightMargin = dp(10);
        appActions.addView(installApk, installSize);
        appActions.addView(exitApp, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        installApk.setNextFocusUpId(compactActions ? R.id.refresh_button : R.id.settings_button);
        installApk.setNextFocusDownId(R.id.report_scroll);
        installApk.setNextFocusRightId(R.id.exit_app_button);
        exitApp.setNextFocusLeftId(R.id.install_apk_button);
        exitApp.setNextFocusDownId(R.id.report_scroll);
        exitApp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { finishAffinity(); }
        });
        installApk.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                try { startActivity(new Intent(MainActivity.this, ApkInstallActivity.class)); }
                catch (RuntimeException failure) {
                    showResult(false, getString(R.string.apk_open_failed, failure.getClass().getSimpleName()), R.string.install_apk);
                }
            }
        });

        scroll = new ScrollView(this);
        scroll.setId(R.id.report_scroll);
        scroll.setFocusable(true);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setNextFocusUpId(R.id.install_apk_button);
        report = text("", 18, Color.rgb(226, 235, 242));
        report.setPadding(dp(4), dp(8), dp(12), dp(20));
        scroll.addView(report, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(text(getString(R.string.privacy), 12, Color.LTGRAY), matchWrap());

        open.setNextFocusRightId(R.id.task_manager_button);
        taskManager.setNextFocusLeftId(R.id.settings_button);
        taskManager.setNextFocusRightId(compactActions ? View.NO_ID : R.id.refresh_button);
        refresh.setNextFocusLeftId(compactActions ? View.NO_ID : R.id.task_manager_button);
        rebootButton.setNextFocusLeftId(R.id.refresh_button);
        open.setNextFocusDownId(compactActions ? R.id.refresh_button : R.id.install_apk_button);
        refresh.setNextFocusDownId(compactActions ? R.id.install_apk_button : R.id.exit_app_button);
        rebootButton.setNextFocusDownId(R.id.exit_app_button);
        if (compactActions) {
            refresh.setNextFocusUpId(R.id.settings_button);
            rebootButton.setNextFocusUpId(R.id.task_manager_button);
        }
        open.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { openSettings(); }
        });
        taskManager.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { openTaskManager(); }
        });
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                lastResult = null;
                refreshReport();
                scroll.smoothScrollTo(0, 0);
            }
        });
        rebootButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showRebootOptions(); }
        });
        refreshReport();
        open.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        refreshReport(); // Recheck permission after returning; never executes a reboot.
        rebootObserver.run();
    }

    @Override protected void onPause() {
        resumed = false;
        report.removeCallbacks(rebootObserver);
        super.onPause();
    }

    @Override public Object onRetainNonConfigurationInstance() {
        rebooter.cancelConfirmation(rebootConsent); // Only discard this window's unconfirmed consent.
        rebootConsent = 0;
        return new RebootState(rebooter, displayedRebootResult);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        report.removeCallbacks(rebootObserver);
        rebooter.cancelConfirmation(rebootConsent);
        if (rebootConfirmation != null) rebootConfirmation.dismiss();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString(LAST_RESULT, lastResult);
        state.putBoolean(REBOOT_BLOCKED, rebooter.blocksRequests());
    }

    private void openSettings() {
        showResult(SettingsNavigator.open(settings), R.string.settings_result);
    }

    private void openTaskManager() {
        showResult(RunningServicesNavigator.open(runningServices), R.string.task_manager_result);
    }

    private void showRebootOptions() {
        if (!rebooter.canOpenOptions() || (rebootConfirmation != null && rebootConfirmation.isShowing())) return;
        final List<Integer> actions = new ArrayList<>();
        List<CharSequence> labels = new ArrayList<>();
        if (rebooter.canRequest()) {
            actions.add(0);
            labels.add(getString(R.string.reboot_auto));
        }
        actions.add(1);
        labels.add(getString(R.string.reboot_accessibility_setup));
        actions.add(2);
        labels.add(getString(R.string.reboot_vendor_settings));
        if (rebooter.canRequest(RebootController.Action.APP_RESTART)) {
            actions.add(3);
            labels.add(getString(R.string.app_restart_option));
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reboot_options)
                .setItems(labels.toArray(new CharSequence[labels.size()]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (rebootConfirmation == dialog) rebootConfirmation = null;
                        switch (actions.get(which)) {
                            case 0: confirmReboot(RebootController.Action.DEVICE_REBOOT); break;
                            case 1: explainAccessibility(); break;
                            case 2:
                                ProgressiveReboot.Attempt result = new AndroidRebootPages(MainActivity.this).openVendor();
                                showResult(result.status == ProgressiveReboot.Status.ASSISTANCE_OPENED,
                                        result.message, R.string.reboot_result);
                                break;
                            case 3: confirmReboot(RebootController.Action.APP_RESTART); break;
                            default: break;
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        showRebootDialog(dialog);
    }

    private void explainAccessibility() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reboot_accessibility_setup)
                .setMessage(R.string.reboot_accessibility_explain)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.open_accessibility_settings, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        RebootController.Result result = new AndroidRebootPages(MainActivity.this).openAccessibilitySettings();
                        showResult(result.requested, result.message, R.string.reboot_result);
                    }
                })
                .create();
        showRebootDialog(dialog);
    }

    private void confirmReboot(RebootController.Action action) {
        if ((rebootConfirmation != null && rebootConfirmation.isShowing()) || !rebooter.requestConfirmation(action)) {
            showResult(false, getString(R.string.reboot_unavailable), R.string.reboot_result);
            return;
        }
        final long consent = rebooter.confirmationToken();
        rebootConsent = consent;
        refreshReport();
        boolean appOnly = action == RebootController.Action.APP_RESTART;
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(appOnly ? R.string.app_restart_confirm_title : R.string.reboot_confirm_title)
                .setMessage(appOnly ? R.string.app_restart_confirm_message : R.string.reboot_confirm_message)
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { cancelReboot(consent); }
                })
                .setPositiveButton(appOnly ? R.string.app_restart_confirm : R.string.reboot_confirm, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { executeReboot(consent); }
                })
                .create();
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override public void onCancel(DialogInterface dialog) { cancelReboot(consent); }
        });
        showRebootDialog(dialog);
    }

    private void showRebootDialog(final AlertDialog dialog) {
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface ignored) {
                if (rebootConfirmation == dialog) rebootConfirmation = null;
            }
        });
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) {
                Button positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                if (positive != null) positive.setTextColor(Color.rgb(209, 163, 168));
                Button cancel = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
                cancel.setFocusableInTouchMode(true);
                cancel.requestFocus();
            }
        });
        rebootConfirmation = dialog;
        dialog.show();
    }

    private void cancelReboot(long consent) {
        rebooter.cancelConfirmation(consent);
        if (rebootConsent == consent) rebootConsent = 0;
        refreshReport();
    }

    private void executeReboot(long consent) {
        // Consume only this dialog's consent; worker never captures this Activity.
        if (!rebooter.confirm(consent, REBOOT_EXECUTOR)) {
            refreshReport();
            return;
        }
        if (rebootConsent == consent) rebootConsent = 0;
        lastResult = getString(R.string.reboot_requesting);
        refreshReport();
        scroll.smoothScrollTo(0, 0);
        report.removeCallbacks(rebootObserver);
        rebootObserver.run();
    }

    private void showResult(SettingsNavigator.Result result, int title) {
        showResult(result.opened, result.message, title);
    }

    private void showResult(boolean requested, String message, int title) {
        lastResult = message;
        refreshReport();
        scroll.smoothScrollTo(0, 0);
        if (!requested) {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(R.string.close, null)
                    .show();
        }
    }

    private void refreshReport() {
        boolean rebootEnabled = rebooter.canOpenOptions();
        boolean direct = rebootAccess.hasDirectRoute();
        rebootButton.setText(direct ? R.string.reboot_device : R.string.reboot_options);
        rebootButton.setBackgroundResource(!rebootEnabled || direct ? R.drawable.reboot_button : R.drawable.action_button);
        boolean hadRebootFocus = rebootButton.hasFocus();
        rebootButton.setEnabled(rebootEnabled);
        rebootButton.setFocusable(rebootEnabled);
        View refresh = findViewById(R.id.refresh_button);
        refresh.setNextFocusRightId(rebootEnabled ? R.id.reboot_button : View.NO_ID);
        findViewById(R.id.task_manager_button).setNextFocusDownId(compactActions
                ? (rebootEnabled ? R.id.reboot_button : R.id.refresh_button) : R.id.install_apk_button);
        findViewById(R.id.exit_app_button).setNextFocusUpId(rebootEnabled ? R.id.reboot_button : R.id.refresh_button);
        if (!rebootEnabled && hadRebootFocus) refresh.requestFocus();
        String result = lastResult == null ? "" : lastResult + "\n\n──────────\n\n";
        report.setText(result + DeviceReport.render(DeviceReader.read(this), Build.VERSION.SDK_INT)
                + "\n\n──────────\n\n" + rebooter.describe()
                + "\n\n──────────\n\n" + SettingsNavigator.describe(settings)
                + "\n\n──────────\n\n" + RunningServicesNavigator.describe(runningServices));
    }

    private TextView text(String content, int size, int color) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(int label, int id) {
        Button button = new Button(this);
        button.setId(id);
        button.setText(label);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setMinHeight(dp(54));
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setBackgroundResource(R.drawable.action_button);
        button.setFocusable(true);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** Per-window result identity; the execution gate itself belongs to the process-wide session. */
    private static final class RebootState {
        final RebootController controller;
        final RebootController.Result displayedResult;
        RebootState(RebootController controller, RebootController.Result displayedResult) {
            this.controller = controller;
            this.displayedResult = displayedResult;
        }
    }

    /** Android 15 enforces edge-to-edge at target 35; keep controls clear of system bars. */
    @TargetApi(21)
    private static final class Api21 {
        static void applyInsets(final View root, final int padding) {
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                    view.setPadding(padding + insets.getSystemWindowInsetLeft(),
                            padding + insets.getSystemWindowInsetTop(),
                            padding + insets.getSystemWindowInsetRight(),
                            padding + insets.getSystemWindowInsetBottom());
                    return insets;
                }
            });
            root.requestApplyInsets();
        }
    }
}
