package app.workusage.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

enum WidgetAppearance {
    DARK(R.string.widget_style_dark, R.drawable.widget_bg, R.drawable.widget_panel,
            0xFFF1F7F6, 0xFFACBCC3, 0xFFA1F0CB, 0xFFAFCFFF, 0xFF39484F),
    LIGHT(R.string.widget_style_light, R.drawable.widget_bg_light, R.drawable.widget_panel_light,
            0xFF182A27, 0xFF3D5753, 0xFF08664C, 0xFF254994, 0xFFC7D6D4),
    OLED(R.string.widget_style_oled, R.drawable.widget_bg_oled, R.drawable.widget_panel_oled,
            0xFFF5F5F5, 0xFFC6C9CD, 0xFFBFFF70, 0xFFFFD27A, 0xFF35383C),
    OCEAN(R.string.widget_style_ocean, R.drawable.widget_bg_ocean, R.drawable.widget_panel_ocean,
            0xFFEAF8FF, 0xFFAFD0E1, 0xFF6CF5EB, 0xFFA4BEFF, 0xFF335264),
    SUNSET(R.string.widget_style_sunset, R.drawable.widget_bg_sunset, R.drawable.widget_panel_sunset,
            0xFFFFF1E4, 0xFFE1BEBB, 0xFFFFC184, 0xFFFFA6CE, 0xFF694650);

    private static final String STYLE = "style";

    final int label;
    private final int background;
    private final int panel;
    private final int text;
    private final int secondary;
    private final int shortAccent;
    private final int weekAccent;
    private final int track;

    WidgetAppearance(int label, int background, int panel, int text, int secondary,
                     int shortAccent, int weekAccent, int track) {
        this.label = label;
        this.background = background;
        this.panel = panel;
        this.text = text;
        this.secondary = secondary;
        this.shortAccent = shortAccent;
        this.weekAccent = weekAccent;
        this.track = track;
    }

    static WidgetAppearance load(Context context) {
        String saved = preferences(context).getString(STYLE, DARK.name());
        for (WidgetAppearance appearance : values()) {
            if (appearance.name().equals(saved)) {
                return appearance;
            }
        }
        return DARK;
    }

    void save(Context context) {
        preferences(context).edit().putString(STYLE, name()).apply();
        UsageWidget.renderAll(context);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("widget_appearance", Context.MODE_PRIVATE);
    }

    void apply(RemoteViews views) {
        views.setInt(android.R.id.background, "setBackgroundResource", background);
        views.setInt(R.id.widget_short_panel, "setBackgroundResource", panel);
        views.setInt(R.id.widget_week_panel, "setBackgroundResource", panel);
        views.setTextColor(R.id.widget_title, text);
        views.setTextColor(R.id.widget_credits, text);
        views.setTextColor(R.id.widget_short_label, secondary);
        views.setTextColor(R.id.widget_week_label, secondary);
        views.setTextColor(R.id.widget_short_reset, secondary);
        views.setTextColor(R.id.widget_week_reset, secondary);
        views.setTextColor(R.id.widget_sync, secondary);
        views.setTextColor(R.id.widget_short_percent, shortAccent);
        views.setTextColor(R.id.widget_week_percent, weekAccent);
        views.setInt(R.id.widget_short_track, "setColorFilter", track);
        views.setInt(R.id.widget_week_track, "setColorFilter", track);
        views.setInt(R.id.widget_short_bar, "setColorFilter", shortAccent);
        views.setInt(R.id.widget_week_bar, "setColorFilter", weekAccent);
        views.setInt(R.id.widget_refresh, "setColorFilter", shortAccent);
    }

    void applyCompact(RemoteViews views, boolean weekly) {
        int accent = weekly ? weekAccent : shortAccent;
        views.setInt(android.R.id.background, "setBackgroundResource", background);
        views.setTextColor(R.id.widget_compact_label, secondary);
        views.setTextColor(R.id.widget_compact_value, accent);
        views.setInt(R.id.widget_compact_track, "setColorFilter", track);
        views.setInt(R.id.widget_compact_bar, "setColorFilter", accent);
        views.setInt(R.id.widget_refresh, "setColorFilter", accent);
    }
}
