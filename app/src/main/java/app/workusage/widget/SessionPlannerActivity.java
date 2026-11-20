package app.workusage.widget;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DecimalFormat;

public final class SessionPlannerActivity extends Activity {
    private UsageRepository repository;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable onReading = this::render;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            main.postDelayed(this, 30_000);
        }
    };
    private int duration;
    private int reserve;
    private boolean atReset;
    private TextView source;
    private TextView schedule;
    private Button startNow;
    private Button startAtReset;
    private Button refresh;
    private Button[] durations;
    private Button[] reserves;
    private Track fiveHours;
    private Track weekly;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        repository = UsageRepository.get(this);
        duration = SessionPlan.duration(this);
        reserve = SessionPlan.reserve(this);
        atReset = state != null && state.getBoolean("start_at_reset");
        build();
    }

    @Override
    protected void onStart() {
        super.onStart();
        repository.observe(onReading);
        render();
        main.postDelayed(tick, 30_000);
        if (repository.connected()) {
            repository.refresh(false, null);
        }
    }

    @Override
    protected void onStop() {
        repository.stopObserving(onReading);
        main.removeCallbacks(tick);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("start_at_reset", atReset);
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.background));
        scroll.setClipToPadding(false);
        if (Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }

        LinearLayout content = column();
        content.setPadding(dp(22), dp(16), dp(22), dp(20));
        scroll.addView(content);
        setContentView(scroll);

        Button dashboard = button(getString(R.string.session_dashboard));
        dashboard.setOnClickListener(view -> {
            startActivity(new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        });
        content.addView(dashboard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = text(33, R.color.on_surface, true);
        title.setText(R.string.session_title);
        if (Build.VERSION.SDK_INT >= 28) {
            title.setAccessibilityHeading(true);
        }
        add(content, title, 13);
        source = text(13, R.color.muted, false);
        add(content, source, 7);

        section(content, R.string.session_length, 20);
        durations = choices(content, SessionPlan.DURATIONS, R.string.session_minutes, true);
        section(content, R.string.session_reserve, 17);
        reserves = choices(content, SessionPlan.RESERVES, R.string.session_percent, false);
        section(content, R.string.session_start, 17);
        LinearLayout starts = row();
        startNow = button(getString(R.string.session_start_now));
        startAtReset = button(getString(R.string.session_start_reset));
        addChoice(starts, startNow, true);
        addChoice(starts, startAtReset, false);
        startNow.setOnClickListener(view -> {
            atReset = false;
            render();
        });
        startAtReset.setOnClickListener(view -> {
            atReset = true;
            render();
        });
        add(content, starts, 6);

        schedule = text(15, R.color.on_surface, true);
        add(content, schedule, 19);
        fiveHours = new Track(content, R.string.five_hour, R.color.mint);
        weekly = new Track(content, R.string.weekly, R.color.blue);

        TextView explanation = text(13, R.color.muted, false);
        explanation.setText(R.string.session_reading_explanation);
        add(content, explanation, 13);
        refresh = button(getString(R.string.refresh_usage));
        refresh.setOnClickListener(view -> repository.refresh(true, null));
        add(content, refresh, 13);
    }

    private void render() {
        UsageSnapshot snapshot = repository.snapshot();
        long now = System.currentTimeMillis();
        boolean connected = repository.connected();
        SessionPlan plan = new SessionPlan(snapshot, connected && repository.error() == null,
                now, duration, atReset);
        if (plan.nextReset == 0) {
            atReset = false;
        }

        if (snapshot == null) {
            source.setText(R.string.session_no_reading);
        } else {
            int label = plan.source == SessionPlan.Source.MANUAL ? R.string.session_manual_reading
                    : plan.source == SessionPlan.Source.SYNCED ? R.string.session_synced_reading
                    : R.string.session_saved_reading;
            source.setText(getString(label, UsageSnapshot.timeLabel(this, snapshot.fetchedAt, now, true)));
        }
        updateChoices(durations, SessionPlan.DURATIONS, duration);
        updateChoices(reserves, SessionPlan.RESERVES, reserve);
        startAtReset.setEnabled(plan.nextReset > 0);
        choose(startNow, !atReset);
        choose(startAtReset, atReset);
        startAtReset.setAlpha(startAtReset.isEnabled() ? 1f : .55f);

        schedule.setText(getString(R.string.session_range,
                UsageSnapshot.timeLabel(this, plan.start, now, true),
                UsageSnapshot.timeLabel(this, plan.end, now, true)));
        fiveHours.render(plan, plan.fiveHours, now);
        weekly.render(plan, plan.week, now);
        refresh.setVisibility(connected ? View.VISIBLE : View.GONE);
        refresh.setEnabled(!repository.syncing());
        refresh.setText(repository.syncing() ? R.string.session_syncing : R.string.refresh_usage);
    }

    private Button[] choices(LinearLayout content, int[] values, int format, boolean isDuration) {
        LinearLayout group = row();
        Button[] options = new Button[values.length];
        for (int i = 0; i < values.length; i++) {
            int value = values[i];
            Button option = button(getString(format, value));
            options[i] = option;
            option.setOnClickListener(view -> {
                if (isDuration) {
                    duration = value;
                } else {
                    reserve = value;
                }
                SessionPlan.save(this, duration, reserve);
                render();
            });
            addChoice(group, option, i < values.length - 1);
        }
        add(content, group, 6);
        return options;
    }

    private void updateChoices(Button[] options, int[] values, int selection) {
        for (int i = 0; i < options.length; i++) {
            choose(options[i], values[i] == selection);
        }
    }

    private void choose(Button button, boolean selected) {
        button.setSelected(selected);
        button.setTextColor(getColor(selected ? R.color.background : R.color.on_surface));
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(selected ? R.color.mint : R.color.surface)));
    }

    private void addChoice(LinearLayout group, Button button, boolean margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (margin) {
            params.setMarginEnd(dp(6));
        }
        group.addView(button, params);
    }

    private void section(LinearLayout content, int resource, int gap) {
        TextView label = text(13, R.color.muted, true);
        label.setText(resource);
        add(content, label, gap);
    }

    private TextView text(int size, int color, boolean strong) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        view.setTextColor(getColor(color));
        view.setTypeface(Typeface.create(strong ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinHeight(dp(46));
        button.setMinimumHeight(dp(46));
        button.setPadding(dp(4), dp(6), dp(4), dp(6));
        button.setBackground(shape(R.color.surface, 13));
        button.setStateListAnimator(null);
        button.setTextColor(getColor(R.color.on_surface));
        return button;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(getColor(color));
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private void add(LinearLayout parent, View child, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(gap);
        parent.addView(child, params);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private final class Track {
        private final TextView percent;
        private final TextView margin;
        private final TextView when;
        private final Timeline timeline;

        Track(LinearLayout parent, int title, int accent) {
            LinearLayout card = column();
            card.setPadding(dp(15), dp(11), dp(15), dp(11));
            card.setBackground(shape(R.color.surface, 18));
            LinearLayout line = row();
            TextView name = text(16, R.color.on_surface, true);
            name.setText(title);
            line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            percent = text(23, accent, true);
            line.addView(percent);
            add(card, line, 0);
            margin = text(13, R.color.muted, false);
            add(card, margin, 3);
            timeline = new Timeline(accent);
            card.addView(timeline, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(27)));
            when = text(13, R.color.muted, false);
            add(card, when, 0);
            add(parent, card, 9);
        }

        void render(SessionPlan plan, UsageSnapshot.Window window, long now) {
            percent.setText(window == null ? getString(R.string.unknown)
                    : getString(R.string.session_allowance_left, window.percent(now)));
            margin.setText(window == null ? getString(R.string.session_no_allowance) : marginLabel(window.remaining));
            SessionPlan.Timing timing = plan.timing(window);
            long reset = plan.reset(window);
            float marker = -1f;
            if (timing == SessionPlan.Timing.START || timing == SessionPlan.Timing.INSIDE
                    || timing == SessionPlan.Timing.END) {
                marker = (float) (reset - plan.start) / (plan.end - plan.start);
            }
            timeline.setMarker(marker);
            if (timing == SessionPlan.Timing.UNKNOWN) {
                when.setText(plan.source == SessionPlan.Source.SAVED
                        ? R.string.session_refresh_for_timing : R.string.session_time_unknown);
                return;
            }
            int label;
            switch (timing) {
                case START: label = R.string.session_reset_start; break;
                case INSIDE: label = R.string.session_reset_inside; break;
                case END: label = R.string.session_reset_end; break;
                default: label = R.string.session_reset_after; break;
            }
            when.setText(getString(label, UsageSnapshot.timeLabel(SessionPlannerActivity.this, reset, now, true)));
        }

        private String marginLabel(double allowance) {
            double delta = allowance - reserve;
            if (delta == 0) {
                return getString(R.string.session_at_reserve, reserve);
            }
            double absolute = Math.abs(delta);
            String amount = absolute < .1 ? "<0.1" : new DecimalFormat("0.#").format(absolute);
            return getString(delta > 0 ? R.string.session_above_reserve : R.string.session_below_reserve,
                    amount, reserve);
        }
    }

    private final class Timeline extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int accent;
        private float marker = -1f;

        Timeline(int accent) {
            super(SessionPlannerActivity.this);
            this.accent = getColor(accent);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setMarker(float position) {
            marker = position;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = dp(5);
            float right = getWidth() - dp(5);
            float y = getHeight() / 2f;
            paint.setColor(getColor(R.color.track));
            canvas.drawRoundRect(left, y - dp(3), right, y + dp(3), dp(3), dp(3), paint);
            if (marker >= 0f) {
                float x = left + (right - left) * marker;
                paint.setColor(accent);
                paint.setStrokeWidth(dp(2));
                canvas.drawLine(x, y - dp(8), x, y + dp(8), paint);
                canvas.drawCircle(x, y, dp(4), paint);
            }
        }
    }
}
