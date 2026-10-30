package com.gathin.deviceinfo;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Non-exported, framework-only file selection and user-confirmed system installation. */
public final class ApkInstallActivity extends Activity {
    private static final int READ_PERMISSION = 62000;
    private static final int UNKNOWN_SOURCES = 62001;
    private static final ApkWorkQueue IO_WORK = new ApkWorkQueue(2, 2);
    private final Handler handler = new Handler();
    private AndroidApkAccess access;
    private State state;
    private Button pickButton, browseButton, installButton, resetButton, upButton;
    private TextView status, path;
    private ListView files;
    private LinearLayout browser;
    private ScrollView statusScroll;
    private AlertDialog permissionDialog;
    private BrowserTask displayedBrowser;
    private boolean resumed;
    private boolean destroyed;

    private final Runnable observer = new Runnable() {
        @Override public void run() {
            if (!resumed || destroyed) return;
            BrowserTask task = state.browser;
            boolean listing = task != null && task.result == null;
            // Read busy first so completion racing this observation receives another poll.
            boolean preparing = state.session.phase() == ApkInstallSession.Phase.PREPARING;
            if (task != null && task.result != null && task != displayedBrowser) {
                displayedBrowser = task;
                state.rows = task.result.files;
                if (task.result.error != null) state.notice = task.result.error;
                else if (state.rows.isEmpty()) state.notice = getString(R.string.apk_no_files);
                updateList();
            }
            render();
            if (listing || preparing) handler.postDelayed(this, 200);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        access = new AndroidApkAccess(this);
        Object retained = getLastNonConfigurationInstance();
        if (retained instanceof State) {
            state = (State) retained;
        } else {
            state = new State();
            if (savedInstanceState != null) {
                state.session = ApkInstallSession.restore(savedInstanceState.getString("phase"), savedInstanceState.getBoolean("result_contract"));
                state.pickerRequest = savedInstanceState.getInt("picker_request", -1);
                state.installerRequest = savedInstanceState.getInt("installer_request", -1);
                state.requestCounter = savedInstanceState.getInt("request_counter", 1000);
                state.notice = getString(R.string.apk_restored);
            }
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 23, 32));
        int padding = dp(16);
        root.setPadding(padding, padding, padding, padding);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 21) Api21.insets(root, padding);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button backButton = navigationButton(R.string.apk_return_home, R.id.apk_return_home);
        backButton.setContentDescription(getString(R.string.apk_return_home_description));
        header.addView(backButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = text(getString(R.string.install_apk), 23);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLayout = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleLayout.leftMargin = dp(12);
        header.addView(title, titleLayout);
        root.addView(header, wrap());
        root.addView(text(getString(R.string.apk_selection_hint), 13), wrap());

        LinearLayout choices = new LinearLayout(this);
        pickButton = button(R.string.apk_system_picker, R.id.apk_picker);
        browseButton = button(R.string.apk_local_browser, R.id.apk_browser);
        choices.addView(pickButton, weighted());
        choices.addView(browseButton, weighted());
        root.addView(choices, wrap());

        status = text("", 15);
        status.setPadding(0, dp(8), 0, dp(8));
        statusScroll = new ScrollView(this);
        statusScroll.setFocusable(true);
        statusScroll.addView(status, wrap());
        root.addView(statusScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        browser = new LinearLayout(this);
        browser.setOrientation(LinearLayout.VERTICAL);
        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setMinimumHeight(dp(48));
        path = text("", 13);
        path.setMaxLines(2);
        path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        navigation.addView(path, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        upButton = navigationButton(R.string.apk_parent, R.id.apk_up);
        upButton.setContentDescription(getString(R.string.apk_parent_description));
        LinearLayout.LayoutParams upSize = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        upSize.leftMargin = dp(8);
        navigation.addView(upButton, upSize);
        browser.addView(navigation, wrap());
        files = new ListView(this);
        files.setId(R.id.apk_files);
        browser.addView(files, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(browser, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout actions = new LinearLayout(this);
        resetButton = button(R.string.apk_reset, R.id.apk_reset);
        installButton = button(R.string.apk_launch_installer, R.id.apk_install);
        actions.addView(resetButton, weighted());
        actions.addView(installButton, weighted());
        root.addView(actions, wrap());
        backButton.setNextFocusDownId(R.id.apk_picker);
        pickButton.setNextFocusUpId(R.id.apk_return_home);
        browseButton.setNextFocusUpId(R.id.apk_return_home);
        pickButton.setNextFocusRightId(R.id.apk_browser);
        browseButton.setNextFocusLeftId(R.id.apk_picker);
        resetButton.setNextFocusRightId(R.id.apk_install);
        installButton.setNextFocusLeftId(R.id.apk_reset);
        upButton.setNextFocusUpId(R.id.apk_browser);
        upButton.setNextFocusDownId(R.id.apk_files);
        files.setNextFocusDownId(R.id.apk_reset);
        // Close this screen directly; folder navigation remains on the separate up/system-back paths.
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { finish(); }
        });
        pickButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { pick(); }
        });
        browseButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (!maySelect()) return;
                resetSelection();
                if (!requestReadIfNeeded()) browse(null, null);
            }
        });
        installButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { install(); }
        });
        resetButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                boolean wasInstalling = state.session.phase() == ApkInstallSession.Phase.INSTALLING;
                resetSelection();
                state.notice = getString(wasInstalling ? R.string.apk_wait_abandoned : R.string.apk_cancelled);
                render();
            }
        });
        upButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { parent(); }
        });
        files.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (!maySelect() || state.browser == null || state.browser.result == null || position >= state.rows.size()) return;
                ApkFiles.Entry selected = state.rows.get(position);
                if (state.root == null) browse(selected.file, selected.file);
                else if (selected.directory) browse(state.root, selected.file);
                else prepare(Uri.fromFile(selected.file));
            }
        });
        updateList();
        render();
        pickButton.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        handler.removeCallbacks(observer);
        observer.run(); // Observe only: no permission screen, picker or installer is launched here.
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(observer);
        super.onPause();
    }

    @Override public Object onRetainNonConfigurationInstance() { return state; }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("phase", state.session.phase().name());
        out.putBoolean("result_contract", state.session.expectsResult());
        out.putInt("picker_request", state.pickerRequest);
        out.putInt("installer_request", state.installerRequest);
        out.putInt("request_counter", state.requestCounter);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(observer);
        if (permissionDialog != null) permissionDialog.dismiss();
        releaseSelection(isChangingConfigurations() && !isFinishing()); // Also covers non-finishing system teardown.
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (state.showBrowser && state.directory != null && maySelect()) parent();
        else super.onBackPressed();
    }

    private boolean maySelect() {
        ApkInstallSession.Phase phase = state.session.phase();
        return phase == ApkInstallSession.Phase.IDLE || phase == ApkInstallSession.Phase.READY;
    }

    private void resetSelection() { releaseSelection(false); }

    private void releaseSelection(boolean retainedForConfiguration) {
        ApkInstallSession.Prepared previous = state.session.detach(retainedForConfiguration);
        if (retainedForConfiguration) return;
        cancelBrowserWork();
        cancelPrepareWork();
        state.pickerRequest = -1;
        state.installerRequest = -1;
        state.browser = null;
        state.showBrowser = false;
        state.rows = Collections.emptyList();
        access.discard(previous);
    }

    private void pick() {
        if (!maySelect()) return;
        resetSelection();
        state.notice = "";
        if (!state.session.pick()) return;
        state.pickerRequest = state.nextRequest();
        for (Intent intent : AndroidApkAccess.pickerIntents()) {
            try {
                startActivityForResult(intent, state.pickerRequest);
                render();
                return;
            } catch (ActivityNotFoundException | SecurityException unavailable) { /* Try older public picker contract. */ }
            catch (RuntimeException failure) {
                state.session.cancelPicker();
                state.pickerRequest = -1;
                state.notice = detail(failure);
                render();
                return;
            }
        }
        state.session.cancelPicker();
        state.pickerRequest = -1;
        if (!requestReadIfNeeded()) {
            browse(null, null);
            state.notice = getString(R.string.apk_picker_missing);
        }
        render();
    }

    private boolean requestReadIfNeeded() {
        try {
            if (!access.needsReadPermission()) return false;
            state.session.cancelPicker();
            state.notice = getString(R.string.apk_read_permission_hint);
            if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28) Api23.requestRead(this);
        } catch (RuntimeException failure) {
            state.session.cancelPicker();
            state.notice = detail(failure);
        }
        render();
        return true;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != READ_PERMISSION) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        state.notice = getString(granted ? R.string.apk_read_granted : R.string.apk_read_denied);
        render(); // The user chooses again. Granting permission never launches an installer.
    }

    private void browse(final File root, final File directory) {
        if (!maySelect()) return;
        if (Build.VERSION.SDK_INT < 26 && !state.legacyBrowserAccepted) {
            explainLegacyBrowser(root, directory);
            return;
        }
        cancelBrowserWork();
        state.notice = "";
        state.root = root;
        state.directory = directory;
        state.showBrowser = true;
        state.rows = Collections.emptyList();
        state.browser = new BrowserTask(access, root, directory);
        updateList();
        try { state.browserWork = IO_WORK.submit(state.browser); }
        catch (RuntimeException failure) { state.browser.result = new BrowseResult(Collections.<ApkFiles.Entry>emptyList(), detail(failure)); }
        observe();
    }

    private void explainLegacyBrowser(final File root, final File directory) {
        if (permissionDialog != null && permissionDialog.isShowing()) return;
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.apk_legacy_browser_title)
                .setMessage(R.string.apk_legacy_browser_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.apk_legacy_browser_continue, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface ignored, int which) {
                        if (destroyed || !resumed) return;
                        state.legacyBrowserAccepted = true;
                        browse(root, directory);
                    }
                }).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) {
                Button cancel = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
                cancel.setFocusableInTouchMode(true);
                cancel.requestFocus();
            }
        });
        permissionDialog = dialog;
        dialog.show();
    }

    private void parent() {
        if (state.directory == null || !maySelect()) return;
        if (state.directory.equals(state.root)) browse(null, null);
        else browse(state.root, state.directory.getParentFile());
    }

    private void prepare(Uri uri) {
        if ("file".equals(uri == null ? null : uri.getScheme()) && requestReadIfNeeded()) return;
        if (state.session.phase() == ApkInstallSession.Phase.READY) resetSelection();
        long ticket = state.session.prepare();
        if (ticket == 0) return;
        state.browser = null;
        state.showBrowser = false;
        state.notice = "";
        cancelBrowserWork();
        cancelPrepareWork();
        state.prepareTask = new PrepareTask(access, state.session, uri, ticket);
        try { state.prepareWork = IO_WORK.submit(state.prepareTask); }
        catch (RuntimeException failure) { state.session.failed(ticket, detail(failure)); }
        observe();
    }

    private void install() {
        if (state.session.phase() != ApkInstallSession.Phase.READY) return;
        try {
            if (!access.canInstallPackages()) { explainUnknownSources(); return; }
            AndroidApkAccess.Launch launch = access.installer(state.session.item());
            if (!state.session.launch(launch.returnsResult)) return;
            state.installerRequest = state.nextRequest();
            try {
                startActivityForResult(launch.intent, state.installerRequest);
                state.notice = getString(R.string.apk_installer_opened);
            } catch (RuntimeException failure) {
                state.installerRequest = -1;
                state.session.launchFailed(detail(failure));
            }
        } catch (IOException | RuntimeException failure) { state.notice = detail(failure); }
        render();
    }

    private void explainUnknownSources() {
        if (permissionDialog != null && permissionDialog.isShowing()) return;
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.apk_unknown_sources_title)
                .setMessage(R.string.apk_unknown_sources_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.apk_open_permission_settings, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (destroyed || !resumed) return;
                        try {
                            startActivityForResult(access.unknownSourceSettings(), UNKNOWN_SOURCES);
                            state.notice = getString(R.string.apk_permission_return);
                        } catch (IOException | RuntimeException failure) { state.notice = detail(failure); }
                        render();
                    }
                }).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) {
                Button cancel = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
                cancel.setFocusableInTouchMode(true);
                cancel.requestFocus();
            }
        });
        permissionDialog = dialog;
        dialog.show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == UNKNOWN_SOURCES) {
            state.notice = getString(state.session.phase() == ApkInstallSession.Phase.READY
                    ? R.string.apk_permission_return : R.string.apk_restored);
        } else if (requestCode == state.pickerRequest && state.session.phase() == ApkInstallSession.Phase.PICKING) {
            state.pickerRequest = -1;
            if (resultCode != RESULT_OK || data == null) {
                state.session.cancelPicker();
                state.notice = getString(R.string.apk_cancelled);
            } else if (data.getClipData() != null && data.getClipData().getItemCount() > 1) {
                state.session.cancelPicker();
                state.notice = getString(R.string.apk_single_file_only);
            } else {
                Uri uri = data.getData();
                if (uri == null && data.getClipData() != null && data.getClipData().getItemCount() == 1) uri = data.getClipData().getItemAt(0).getUri();
                prepare(uri);
            }
        } else if (requestCode == state.installerRequest && state.session.phase() == ApkInstallSession.Phase.INSTALLING) {
            state.installerRequest = -1;
            boolean success = state.session.expectsResult() && resultCode == RESULT_OK;
            // Unknown/cancelled OEM wrappers may return before their child finishes reading.
            // Retain those snapshots until expiry rather than deleting a potentially live file.
            if (success) access.discard(state.session.item());
            if (success) state.notice = getString(R.string.apk_install_success);
            else if (state.session.expectsResult() && resultCode == RESULT_FIRST_USER) {
                // The detailed EXTRA_INSTALL_RESULT key is not part of the public SDK.
                state.notice = getString(R.string.apk_install_failure);
            } else state.notice = getString(R.string.apk_install_unknown);
            state.session.finish();
        }
        observe();
    }

    private void observe() {
        handler.removeCallbacks(observer);
        if (resumed) observer.run(); else render();
    }

    private void updateList() {
        String[] labels = new String[state.rows.size()];
        for (int i = 0; i < labels.length; i++) {
            ApkFiles.Entry entry = state.rows.get(i);
            labels[i] = state.root == null ? AndroidApkAccess.display(entry.file.getPath())
                    : (entry.directory ? getString(R.string.apk_directory_prefix) : "APK · ") + AndroidApkAccess.display(entry.file.getName());
        }
        files.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
    }

    private void render() {
        ApkInstallSession.Phase phase = state.session.phase();
        boolean selectable = phase == ApkInstallSession.Phase.IDLE || phase == ApkInstallSession.Phase.READY;
        boolean listing = state.browser != null && state.browser.result == null;
        pickButton.setEnabled(selectable);
        browseButton.setEnabled(selectable);
        installButton.setEnabled(phase == ApkInstallSession.Phase.READY);
        files.setEnabled(selectable && !listing);
        boolean hasParent = state.showBrowser && state.directory != null;
        boolean upAvailable = selectable && !listing && hasParent;
        boolean hadUpFocus = upButton.hasFocus();
        upButton.setVisibility(hasParent ? View.VISIBLE : View.GONE);
        upButton.setEnabled(upAvailable);
        files.setNextFocusUpId(upAvailable ? R.id.apk_up : R.id.apk_browser);
        pickButton.setNextFocusDownId(state.showBrowser && !listing ? R.id.apk_files : R.id.apk_reset);
        browseButton.setNextFocusDownId(upAvailable ? R.id.apk_up
                : state.showBrowser && !listing ? R.id.apk_files : R.id.apk_reset);
        if (hadUpFocus && !hasParent) {
            if (selectable) browseButton.requestFocus(); else resetButton.requestFocus();
        }
        browser.setVisibility(state.showBrowser ? View.VISIBLE : View.GONE);
        statusScroll.setLayoutParams(state.showBrowser
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92))
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        path.setText(state.directory == null ? getString(R.string.apk_storage_roots) : AndroidApkAccess.display(state.directory.getPath()));
        String message;
        ApkInstallSession.Prepared item = state.session.item();
        if (phase == ApkInstallSession.Phase.PREPARING) message = getString(R.string.apk_preparing);
        else if (phase == ApkInstallSession.Phase.PICKING) message = getString(R.string.apk_picking);
        else if (phase == ApkInstallSession.Phase.INSTALLING) message = getString(R.string.apk_installer_opened);
        else if (phase == ApkInstallSession.Phase.READY && item != null) {
            message = getString(R.string.apk_ready, item.sourceName, item.packageName, item.version, item.bytes / 1024);
            if (Build.VERSION.SDK_INT < 24) message += "\n\n" + getString(R.string.apk_legacy_sharing_note);
        } else if (listing) message = getString(R.string.apk_loading_directory);
        else message = getString(Build.VERSION.SDK_INT >= 29 ? R.string.apk_scoped_storage_hint : R.string.apk_idle_hint);
        String error = state.session.error();
        status.setText(message + (error == null ? "" : "\n\n" + error)
                + (state.notice == null || state.notice.isEmpty() ? "" : "\n\n" + state.notice));
    }

    private void cancelBrowserWork() {
        if (state.browserWork != null) state.browserWork.cancel(null);
        state.browserWork = null;
    }
    private void cancelPrepareWork() {
        if (state.prepareWork != null) state.prepareWork.cancel(state.prepareTask == null ? null : state.prepareTask.control);
        state.prepareWork = null;
        state.prepareTask = null;
    }
    private static String detail(Exception failure) {
        if (failure instanceof java.util.concurrent.RejectedExecutionException) return "文件服务仍忙，已达到后台任务上限；请稍后重试，或更换无响应的 USB/文件提供方。";
        return AndroidApkAccess.display(failure.getClass().getSimpleName() + ": " + failure.getMessage());
    }
    private TextView text(String value, int size) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextColor(Color.rgb(226, 235, 242)); text.setTextSize(size);
        return text;
    }
    private Button button(int label, int id) {
        Button button = new Button(this);
        button.setId(id); button.setText(label); button.setTextSize(14);
        button.setTextColor(getResources().getColorStateList(R.color.reboot_button_text));
        button.setAllCaps(false); button.setMinHeight(dp(48)); button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setBackgroundResource(R.drawable.action_button);
        return button;
    }
    private Button navigationButton(int label, int id) {
        Button button = button(label, id);
        button.setBackgroundResource(R.drawable.navigation_button);
        button.setTextColor(getResources().getColorStateList(R.color.navigation_button_text));
        button.setMinWidth(dp(48));
        button.setMinimumWidth(dp(48));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setSingleLine(true);
        return button;
    }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(dp(2), dp(6), dp(2), dp(6)); return params;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class State {
        ApkInstallSession session = new ApkInstallSession();
        BrowserTask browser;
        ApkWorkQueue.Handle browserWork, prepareWork;
        PrepareTask prepareTask;
        List<ApkFiles.Entry> rows = Collections.emptyList();
        File root, directory;
        boolean showBrowser, legacyBrowserAccepted;
        String notice;
        int pickerRequest = -1, installerRequest = -1, requestCounter = 1000;
        int nextRequest() { if (++requestCounter >= 60000) requestCounter = 1000; return requestCounter; }
    }
    private static final class BrowseResult {
        final List<ApkFiles.Entry> files;
        final String error;
        BrowseResult(List<ApkFiles.Entry> files, String error) { this.files = files; this.error = error; }
    }
    private static final class BrowserTask implements Runnable {
        final AndroidApkAccess access;
        final File root, directory;
        volatile BrowseResult result;
        BrowserTask(AndroidApkAccess access, File root, File directory) { this.access = access; this.root = root; this.directory = directory; }
        @Override public void run() {
            try {
                List<ApkFiles.Entry> entries;
                if (root == null) {
                    entries = new ArrayList<>();
                    for (File file : access.roots()) entries.add(new ApkFiles.Entry(file, true));
                } else entries = access.list(root, directory);
                result = new BrowseResult(Collections.unmodifiableList(entries), null);
            } catch (IOException | RuntimeException failure) { result = new BrowseResult(Collections.<ApkFiles.Entry>emptyList(), detail(failure)); }
        }
    }
    private static final class PrepareTask implements Runnable {
        final AndroidApkAccess access;
        final ApkInstallSession session;
        final Uri source;
        final long ticket;
        final AndroidApkAccess.InputControl control = new AndroidApkAccess.InputControl();
        PrepareTask(AndroidApkAccess access, ApkInstallSession session, Uri source, long ticket) {
            this.access = access; this.session = session; this.source = source; this.ticket = ticket;
        }
        @Override public void run() {
            try {
                ApkInstallSession.Prepared item = access.prepare(source, new ApkCache.Cancellation() {
                    @Override public boolean cancelled() { return !session.isPreparing(ticket); }
                }, control);
                if (!session.complete(ticket, item)) access.discard(item);
            } catch (IOException | RuntimeException failure) { session.failed(ticket, detail(failure)); }
        }
    }
    @TargetApi(23)
    private static final class Api23 {
        static void requestRead(Activity activity) { activity.requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE }, READ_PERMISSION); }
    }
    @TargetApi(21)
    private static final class Api21 {
        static void insets(final View root, final int padding) {
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                    view.setPadding(padding + insets.getSystemWindowInsetLeft(), padding + insets.getSystemWindowInsetTop(),
                            padding + insets.getSystemWindowInsetRight(), padding + insets.getSystemWindowInsetBottom());
                    return insets;
                }
            });
            root.requestApplyInsets();
        }
    }
}
