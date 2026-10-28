package com.gathin.deviceinfo;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
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

/** Framework-only UI: no Material theme, vectors, or AndroidX is required on an old TV. */
public final class MainActivity extends Activity {
    private static final String LAST_RESULT = "settings_result";
    private TextView report;
    private ScrollView scroll;
    private AndroidSettingsAccess settings;
    private String lastResult;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) lastResult = savedInstanceState.getString(LAST_RESULT);
        settings = new AndroidSettingsAccess(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 23, 32));
        int padding = dp(20);
        root.setPadding(padding, padding, padding, padding);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 21) Api21.applyInsets(root, padding);

        root.addView(text(getString(R.string.title), 25, Color.WHITE), matchWrap());
        TextView summary = text(getString(R.string.summary, DeviceReport.value(Build.VERSION.RELEASE),
                Build.VERSION.SDK_INT), 21, Color.rgb(141, 232, 255));
        summary.setPadding(0, dp(6), 0, dp(4));
        root.addView(summary, matchWrap());
        root.addView(text(getString(R.string.hint), 14, Color.LTGRAY), matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row = matchWrap();
        row.setMargins(0, dp(12), 0, dp(12));
        root.addView(actions, row);
        Button open = button(R.string.open_settings, R.id.settings_button);
        Button refresh = button(R.string.refresh, R.id.refresh_button);
        LinearLayout.LayoutParams openSize = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f);
        openSize.rightMargin = dp(10);
        actions.addView(open, openSize);
        actions.addView(refresh, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        scroll = new ScrollView(this);
        scroll.setId(R.id.report_scroll);
        scroll.setFocusable(true);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setNextFocusUpId(R.id.settings_button);
        report = text("", 18, Color.rgb(226, 235, 242));
        report.setPadding(dp(4), dp(8), dp(12), dp(20));
        scroll.addView(report, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(text(getString(R.string.privacy), 12, Color.LTGRAY), matchWrap());

        open.setNextFocusRightId(R.id.refresh_button);
        refresh.setNextFocusLeftId(R.id.settings_button);
        open.setNextFocusDownId(R.id.report_scroll);
        refresh.setNextFocusDownId(R.id.report_scroll);
        open.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { openSettings(); }
        });
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                lastResult = null;
                refreshReport();
                scroll.smoothScrollTo(0, 0);
            }
        });
        refreshReport();
        open.requestFocus();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString(LAST_RESULT, lastResult);
    }

    private void openSettings() {
        SettingsNavigator.Result result = SettingsNavigator.open(settings);
        lastResult = result.message;
        refreshReport();
        scroll.smoothScrollTo(0, 0);
        if (!result.opened) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.settings_result)
                    .setMessage(result.message)
                    .setPositiveButton(R.string.close, null)
                    .show();
        }
    }

    private void refreshReport() {
        String result = lastResult == null ? "" : lastResult + "\n\n──────────\n\n";
        report.setText(result + DeviceReport.render(DeviceReader.read(this), Build.VERSION.SDK_INT)
                + "\n\n──────────\n\n" + SettingsNavigator.describe(settings));
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
