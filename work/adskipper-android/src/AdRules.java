package local.adskipper;

import android.content.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class AdRules {
    final Pattern skipText;
    final Pattern promotionCloseText;
    final Pattern promotionActionText;
    final String[] baiduSkipIds;
    final String baiduClose;
    final String baiduSwipeAdText;
    final double baiduSwipeMinX;
    final double baiduSwipeMaxY;
    final double baiduSwipeMaxWidth;
    final String didiClose;
    final String didiParent;
    final String didiPanel;
    final String didiPanelDescription;
    final String didiDeliveryClose;
    final String didiDeliveryCloseDescription;
    final String didiDeliveryContainer;
    final String railwaySkip;
    final String railwayAd;
    final String railwayAdDescription;
    final String chinaMobileSkip;
    final String ctripClose;
    final String ctripAdDescription;
    final String ctripCtaText;
    final String ctripSkipId;
    final String ctripSkipText;
    final String zhixingSkipId;
    final String zhixingSkipText;
    final String zhixingCloseText;
    final String zhixingNativeCloseId;
    final String marketAdPackage;
    final Pattern marketSkipText;
    private final Map<String, String> names = new HashMap<>();

    static AdRules load(Context context) throws IOException, JSONException {
        Map<String, JSONObject> byId = new HashMap<>();
        readRules(context, "common.json", "*", byId);
        readRules(context, "com.baidu.netdisk.json", "com.baidu.netdisk", byId);
        readRules(context, "com.sdu.didi.psnger.json", "com.sdu.didi.psnger", byId);
        readRules(context, "com.MobileTicket.json", "com.MobileTicket", byId);
        readRules(context, "com.greenpoint.android.mc10086.activity.json", "com.greenpoint.android.mc10086.activity", byId);
        readRules(context, "ctrip.android.view.json", "ctrip.android.view", byId);
        readRules(context, "com.xiaomi.market.json", "com.xiaomi.market", byId);
        readRules(context, "com.yipiao.json", "com.yipiao", byId);
        return new AdRules(byId);
    }

    private static void readRules(Context context, String filename, String app,
            Map<String, JSONObject> byId) throws IOException, JSONException {
        StringBuilder json = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(filename), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) json.append(line).append('\n');
        }
        JSONObject document = new JSONObject(json.toString());
        if (document.getInt("version") != 1 || !app.equals(document.getString("app"))) {
            throw new JSONException("Unexpected rule document: " + filename);
        }
        JSONArray entries = document.getJSONArray("rules");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject rule = entries.getJSONObject(i);
            if ("click".equals(rule.getString("action"))) {
                rule.put("app", app);
                byId.put(rule.getString("id"), rule);
            }
        }
    }

    private AdRules(Map<String, JSONObject> rules) throws JSONException {
        for (Map.Entry<String, JSONObject> rule : rules.entrySet()) {
            names.put(rule.getKey(), rule.getValue().optString("name", rule.getKey()));
        }
        skipText = Pattern.compile(target(rules, "generic-skip", "*").getString("textPattern"));
        JSONObject promotion = target(rules, "generic-promotion", "*");
        promotionCloseText = Pattern.compile(promotion.getString("closeTextPattern"));
        promotionActionText = Pattern.compile(promotion.getString("promotionTextPattern"));
        JSONArray ids = target(rules, "baidu-splash", "com.baidu.netdisk").getJSONArray("viewIds");
        baiduSkipIds = new String[] {ids.getString(0), ids.getString(1)};
        baiduClose = target(rules, "baidu-promotion", "com.baidu.netdisk").getString("viewId");
        JSONObject swipeTarget = target(rules, "baidu-swipe-ad", "com.baidu.netdisk");
        baiduSwipeMinX = swipeTarget.getDouble("minCenterX");
        baiduSwipeMaxY = swipeTarget.getDouble("maxCenterY");
        baiduSwipeMaxWidth = swipeTarget.getDouble("maxWidth");
        baiduSwipeAdText = rules.get("baidu-swipe-ad").getJSONObject("conditions").getString("adText");
        didiClose = target(rules, "didi-promotion", "com.sdu.didi.psnger").getString("viewId");
        JSONObject panel = rules.get("didi-promotion").getJSONObject("conditions");
        didiParent = panel.getString("parentViewId");
        didiPanel = panel.getString("panelViewId");
        didiPanelDescription = panel.getString("panelDescription");
        JSONObject delivery = target(rules, "didi-delivery-promotion", "com.sdu.didi.psnger");
        didiDeliveryClose = delivery.getString("viewId");
        didiDeliveryCloseDescription = delivery.getString("description");
        JSONObject deliveryConditions = rules.get("didi-delivery-promotion").getJSONObject("conditions");
        didiDeliveryContainer = deliveryConditions.getString("containerViewId");
        railwaySkip = target(rules, "railway-splash", "com.MobileTicket").getString("viewId");
        JSONObject ad = rules.get("railway-splash").getJSONObject("conditions");
        railwayAd = ad.getString("adViewId");
        railwayAdDescription = ad.getString("adDescription");
        chinaMobileSkip = target(rules, "china-mobile-splash", "com.greenpoint.android.mc10086.activity").getString("viewId");
        ctripClose = target(rules, "ctrip-promotion", "ctrip.android.view").getString("viewId");
        JSONObject ctrip = rules.get("ctrip-promotion").getJSONObject("conditions");
        ctripAdDescription = ctrip.getString("adDescription");
        ctripCtaText = ctrip.getString("ctaText");
        JSONObject ctripSplash = target(rules, "ctrip-splash", "ctrip.android.view");
        ctripSkipId = ctripSplash.getString("viewId");
        ctripSkipText = ctripSplash.getString("text");
        JSONObject zhixing = target(rules, "zhixing-splash", "com.yipiao");
        zhixingSkipId = zhixing.getString("viewId");
        zhixingSkipText = zhixing.getString("text");
        zhixingCloseText = target(rules, "zhixing-promotion", "com.yipiao").getString("text");
        zhixingNativeCloseId = target(rules, "zhixing-native-promotion", "com.yipiao").getString("viewId");
        JSONObject market = target(rules, "xiaomi-market-splash", "com.xiaomi.market");
        marketAdPackage = market.getString("windowPackage");
        marketSkipText = Pattern.compile(market.getString("textPattern"));
    }

    String name(String id) {
        return names.getOrDefault(id, id);
    }

    private static JSONObject target(Map<String, JSONObject> rules, String id, String app)
            throws JSONException {
        JSONObject rule = rules.get(id);
        if (rule == null || !app.equals(rule.optString("app"))) {
            throw new JSONException("Missing rule: " + id);
        }
        return rule.getJSONObject("target");
    }
}
