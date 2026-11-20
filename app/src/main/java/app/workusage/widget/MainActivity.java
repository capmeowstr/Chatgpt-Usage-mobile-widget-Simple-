package app.workusage.widget;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public final class MainActivity extends Activity {
    private static final int ALERT_PERMISSION = 160;
    private static final int[] ALERT_LEVELS = {0, 10, 20, 30};
    private UsageRepository repository;
    private final Runnable updateScreen = this::render;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable updateResetTimes = new Runnable() {
        @Override
        public void run() {
            renderLimits(repository.snapshot(), System.currentTimeMillis());
            main.postDelayed(this, 30_000);
        }
    };
    private TextView status;
    private TextView resetCount;
    private TextView primary;
    private TextView secondaryAccount;
    private TextView deviceCode;
    private TextView browserSignIn;
    private TextView deviceSettings;
    private TextView enterDashboardValues;
    private TextView alerts;
    private LimitViews fiveHourCard;
    private LimitViews weekCard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = UsageRepository.get(this);
        buildScreen();
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        repository.observe(updateScreen);
        render();
        if (repository.connected()) {
            SyncJob.schedulePeriodic(this);
            repository.refresh(false, null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        repository.setSignInVisible(true);
        render();
        main.postDelayed(updateResetTimes, 30_000);
    }

    @Override
    protected void onPause() {
        repository.setSignInVisible(false);
        main.removeCallbacks(updateResetTimes);
        super.onPause();
    }

    @Override
    protected void onStop() {
        repository.stopObserving(updateScreen);
        super.onStop();
    }

    private void buildScreen() {
        ScrollView screen = new ScrollView(this);
        screen.setFillViewport(true);
        screen.setBackgroundColor(color(R.color.background));
        screen.setClipToPadding(false);
        if (Build.VERSION.SDK_INT >= 35) {
            screen.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        setContentView(screen);

        LinearLayout content = panel();
        screen.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.setPadding(dp(22), dp(24), dp(22), dp(24));

        LinearLayout brand = row();
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView name = text("ChatGPT Work", 15, color(R.color.mint), "sans-serif-medium");
        LinearLayout.LayoutParams nameParams = wrap();
        nameParams.setMarginStart(dp(10));
        brand.addView(name, nameParams);
        add(content, brand, 0);

        TextView title = text("Usage limits", 34, color(R.color.on_surface), "sans-serif-medium");
        heading(title);
        add(content, title, 16);
        status = text("", 14, color(R.color.muted), "sans-serif");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setLineSpacing(dp(2), 1f);
        add(content, status, 7);

        deviceCode = text("", 24, color(R.color.mint), "monospace");
        deviceCode.setGravity(Gravity.CENTER);
        deviceCode.setPadding(dp(12), dp(13), dp(12), dp(13));
        deviceCode.setBackground(shape(color(R.color.surface), 14));
        add(content, deviceCode, 10);

        fiveHourCard = addLimit(content, "Five-hour limit", R.color.mint, R.drawable.bar_mint, 20);
        weekCard = addLimit(content, "Weekly limit", R.color.blue, R.drawable.bar_blue, 9);

        LinearLayout resetRow = row();
        resetRow.setPadding(dp(17), dp(12), dp(17), dp(12));
        resetRow.setBackground(shape(color(R.color.surface), 18));
        TextView earned = text("Earned resets left", 15, color(R.color.on_surface),
                "sans-serif-medium");
        resetRow.addView(earned,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        resetCount = text("—", 22, color(R.color.mint), "sans-serif-medium");
        resetRow.addView(resetCount, wrap());
        add(content, resetRow, 9);

        TextView planner = button(getString(R.string.session_open), false);
        planner.setOnClickListener(view -> startActivity(new Intent(this, SessionPlannerActivity.class)));
        add(content, planner, 10);

        primary = button("Connect with a device code", true);
        primary.setOnClickListener(v -> accountAction());
        add(content, primary, 18);

        TextView pin = button(getString(R.string.widget_add), false);
        pin.setOnClickListener(v -> addWidget());
        add(content, pin, 8);

        TextView style = button(getString(R.string.widget_style), false);
        style.setOnClickListener(v -> showWidgetStyles());
        add(content, style, 8);

        LinearLayout tools = row();
        TextView history = button(getString(R.string.history_button), false);
        history.setOnClickListener(v -> showUsageHistory());
        LinearLayout.LayoutParams firstTool = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        firstTool.setMarginEnd(dp(8));
        tools.addView(history, firstTool);
        alerts = button(getString(R.string.usage_alerts), false);
        alerts.setOnClickListener(v -> showUsageAlerts());
        tools.addView(alerts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        add(content, tools, 8);

        TextView dashboard = link("Open usage dashboard", () -> openBrowser(CodexClient.DASHBOARD));
        add(content, dashboard, 5);
        browserSignIn = link("Use browser sign-in", this::startBrowserSignIn);
        add(content, browserSignIn, 0);
        deviceSettings = link("Device code settings", () -> openBrowser(CodexClient.SECURITY_SETTINGS));
        add(content, deviceSettings, 0);
        enterDashboardValues = link("Enter dashboard values", this::showManualUsage);
        add(content, enterDashboardValues, 0);
        secondaryAccount = link("Disconnect", this::secondaryAccountAction);
        add(content, secondaryAccount, 0);
    }

    private LimitViews addLimit(LinearLayout parent, String name, int accent, int barResource,
                                int margin) {
        LinearLayout card = panel();
        card.setPadding(dp(17), dp(13), dp(17), dp(13));
        card.setBackground(shape(color(R.color.surface), 18));
        add(parent, card, margin);

        LinearLayout line = row();
        TextView title = text(name, 15, color(R.color.on_surface), "sans-serif-medium");
        line.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView value = text("—", 23, color(accent), "sans-serif-medium");
        value.setGravity(Gravity.END);
        line.addView(value, wrap());
        add(card, line, 0);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setProgressDrawable(getDrawable(barResource).mutate());
        bar.setMax(100);
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
        barParams.topMargin = dp(8);
        card.addView(bar, barParams);

        TextView detail = text("", 12, color(R.color.muted), "sans-serif");
        add(card, detail, 7);
        return new LimitViews(value, detail, bar);
    }

    private void render() {
        UsageSnapshot snapshot = repository.snapshot();
        long now = System.currentTimeMillis();
        renderLimits(snapshot, now);
        resetCount.setText(snapshot == null || snapshot.earnedResets == null
                ? "—" : String.valueOf(snapshot.earnedResets));
        resetCount.setContentDescription(snapshot == null || snapshot.earnedResets == null
                ? "Earned reset count unavailable" : snapshot.earnedResets + " earned resets left");
        status.setText(statusText(snapshot, now));
        status.setTextColor(color(repository.error() == null ? R.color.muted : R.color.alert));
        int threshold = UsageAlerts.threshold(this);
        alerts.setText(threshold == 0 ? getString(R.string.usage_alerts)
                : getString(R.string.usage_alerts_threshold, threshold));

        boolean signingIn = repository.signingIn();
        boolean connected = repository.connected();
        boolean usingCode = repository.signingInWithDeviceCode();
        String code = repository.deviceUserCode();
        deviceCode.setVisibility(code != null && !repository.exchanging() ? View.VISIBLE : View.GONE);
        deviceCode.setText(code);
        deviceCode.setContentDescription(code == null ? null : "One-time device code " + code);
        browserSignIn.setVisibility(!signingIn && !connected ? View.VISIBLE : View.GONE);
        deviceSettings.setVisibility(!connected && !repository.exchanging() ? View.VISIBLE : View.GONE);
        enterDashboardValues.setVisibility(!signingIn && (!connected || repository.error() != null)
                ? View.VISIBLE : View.GONE);
        if (signingIn) {
            if (repository.exchanging()) {
                primary.setText("Connecting…");
            } else if (usingCode) {
                primary.setText(code == null ? "Getting a device code…" : "Copy code and open ChatGPT");
            } else {
                primary.setText("Continue browser sign-in");
            }
            secondaryAccount.setText("Cancel sign-in");
        } else if (connected) {
            primary.setText(repository.syncing() ? "Refreshing…" : "Refresh usage");
            secondaryAccount.setText("Disconnect");
        } else {
            primary.setText("Connect with a device code");
        }
        secondaryAccount.setVisibility(signingIn || connected ? View.VISIBLE : View.GONE);
        primary.setEnabled(!repository.exchanging() && !repository.syncing() && (!usingCode || code != null));
        primary.setAlpha(primary.isEnabled() ? 1f : 0.67f);
    }

    private void renderLimits(UsageSnapshot snapshot, long now) {
        fiveHourCard.render(snapshot == null ? null : snapshot.fiveHours, snapshot != null, now);
        weekCard.render(snapshot == null ? null : snapshot.week, snapshot != null, now);
    }

    private String statusText(UsageSnapshot snapshot, long now) {
        if (repository.signingIn()) {
            if (repository.exchanging()) {
                return "Finishing ChatGPT sign-in…";
            }
            if (repository.signingInWithDeviceCode()) {
                return repository.deviceUserCode() == null ? "Requesting a one-time code from OpenAI."
                        : "Approve the code in your browser, then switch back here to finish signing in.";
            }
            return "Finish signing in with your browser, then return here.";
        }
        if (repository.syncing()) {
            return "Syncing with ChatGPT…";
        }
        String lastSync = snapshot == null ? ""
                : (snapshot.manuallyEntered ? "Manually entered " : "Last synced ")
                + UsageSnapshot.timeLabel(this, snapshot.fetchedAt, now, false);
        String error = repository.error();
        if (error != null) {
            return snapshot == null ? error : error + "\n" + lastSync;
        }
        if (snapshot != null) {
            return lastSync;
        }
        return repository.connected() ? "Waiting for your first sync."
                : "Connect your ChatGPT account to see your remaining allowance.";
    }

    private void accountAction() {
        if (repository.connected()) {
            repository.refresh(true, null);
            return;
        }
        if (repository.signingInWithDeviceCode()) {
            String code = repository.deviceUserCode();
            if (code != null) {
                copyCodeAndOpenChatGpt(code);
            }
            return;
        }
        if (repository.signingIn()) {
            startBrowserSignIn();
            return;
        }
        repository.startSignIn(true, null);
    }

    private void startBrowserSignIn() {
        repository.startSignIn(false, url -> {
            if (!isFinishing() && !isDestroyed() && !openBrowser(url)) {
                repository.cancelSignIn("Open a browser on this phone to sign in.");
            }
        });
    }

    private void copyCodeAndOpenChatGpt(String code) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = ClipData.newPlainText("OpenAI one-time code", code);
        if (Build.VERSION.SDK_INT >= 33) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
        openBrowser(CodexClient.DEVICE_VERIFICATION_URL);
    }

    private void showManualUsage() {
        LinearLayout fields = panel();
        fields.setPadding(dp(22), dp(6), dp(22), dp(8));
        UsageSnapshot current = repository.snapshot();
        EditText five = manualField(fields, "Five-hour % left", true,
                current == null || current.fiveHours == null ? null : current.fiveHours.remaining);
        EditText week = manualField(fields, "Weekly % left", true,
                current == null || current.week == null ? null : current.week.remaining);
        EditText resets = manualField(fields, "Earned resets left", false,
                current == null ? null : current.earnedResets);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Enter dashboard values")
                .setMessage("Use the remaining percentages and earned reset count from the dashboard. "
                        + "You can leave a field blank.")
                .setView(fields)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Open dashboard", null)
                .setPositiveButton("Save", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(view -> openBrowser(CodexClient.DASHBOARD));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                Double fiveValue = readPercentage(five);
                Double weekValue = readPercentage(week);
                Integer resetValue = readResets(resets);
                if (fiveValue == null && weekValue == null && resetValue == null) {
                    five.setError("Enter at least one value");
                    return;
                }
                repository.saveManualUsage(fiveValue, weekValue, resetValue);
                dialog.dismiss();
            } catch (NumberFormatException ignoredInput) {
                // The field that failed validation shows the correction.
            }
        });
    }

    private EditText manualField(LinearLayout parent, String hint, boolean decimal, Number initial) {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER
                | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        if (initial != null) {
            input.setText(initial.toString());
        }
        add(parent, input, 5);
        return input;
    }

    private Double readPercentage(EditText input) {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            double value = Double.parseDouble(text);
            if (!Double.isFinite(value) || value < 0 || value > 100) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            input.setError("Enter a percentage from 0 to 100");
            throw e;
        }
    }

    private Integer readResets(EditText input) {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            int value = Integer.parseInt(text);
            if (value < 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            input.setError("Enter a whole number of 0 or more");
            throw e;
        }
    }

    private void secondaryAccountAction() {
        if (repository.signingIn()) {
            repository.cancelSignIn(null);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Disconnect ChatGPT?")
                .setMessage("Your saved usage and history will be cleared from this phone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Disconnect", (dialog, which) -> repository.disconnect())
                .show();
    }

    private void addWidget() {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        if (!manager.isRequestPinAppWidgetSupported()) {
            showWidgetPinFallback();
            return;
        }
        UsageWidget.Variant[] variants = UsageWidget.Variant.values();
        CharSequence[] labels = new CharSequence[variants.length];
        for (int i = 0; i < variants.length; i++) {
            labels[i] = getText(variants[i].pickerLabel);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.widget_add)
                .setItems(labels, (dialog, index) -> {
                    if (!manager.requestPinAppWidget(variants[index].component(this), null, null)) {
                        showWidgetPinFallback();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showWidgetPinFallback() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.widget_add)
                .setMessage(R.string.widget_pin_fallback)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showUsageHistory() {
        long now = System.currentTimeMillis();
        List<UsageHistory.Point> points = UsageHistory.recent(this, now);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.history_title)
                .setPositiveButton(android.R.string.ok, null);
        if (points.isEmpty()) {
            dialog.setMessage(R.string.history_empty).show();
            return;
        }

        LinearLayout content = panel();
        content.setPadding(dp(22), dp(8), dp(22), dp(6));
        TextView caption = text(getString(R.string.history_details), 13, color(R.color.muted), "sans-serif");
        add(content, caption, 0);
        content.addView(new UsageHistoryChart(this, points, now),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(161)));
        UsageHistory.Point latest = points.get(points.size() - 1);
        TextView summary = text(getString(R.string.history_latest,
                historyPercent(latest.fiveHours), historyPercent(latest.week)),
                13, color(R.color.on_surface), "sans-serif");
        add(content, summary, 4);
        LinearLayout key = row();
        key.addView(text("● Five-hour", 12, color(R.color.mint), "sans-serif-medium"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        key.addView(text("● Weekly", 12, color(R.color.blue), "sans-serif-medium"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        add(content, key, 10);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        dialog.setView(scroll)
                .setNeutralButton(R.string.history_clear, (view, which) -> new AlertDialog.Builder(this)
                        .setTitle(R.string.history_clear)
                        .setMessage(R.string.history_clear_confirm)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.history_clear, (confirm, action) -> UsageHistory.clear(this))
                        .show())
                .show();
    }

    private String historyPercent(Double percent) {
        return percent == null ? getString(R.string.unknown) : UsageSnapshot.percentage(percent);
    }

    private void showUsageAlerts() {
        int current = UsageAlerts.threshold(this);
        CharSequence[] labels = new CharSequence[ALERT_LEVELS.length];
        int selected = 0;
        for (int i = 0; i < ALERT_LEVELS.length; i++) {
            int level = ALERT_LEVELS[i];
            labels[i] = level == 0 ? getText(R.string.usage_alert_off)
                    : getString(R.string.usage_alert_level, level);
            if (level == current) {
                selected = i;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.usage_alerts)
                .setSingleChoiceItems(labels, selected, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.usage_alert_settings, (dialog, which) -> startActivity(
                        new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())))
                .setPositiveButton("Apply", (dialog, which) -> {
                    int item = ((AlertDialog) dialog).getListView().getCheckedItemPosition();
                    if (item >= 0) {
                        saveAlertLevel(ALERT_LEVELS[item]);
                    }
                })
                .setView(usageAlertExplanation(current))
                .show();
    }

    private TextView usageAlertExplanation(int threshold) {
        String message = getString(R.string.usage_alert_description);
        if (threshold > 0 && !UsageAlerts.allowed(this)) {
            message += "\n\n" + getString(R.string.usage_alert_blocked);
        }
        TextView explanation = text(message, 13, color(R.color.muted), "sans-serif");
        explanation.setPadding(dp(24), dp(16), dp(24), dp(4));
        return explanation;
    }

    private void saveAlertLevel(int level) {
        UsageAlerts.saveThreshold(this, level);
        render();
        if (level == 0) {
            return;
        }
        if (UsageAlerts.needsPermission(this)) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, ALERT_PERMISSION);
        } else if (UsageAlerts.allowed(this) && repository.connected()) {
            repository.refresh(true, null);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ALERT_PERMISSION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED && repository.connected()) {
            repository.refresh(true, null);
        }
    }

    private void showWidgetStyles() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = panel();
        content.setPadding(dp(22), dp(12), dp(22), dp(8));
        scroll.addView(content);

        FrameLayout preview = new FrameLayout(this);
        content.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(158)));
        LinearLayout compact = row();
        FrameLayout fiveHourPreview = new FrameLayout(this);
        FrameLayout weeklyPreview = new FrameLayout(this);
        LinearLayout.LayoutParams firstPreview = new LinearLayout.LayoutParams(0, dp(52), 1f);
        firstPreview.setMarginEnd(dp(8));
        compact.addView(fiveHourPreview, firstPreview);
        compact.addView(weeklyPreview, new LinearLayout.LayoutParams(0, dp(52), 1f));
        add(content, compact, 8);
        FrameLayout sessionPreview = new FrameLayout(this);
        LinearLayout.LayoutParams sessionParams = new LinearLayout.LayoutParams(dp(146), dp(52));
        sessionParams.topMargin = dp(8);
        content.addView(sessionPreview, sessionParams);

        RadioGroup styles = new RadioGroup(this);
        styles.setOrientation(RadioGroup.VERTICAL);
        add(content, styles, 12);

        WidgetAppearance current = WidgetAppearance.load(this);
        for (WidgetAppearance appearance : WidgetAppearance.values()) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setText(appearance.label);
            option.setTag(appearance);
            option.setMinHeight(dp(48));
            styles.addView(option);
            if (appearance == current) {
                styles.check(option.getId());
            }
        }
        renderWidgetPreviews(preview, fiveHourPreview, weeklyPreview, sessionPreview, current);
        styles.setOnCheckedChangeListener((group, id) -> {
            RadioButton option = group.findViewById(id);
            if (option != null) {
                renderWidgetPreviews(preview, fiveHourPreview, weeklyPreview, sessionPreview,
                        (WidgetAppearance) option.getTag());
            }
        });

        new AlertDialog.Builder(this)
                .setTitle(R.string.widget_style)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Apply", (dialog, which) -> {
                    RadioButton option = styles.findViewById(styles.getCheckedRadioButtonId());
                    ((WidgetAppearance) option.getTag()).save(this);
                })
                .show();
    }

    private void renderWidgetPreviews(FrameLayout full, FrameLayout fiveHour, FrameLayout weekly,
                                      FrameLayout session, WidgetAppearance appearance) {
        renderWidgetPreview(full, appearance, UsageWidget.Variant.FULL);
        renderWidgetPreview(fiveHour, appearance, UsageWidget.Variant.FIVE_HOUR);
        renderWidgetPreview(weekly, appearance, UsageWidget.Variant.WEEKLY);
        renderWidgetPreview(session, appearance, UsageWidget.Variant.SESSION);
    }

    private void renderWidgetPreview(FrameLayout preview, WidgetAppearance appearance,
                                     UsageWidget.Variant variant) {
        preview.removeAllViews();
        preview.addView(UsageWidget.preview(this, appearance, variant).apply(this, preview),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private boolean openBrowser(String address) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(address));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "This phone needs a browser to open ChatGPT.", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private TextView text(String label, int size, int color, String family) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(family, Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    private TextView button(String label, boolean filled) {
        Button view = new Button(this);
        view.setText(label);
        view.setTextSize(15);
        view.setTextColor(color(filled ? R.color.background : R.color.on_surface));
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setAllCaps(false);
        view.setIncludeFontPadding(false);
        view.setStateListAnimator(null);
        int base = color(filled ? R.color.mint : R.color.surface);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(filled ? 0x22000000 : 0x28FFFFFF),
                shape(base, 14), null));
        view.setMinHeight(dp(49));
        view.setPadding(dp(14), dp(10), dp(14), dp(10));
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private TextView link(String label, Runnable click) {
        TextView view = text(label, 14, color(R.color.muted), "sans-serif-medium");
        view.setMinHeight(dp(44));
        view.setGravity(Gravity.CENTER);
        view.setOnClickListener(v -> click.run());
        return view;
    }

    private void heading(TextView view) {
        if (Build.VERSION.SDK_INT >= 28) {
            view.setAccessibilityHeading(true);
        }
    }

    private void add(LinearLayout parent, View view, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(gap);
        parent.addView(view, params);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout panel() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        return group;
    }

    private LinearLayout row() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setGravity(Gravity.CENTER_VERTICAL);
        return group;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int color(int resource) {
        return getColor(resource);
    }

    private final class LimitViews {
        private final TextView value;
        private final TextView detail;
        private final ProgressBar bar;

        LimitViews(TextView value, TextView detail, ProgressBar bar) {
            this.value = value;
            this.detail = detail;
            this.bar = bar;
        }

        void render(UsageSnapshot.Window window, boolean hasSnapshot, long now) {
            boolean showValue = window != null && !window.expired(now);
            value.setText(showValue ? window.percent(now) + " left" : "—");
            if (window != null) {
                String resetTime = window.detail(MainActivity.this, now, false);
                String eta = window.timeToReset(now);
                detail.setText(eta.isEmpty() ? resetTime : getString(R.string.dashboard_reset_eta, resetTime, eta));
            } else {
                detail.setText(hasSnapshot ? "Limit unavailable" : "Awaiting usage data");
            }
            bar.setProgress(showValue ? window.progress(now) : 0);
        }
    }
}
