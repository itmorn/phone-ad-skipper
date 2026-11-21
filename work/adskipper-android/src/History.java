package local.adskipper;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class History {
    static int savedSeconds(int remaining) {
        return remaining < 0 ? 5 : remaining;
    }

    static final class Savings {
        long seconds;
        long firstRecordedAt;

        void add(int remaining, long timestamp) {
            seconds += savedSeconds(remaining);
            if (timestamp > 0 && (firstRecordedAt == 0 || timestamp < firstRecordedAt)) {
                firstRecordedAt = timestamp;
            }
        }
    }

    static Savings savings(Context context, JSONArray entries) {
        SharedPreferences preferences = store(context);
        Savings savings = new Savings();
        if (preferences.contains("total_seconds")) {
            savings.seconds = preferences.getLong("total_seconds", 0);
            savings.firstRecordedAt = preferences.getLong("first_recorded_at", 0);
            if (!preferences.getBoolean("includes_estimates", false)) {
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.optJSONObject(i);
                    if (entry != null && entry.optInt("seconds", -1) < 0) {
                        savings.seconds += savedSeconds(-1);
                    }
                }
            }
            return savings;
        }
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry != null) savings.add(entry.optInt("seconds", -1), entry.optLong("time"));
        }
        return savings;
    }
    private static SharedPreferences store(Context context) {
        return context.getSharedPreferences("skip_history", Context.MODE_PRIVATE);
    }

    static JSONArray read(Context context) {
        try {
            return new JSONArray(store(context).getString("entries", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static File imageFile(Context context, String name) {
        return new File(context.getFilesDir(), name);
    }

    static void record(Context context, String packageName, int seconds, Bitmap image, String ruleName) {
        PackageManager packages = context.getPackageManager();
        String name = packageName;
        try {
            name = packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException ignored) {}

        JSONArray previous = read(context);
        JSONArray entries = new JSONArray();
        JSONObject entry = new JSONObject();
        try {
            entry.put("app", name);
            entry.put("package", packageName);
            entry.put("time", System.currentTimeMillis());
            entry.put("seconds", seconds);
            entry.put("ruleName", ruleName);
            if (image != null) {
                String filename = "splash-" + UUID.randomUUID() + ".jpg";
                File file = imageFile(context, filename);
                try (FileOutputStream output = new FileOutputStream(file)) {
                    if (image.compress(Bitmap.CompressFormat.JPEG, 82, output)) {
                        entry.put("image", filename);
                    }
                } catch (IOException e) {
                    file.delete();
                }
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        entries.put(entry);
        for (int i = 0; i < Math.min(previous.length(), 199); i++) {
            entries.put(previous.optJSONObject(i));
        }
        Savings savings = savings(context, previous);
        savings.add(seconds, entry.optLong("time"));
        store(context).edit()
            .putString("entries", entries.toString())
            .putLong("total_seconds", savings.seconds)
            .putLong("first_recorded_at", savings.firstRecordedAt)
            .putBoolean("includes_estimates", true)
            .apply();
        for (int i = 199; i < previous.length(); i++) {
            JSONObject expired = previous.optJSONObject(i);
            if (expired != null && expired.has("image")) {
                imageFile(context, expired.optString("image")).delete();
            }
        }
    }
}
