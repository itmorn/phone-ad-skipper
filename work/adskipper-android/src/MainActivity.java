package local.adskipper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    static final String PREFS = "selected_apps";
    static final String INTERCEPT_ENABLED = "intercept_enabled";
    static final String INTERCEPT_NOTICE = "intercept_notice_enabled";
    static final String INTERCEPT_SCREENSHOT = "intercept_screenshot_enabled";
    private static final String INTERVAL = "sample_interval_ms";
    private static final String DURATION = "sample_duration_seconds";

    private void updateInterceptSwitch(Switch control, boolean enabled) {
        control.setText(enabled ? "总开关 · 已开启" : "总开关 · 已暂停");
        control.setTextColor(enabled ? 0xff075744 : 0xff394640);
    }

    static int sampleInterval(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(INTERVAL, 200);
    }

    static int sampleDuration(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(DURATION, 5);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        layout.setFitsSystemWindows(true);

        TextView title = new TextView(this);
        title.setText(getApplicationInfo().loadLabel(getPackageManager()));
        title.setTextSize(25);
        layout.addView(title);
        Button settings = new Button(this);
        settings.setText("打开无障碍设置");
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        layout.addView(settings);
        TextView instructions = new TextView(this);
        instructions.setText("开启助手的无障碍服务，然后勾选要自动跳过开屏广告的应用。");
        layout.addView(instructions);

        Button history = new Button(this);
        history.setText("拦截历史");
        history.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        layout.addView(history);
        Button sampling = new Button(this);
        sampling.setText("检测设置");
        sampling.setOnClickListener(v -> showSamplingSettings());
        layout.addView(sampling);
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        Switch intercept = new Switch(this);
        intercept.setTextSize(18);
        intercept.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        intercept.setMinHeight(Math.round(padding * 3.2f));
        intercept.setSwitchMinWidth(Math.round(padding * 2.4f));
        intercept.setPadding(padding, 0, padding, 0);
        intercept.setTrackTintList(new ColorStateList(
            new int[][] { {android.R.attr.state_checked}, {} },
            new int[] {0xff358a70, 0xff899591}));
        intercept.setThumbTintList(ColorStateList.valueOf(0xffffffff));

        GradientDrawable card = new GradientDrawable();
        card.setColor(0xffe9f4ef);
        card.setCornerRadius(padding);
        intercept.setBackground(new RippleDrawable(ColorStateList.valueOf(0x25358a70), card, null));
        intercept.setChecked(preferences.getBoolean(INTERCEPT_ENABLED, true));
        updateInterceptSwitch(intercept, intercept.isChecked());
        intercept.setOnCheckedChangeListener((view, enabled) -> {
            updateInterceptSwitch(intercept, enabled);
            preferences.edit().putBoolean(INTERCEPT_ENABLED, enabled).apply();
        });
        LinearLayout.LayoutParams interceptLayout = new LinearLayout.LayoutParams(-1, -2);
        interceptLayout.setMargins(0, padding / 2, 0, padding / 2);
        layout.addView(intercept, 1, interceptLayout);

        Switch notice = new Switch(this);
        notice.setText("拦截提醒");
        notice.setChecked(preferences.getBoolean(INTERCEPT_NOTICE, true));
        notice.setOnCheckedChangeListener((view, enabled) ->
            preferences.edit().putBoolean(INTERCEPT_NOTICE, enabled).apply());
        layout.addView(notice);
        Switch screenshot = new Switch(this);
        screenshot.setText("保存广告截图");
        screenshot.setChecked(preferences.getBoolean(INTERCEPT_SCREENSHOT, true));
        screenshot.setOnCheckedChangeListener((view, enabled) ->
            preferences.edit().putBoolean(INTERCEPT_SCREENSHOT, enabled).apply());
        layout.addView(screenshot);
        Button selectAll = new Button(this);
        layout.addView(selectAll);

        ScrollView scroll = new ScrollView(this);
        LinearLayout apps = new LinearLayout(this);
        apps.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(apps);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);

        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> entries = new ArrayList<>(getPackageManager().queryIntentActivities(launcher, 0));
        entries.sort(new ResolveInfo.DisplayNameComparator(getPackageManager()));
        Set<String> shown = new HashSet<>();
        Map<String, CheckBox> choices = new LinkedHashMap<>();
        for (ResolveInfo entry : entries) {
            String name = entry.activityInfo.packageName;
            if (name.equals(getPackageName()) || !shown.add(name)) continue;
            CheckBox check = new CheckBox(this);
            check.setText(entry.loadLabel(getPackageManager()));
            check.setChecked(preferences.getBoolean(name, false));
            choices.put(name, check);
            check.setOnClickListener(view -> {
                preferences.edit().putBoolean(name, check.isChecked()).apply();
                updateSelectionButton(selectAll, choices.values());
            });
            apps.addView(check);
        }
        updateSelectionButton(selectAll, choices.values());
        selectAll.setOnClickListener(view -> {
            boolean selected = !allSelected(choices.values());
            SharedPreferences.Editor editor = preferences.edit();
            choices.forEach((name, check) -> {
                editor.putBoolean(name, selected);
                check.setChecked(selected);
            });
            editor.apply();
            updateSelectionButton(selectAll, choices.values());
        });
    }

    private void showSamplingSettings() {
        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        options.setPadding(padding, padding, padding, padding);
        SeekBar interval = addSlider(options, "检测间隔", " ms", 10, 500, sampleInterval(this));
        SeekBar duration = addSlider(options, "持续时间", " 秒", 1, 15, sampleDuration(this));
        new AlertDialog.Builder(this).setTitle("检测设置").setView(options)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", (dialog, which) -> getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putInt(INTERVAL, interval.getProgress() + 10)
                .putInt(DURATION, duration.getProgress() + 1).apply()).show();
    }

    private SeekBar addSlider(LinearLayout options, String title, String unit, int min, int max, int value) {
        TextView label = new TextView(this);
        label.setText(title + "  " + value + unit);
        options.addView(label);
        SeekBar slider = new SeekBar(this);
        slider.setContentDescription(title);
        slider.setMax(max - min);
        slider.setProgress(value - min);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int progress, boolean fromUser) {
                label.setText(title + "  " + (min + progress) + unit);
            }
            @Override public void onStartTrackingTouch(SeekBar view) {}
            @Override public void onStopTrackingTouch(SeekBar view) {}
        });
        options.addView(slider);
        return slider;
    }

    private boolean allSelected(Collection<CheckBox> choices) {
        for (CheckBox check : choices) {
            if (!check.isChecked()) return false;
        }
        return !choices.isEmpty();
    }

    private void updateSelectionButton(Button button, Collection<CheckBox> choices) {
        button.setEnabled(!choices.isEmpty());
        button.setText(allSelected(choices) ? "取消全选" : "全选");
    }

}
