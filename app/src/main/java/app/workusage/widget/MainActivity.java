package app.workusage.widget;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private UsageRepository repository;
    private final Runnable updateScreen = this::render;
    private TextView status;
    private TextView resetCount;
    private TextView primary;
    private TextView secondaryAccount;
    private TextView deviceCode;
    private TextView browserSignIn;
    private TextView deviceSettings;
    private TextView enterDashboardValues;
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
    }

    @Override
    protected void onPause() {
        repository.setSignInVisible(false);
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

        primary = button("Connect with a device code", true);
        primary.setOnClickListener(v -> accountAction());
        add(content, primary, 18);

        TextView pin = button("Add to home screen", false);
        pin.setOnClickListener(v -> addWidget());
        add(content, pin, 8);

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
        fiveHourCard.render(snapshot == null ? null : snapshot.fiveHours, snapshot != null, now);
        weekCard.render(snapshot == null ? null : snapshot.week, snapshot != null, now);
        resetCount.setText(snapshot == null || snapshot.earnedResets == null
                ? "—" : String.valueOf(snapshot.earnedResets));
        resetCount.setContentDescription(snapshot == null || snapshot.earnedResets == null
                ? "Earned reset count unavailable" : snapshot.earnedResets + " earned resets left");
        status.setText(statusText(snapshot, now));
        status.setTextColor(color(repository.error() == null ? R.color.muted : R.color.alert));

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
                .setMessage("Your saved usage will be cleared from this phone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Disconnect", (dialog, which) -> repository.disconnect())
                .show();
    }

    private void addWidget() {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        if (manager.isRequestPinAppWidgetSupported()
                && manager.requestPinAppWidget(UsageWidget.component(this), null, null)) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Add ChatGPT Work Usage")
                .setMessage("Touch and hold your home screen. Open Widgets, find ChatGPT Work Usage, "
                        + "and drag it onto your home screen.")
                .setPositiveButton("OK", null)
                .show();
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
                detail.setText(window.detail(MainActivity.this, now, false));
            } else {
                detail.setText(hasSnapshot ? "Limit unavailable" : "Awaiting usage data");
            }
            bar.setProgress(showValue ? window.progress(now) : 0);
        }
    }
}
