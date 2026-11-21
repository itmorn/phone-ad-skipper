package local.adskipper;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public class HistoryActivity extends Activity {
    private static final int INK = Color.rgb(24, 40, 54);
    private static final int MUTED = Color.rgb(78, 98, 112);
    private static final int ACCENT = Color.rgb(0, 95, 78);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = column();
        page.setBackgroundColor(Color.rgb(242, 246, 248));
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = android.os.Build.VERSION.SDK_INT >= 30
                ? insets.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout())
                : insets.getSystemWindowInsets();
            view.setPadding(bars.left + dp(20), bars.top + dp(8),
                bars.right + dp(20), bars.bottom + dp(8));
            return insets;
        });

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this, null, android.R.attr.borderlessButtonStyle);
        back.setImageResource(android.R.drawable.ic_menu_revert);
        back.setColorFilter(ACCENT);
        back.setContentDescription("返回");
        back.setOnClickListener(view -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        toolbar.addView(label("拦截历史", 24, INK, true));
        page.addView(toolbar);

        JSONArray records = History.read(this);
        addSavings(page, History.savings(this, records));
        TextView count = label("最近 " + records.length() + " 条记录", 14, MUTED, false);
        count.setPadding(0, dp(4), 0, dp(16));
        page.addView(count);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = column();
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        if (records.length() == 0) {
            LinearLayout card = card(list);
            card.addView(label("暂无拦截记录", 18, INK, true));
            TextView hint = label("自动跳过开屏广告后，记录会显示在这里。", 14, MUTED, false);
            hint.setPadding(0, dp(6), 0, 0);
            card.addView(hint);
        }

        SimpleDateFormat date = new SimpleDateFormat("yyyy年M月d日", Locale.getDefault());
        SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        Map<String, String> packagesByLabel = launcherPackages();
        String previousDay = null;
        for (int i = 0; i < records.length(); i++) {
            JSONObject entry = records.optJSONObject(i);
            if (entry == null) continue;
            Date timestamp = new Date(entry.optLong("time"));
            String day = date.format(timestamp);
            if (!day.equals(previousDay)) {
                TextView heading = label(day, 14, MUTED, true);
                heading.setPadding(0, dp(8), 0, dp(8));
                list.addView(heading);
                previousDay = day;
            }
            LinearLayout card = card(list);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            String name = entry.optString("app");
            addAppIcon(card, name, entry.optString("package", packagesByLabel.get(name)));
            LinearLayout details = column();
            card.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            details.addView(label(name, 18, INK, true));
            TextView detail = label(time.format(timestamp), 14, MUTED, false);
            detail.setPadding(0, dp(5), 0, dp(8));
            details.addView(detail);
            int seconds = entry.optInt("seconds", -1);
            String saving = "节省时间 " + duration(History.savedSeconds(seconds));
            details.addView(label(seconds < 0 ? saving + "（估算）" : saving, 15, ACCENT, true));
            TextView rule = label("命中规则 · " + entry.optString("ruleName", "未记录"), 12, MUTED, false);
            rule.setPadding(0, dp(4), 0, 0);
            details.addView(rule);
            if (entry.has("image")) addPreview(card, entry.optString("image"));
        }

        setContentView(page);
    }

    private void addSavings(LinearLayout page, History.Savings savings) {
        long days = 1;
        if (savings.firstRecordedAt > 0) {
            ZoneId zone = ZoneId.systemDefault();
            LocalDate first = Instant.ofEpochMilli(savings.firstRecordedAt).atZone(zone).toLocalDate();
            days = Math.max(1, ChronoUnit.DAYS.between(first, LocalDate.now(zone)) + 1);
        }
        LinearLayout summary = card(page);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        addMetric(summary, "累计节省时间", duration(savings.seconds));
        double average = (double) savings.seconds / days;
        String daily = average > 0 && average < 1 ? "不足 1 秒" : duration(Math.round(average));
        addMetric(summary, "平均每天节省", daily);
    }

    private void addMetric(LinearLayout summary, String title, String value) {
        LinearLayout metric = column();
        metric.setPadding(0, 0, dp(6), 0);
        metric.addView(label(title, 14, MUTED, true));
        TextView number = label(value, 23, ACCENT, true);
        number.setPadding(0, dp(4), 0, 0);
        metric.addView(number);
        summary.addView(metric, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private String duration(long seconds) {
        if (seconds >= 3600) return seconds / 3600 + " 小时 " + (seconds / 60 % 60) + " 分";
        if (seconds >= 60) return seconds / 60 + " 分 " + (seconds % 60) + " 秒";
        return seconds + " 秒";
    }

    private Map<String, String> launcherPackages() {
        Map<String, String> packages = new HashMap<>();
        PackageManager manager = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo entry : manager.queryIntentActivities(launcher, 0)) {
            String name = manager.getApplicationLabel(entry.activityInfo.applicationInfo).toString();
            packages.putIfAbsent(name, entry.activityInfo.packageName);
        }
        return packages;
    }

    private void addAppIcon(LinearLayout card, String name, String packageName) {
        PackageManager manager = getPackageManager();
        Drawable icon = manager.getDefaultActivityIcon();
        if (packageName != null && !packageName.isEmpty()) {
            try {
                icon = manager.getApplicationIcon(packageName);
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        ImageView view = new ImageView(this);
        view.setImageDrawable(icon);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setContentDescription(name + "图标");
        LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(dp(38), dp(38));
        position.setMarginEnd(dp(12));
        card.addView(view, position);
    }

    private void addPreview(LinearLayout card, String filename) {
        String path = History.imageFile(this, filename).getAbsolutePath();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 8;
        Bitmap thumbnail = BitmapFactory.decodeFile(path, options);
        if (thumbnail == null) return;
        ImageView preview = new ImageView(this);
        preview.setImageBitmap(thumbnail);
        preview.setContentDescription("查看开屏广告截图");
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(dp(48), dp(76));
        position.setMarginStart(dp(12));
        card.addView(preview, position);
        preview.setOnClickListener(view -> {
            ImageView full = new ImageView(this);
            full.setImageBitmap(BitmapFactory.decodeFile(path));
            full.setAdjustViewBounds(true);
            new android.app.AlertDialog.Builder(this)
                .setTitle("开屏广告截图").setView(full).setPositiveButton("关闭", null).show();
        });
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout card(LinearLayout list) {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(16));
        card.setBackground(background);
        LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(-1, -2);
        position.bottomMargin = dp(10);
        list.addView(card, position);
        return card;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
