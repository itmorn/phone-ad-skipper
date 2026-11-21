package local.adskipper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;

public class SkipService extends AccessibilityService {
    private static final String TAG = "PhoneAdSkipper";
    private static final String BAIDU = "com.baidu.netdisk";
    private static final String DIDI = "com.sdu.didi.psnger";
    private static final String CTRIP = "ctrip.android.view";
    private static final String MARKET = "com.xiaomi.market";
    private static final String RAILWAY = "com.MobileTicket";
    private static final String CHINA_MOBILE = "com.greenpoint.android.mc10086.activity";
    private static final String LAUNCHER = "com.miui.home";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final int SCREENSHOT_WAIT_MS = 80;
    private AdRules rules;
    private String foregroundPackage = "";
    private int baiduHandledWindow = -1;
    private long deadline;
    private boolean clicked;
    private int launch;
    private final Set<String> reported = new HashSet<>();
    private final Handler handler = new Handler(android.os.Looper.getMainLooper());
    private int promoWindow = -1;
    private TextView interceptNotice;
    private HandlerThread noticeThread;
    private Handler noticeHandler;
    private final Runnable hideInterceptNotice = this::removeInterceptNotice;
    private long sampleUntil;
    private int sampleInterval;
    private String samplingPackage = "";
    private final WindowTrace windowTrace = new WindowTrace();
    private boolean capturePending;
    private volatile boolean interceptEnabled;
    private android.content.SharedPreferences preferences;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener interceptSettingChanged =
        (prefs, key) -> {
            if (MainActivity.INTERCEPT_ENABLED.equals(key)) updateInterceptEnabled();
        };
    private volatile boolean destroyed;
    private final ExecutorService screenshots = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService screenshotDeadline = Executors.newSingleThreadScheduledExecutor();
    private Runnable traceAfterCapture;
    private int baiduOpeningWindow = -1;
    private final Runnable sampleForeground = new Runnable() {
        @Override public void run() {
            if (SystemClock.uptimeMillis() >= sampleUntil || !isSelected(samplingPackage)) return;
            long started = SystemClock.uptimeMillis();
            readForeground("补查");
            long now = SystemClock.uptimeMillis();
            long next = Math.max(started + sampleInterval, now + 1);
            if (next < sampleUntil) handler.postAtTime(this, next);
        }
    };

    private AccessibilityNodeInfo focusedApplicationRoot() {
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused()) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) return root;
            }
        }
        return getRootInActiveWindow();
    }

    private void updateInterceptEnabled() {
        boolean enabled = preferences.getBoolean(MainActivity.INTERCEPT_ENABLED, true);
        if (interceptEnabled == enabled) return;
        interceptEnabled = enabled;
        stopSampling();
        launch++;
        deadline = 0;
        foregroundPackage = "";
        baiduOpeningWindow = -1;
        promoWindow = -1;
        resetAttempt();
        reported.clear();
        traceAfterCapture = null;
        if (!enabled && noticeHandler != null) {
            noticeHandler.removeCallbacksAndMessages(null);
            noticeHandler.post(this::removeInterceptNotice);
        }
    }

    @Override protected void onServiceConnected() {
        preferences = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener(interceptSettingChanged);
        updateInterceptEnabled();
        try {
            rules = AdRules.load(this);
            Log.i(TAG, "service connected; loaded common and per-app rules");
        } catch (java.io.IOException | org.json.JSONException | IllegalArgumentException error) {
            rules = null;
            Log.e(TAG, "Could not load rule files", error);
        }
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(interceptSettingChanged);
        screenshotDeadline.shutdownNow();
        screenshots.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        if (noticeThread != null) {
            noticeHandler.removeCallbacksAndMessages(null);
            noticeHandler.post(this::removeInterceptNotice);
            noticeThread.quitSafely();
        }
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!interceptEnabled || capturePending) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        CharSequence eventPackage = event.getPackageName();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && LAUNCHER.equals(String.valueOf(eventPackage))
                && "com.miui.home.launcher.Launcher".equals(String.valueOf(event.getClassName()))) {
            AccessibilityNodeInfo focused = focusedApplicationRoot();
            if (focused != null && LAUNCHER.contentEquals(focused.getPackageName())) {
                enterDesktop("窗口事件", focused.getWindowId());
            } else {
                Log.i(TAG, "desktop event ignored; launcher window not focused");
            }
            return;
        }
        boolean packageChanged = eventPackage != null && !SYSTEM_UI.contentEquals(eventPackage)
            && !foregroundPackage.contentEquals(eventPackage);
        boolean newBaiduOpening = false;
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && BAIDU.contentEquals(
                eventPackage == null ? "" : eventPackage)) {
            String name = String.valueOf(event.getClassName());
            if ("com.baidu.netdisk.ui.Navigate".equals(name) && event.getWindowId() != baiduOpeningWindow) {
                baiduOpeningWindow = event.getWindowId();
                foregroundPackage = "";
                launch++;
                newBaiduOpening = true;
            }
        }
        if ((newBaiduOpening || (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && packageChanged))
                && eventPackage != null && isSelected(eventPackage.toString())) {
            startSampling(eventPackage.toString());
        }
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED && SystemClock.uptimeMillis() < sampleUntil) return;
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && CHINA_MOBILE.contentEquals(
                eventPackage == null ? "" : eventPackage) && isSelected(CHINA_MOBILE)
                && "com.mc10086.cmcc.base.StartPageActivity".equals(String.valueOf(event.getClassName()))) {
            AccessibilityNodeInfo node = event.getSource();
            if (node != null && CHINA_MOBILE.contentEquals(node.getPackageName())) {
                for (int depth = 0; depth < 8; depth++) {
                    AccessibilityNodeInfo parent = node.getParent();
                    if (parent == null || parent.getWindowId() != event.getWindowId()) break;
                    node = parent;
                }
                startSampling(CHINA_MOBILE);
                readForeground("中国移动启动窗口", node, SystemClock.uptimeMillis());
                return;
            }
            Log.i(TAG, "China Mobile start event did not expose a window tree");
        }
        readForeground("窗口事件");
    }

    private void startSampling(String source) {
        samplingPackage = source;
        sampleInterval = MainActivity.sampleInterval(this);
        sampleUntil = SystemClock.uptimeMillis() + MainActivity.sampleDuration(this) * 1000L;
        handler.removeCallbacks(sampleForeground);
        handler.postDelayed(sampleForeground, sampleInterval);
    }

    private void readForeground(String trigger) {
        if (!interceptEnabled || capturePending) return;
        long started = SystemClock.uptimeMillis();
        readForeground(trigger, focusedApplicationRoot(), started);
    }

    private void readForeground(String trigger, AccessibilityNodeInfo root, long started) {
        String source = root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (SYSTEM_UI.equals(source) && LAUNCHER.equals(foregroundPackage)) return;
        if (LAUNCHER.equals(source)) {
            if (!foregroundPackage.isEmpty() && !LAUNCHER.equals(foregroundPackage)) {
                enterDesktop(trigger, root.getWindowId());
            }
            return;
        }
        boolean changed = !source.isEmpty() && !source.equals(foregroundPackage);
        boolean selected = !source.isEmpty() && isSelected(source);
        if (!source.isEmpty() && !selected) {
            if (!changed) return;
            stopSampling();
        } else if (changed && (SystemClock.uptimeMillis() >= sampleUntil || !samplingPackage.equals(source))) {
            startSampling(source);
        }
        String result = source.isEmpty() ? "未读到窗口" : appLabel(source) + " (" + source + ")";
        if (!source.isEmpty() && !selected) result += " · 未勾选";
        Log.i(TAG, "foreground sample: " + trigger + " | " + result
            + " | 窗口 " + (root == null ? "-" : root.getWindowId())
            + " | 读取 " + (SystemClock.uptimeMillis() - started) + " ms");
        long timestamp = System.currentTimeMillis();
        inspectActiveWindow(root);
        if (SystemClock.uptimeMillis() < sampleUntil
                && (selected || (source.isEmpty() && isSelected(samplingPackage)))) {
            if (capturePending) {
                traceAfterCapture = () -> windowTrace.record(root, trigger, timestamp);
            } else {
                windowTrace.record(root, trigger, timestamp);
            }
        }
    }

    private void enterDesktop(String trigger, int windowId) {
        stopSampling();
        if (!LAUNCHER.equals(foregroundPackage)) {
            Log.i(TAG, "foreground sample: " + trigger + " | 系统桌面 (" + LAUNCHER
                + ") | 窗口 " + windowId);
            foregroundPackage = LAUNCHER;
            baiduOpeningWindow = -1;
            launch++;
        }
    }

    private void stopSampling() {
        sampleUntil = 0;
        samplingPackage = "";
        handler.removeCallbacks(sampleForeground);
    }

    private boolean isSelected(String source) {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getBoolean(source, false)
            || isMarketAdWindow(source);
    }

    private boolean isMarketAdWindow(String source) {
        return rules != null && rules.marketAdPackage.equals(source);
    }

    private boolean matchesMarketSkip(AccessibilityNodeInfo node) {
        return rules.marketSkipText.matcher(String.valueOf(node.getText()).trim()).matches()
            || rules.marketSkipText.matcher(String.valueOf(node.getContentDescription()).trim()).matches();
    }

    private void inspectActiveWindow(AccessibilityNodeInfo root) {
        if (root == null || root.getPackageName() == null) return;
        String source = root.getPackageName().toString();
        if (!source.equals(foregroundPackage)) {
            if (!BAIDU.equals(source) && BAIDU.equals(foregroundPackage)) {
                baiduOpeningWindow = -1;
            }
            foregroundPackage = source;

            launch++;
            deadline = sampleUntil;
            clicked = false;
            baiduHandledWindow = -1;
            promoWindow = -1;
            reported.clear();
        }
        if (rules == null || !isSelected(source)) return;

        Rect page = new Rect();
        root.getBoundsInScreen(page);
        if (page.isEmpty()) return;
        if (DIDI.equals(source) && (closeDidiDelivery(root, page) || closeDidiPromotion(root, page))) return;
        if (CTRIP.equals(source) && closeCtripPromotion(root, page)) return;
        if ("com.yipiao".equals(source) && closeZhixingPromotion(root, page)) return;
        if (SystemClock.uptimeMillis() <= deadline && closeGenericPromotion(root, page, source)) return;
        if (RAILWAY.equals(source) && !clicked && SystemClock.uptimeMillis() <= deadline
                && skipRailwaySplash(root, page)) return;

        if (BAIDU.equals(source) && baiduHandledWindow != root.getWindowId()) {
            for (String id : rules.baiduSkipIds) {
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(id)) {
                    baiduHandledWindow = root.getWindowId();
                    if (trySkip(node, page, source)) return;
                    baiduHandledWindow = -1;
                }
            }
        }
        if (clicked && !BAIDU.equals(source)) return;
        boolean insideStartup = SystemClock.uptimeMillis() <= deadline;
        if (CHINA_MOBILE.equals(source) && !clicked && insideStartup) {
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(rules.chinaMobileSkip)) {
                if (trySkip(node, page, source, true)) return;
            }
        }
        if (BAIDU.equals(source) && !clicked && insideStartup && skipBaiduSwipeAd(root)) return;
        if (!insideStartup && !BAIDU.equals(source)) return;
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
        nodes.add(root);
        for (int examined = 0; !nodes.isEmpty() && examined < 250; examined++) {
            AccessibilityNodeInfo node = nodes.removeFirst();
            if (CHINA_MOBILE.equals(source) && !clicked && insideStartup
                    && rules.chinaMobileSkip.equals(node.getViewIdResourceName())
                    && trySkip(node, page, source, true)) return;
            if (BAIDU.equals(source)) {
                reportControl(node);
                if (closeBaiduPromotion(node, page)) return;
                if (baiduHandledWindow != root.getWindowId() && isBaiduSkip(node)) {
                    baiduHandledWindow = root.getWindowId();
                    if (trySkip(node, page, source)) return;
                    baiduHandledWindow = -1;
                }
            }
            if (!clicked && insideStartup
                    && (!isMarketAdWindow(source) || matchesMarketSkip(node))
                    && trySkip(node, page, source)) return;
            for (int i = 0; i < node.getChildCount() && nodes.size() < 250; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) nodes.addLast(child);
            }
        }
    }

    private String appLabel(String source) {
        String name = source;
        android.content.pm.PackageManager packages = getPackageManager();
        try {
            name = packages.getApplicationLabel(packages.getApplicationInfo(source, 0)).toString();
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
        return name;
    }

    private boolean isBaiduSkip(AccessibilityNodeInfo node) {
        String id = node.getViewIdResourceName();
        return rules.baiduSkipIds[0].equals(id) || rules.baiduSkipIds[1].equals(id);
    }

    private AccessibilityNodeInfo findBaiduSwipeButton(AccessibilityNodeInfo root) {
        Rect page = new Rect();
        root.getBoundsInScreen(page);
        if (page.isEmpty()) return null;
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
        nodes.add(root);
        AccessibilityNodeInfo button = null;
        boolean ad = false;
        Rect bounds = new Rect();
        for (int examined = 0; !nodes.isEmpty(); examined++) {
            if (examined >= 250) return null;
            AccessibilityNodeInfo node = nodes.removeFirst();
            if (node.isVisibleToUser()) {
                if (rules.baiduSwipeAdText.equals(String.valueOf(node.getText()))) ad = true;
                if (node.isClickable() && node.isEnabled() && nearCorner(node, page)) {
                    node.getBoundsInScreen(bounds);
                    if (bounds.centerX() >= page.left + page.width() * rules.baiduSwipeMinX
                            && bounds.centerY() <= page.top + page.height() * rules.baiduSwipeMaxY
                            && bounds.width() <= page.width() * rules.baiduSwipeMaxWidth) {
                        if (button != null) return null;
                        button = node;
                    }
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) nodes.addLast(child);
            }
        }
        return ad ? button : null;
    }

    private boolean skipBaiduSwipeAd(AccessibilityNodeInfo root) {
        if (findBaiduSwipeButton(root) == null) return false;
        int session = launch;
        int window = root.getWindowId();
        clicked = true;
        Log.i(TAG, "skip candidate app=" + BAIDU + " rule=baidu-swipe-ad");
        captureWindow(window, BAIDU, image -> {
            if (session != launch) return;
            AccessibilityNodeInfo current = focusedApplicationRoot();
            if (current != null && current.getWindowId() == window
                    && BAIDU.contentEquals(current.getPackageName())) {
                AccessibilityNodeInfo button = findBaiduSwipeButton(current);
                if (button != null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "skip action accepted app=" + BAIDU + " seconds=-1 rule=baidu-swipe-ad");
                    recordIntercept(BAIDU, -1, image, "baidu-swipe-ad");
                    return;
                }
            }
            clicked = false;
            Log.i(TAG, "skip not sent app=" + BAIDU + " rule=baidu-swipe-ad");
        });
        return true;
    }

    private boolean skipRailwaySplash(AccessibilityNodeInfo root, Rect page) {
        boolean adVisible = false;
        for (AccessibilityNodeInfo ad : root.findAccessibilityNodeInfosByViewId(rules.railwayAd)) {
            if (ad.isVisibleToUser() && rules.railwayAdDescription.equals(String.valueOf(ad.getContentDescription()))) {
                adVisible = true;
                break;
            }
        }
        if (!adVisible) return false;
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(rules.railwaySkip)) {
            if (trySkip(node, page, RAILWAY, true)) return true;
        }
        return false;
    }

    private boolean closeBaiduPromotion(AccessibilityNodeInfo button, Rect page) {
        if (promoWindow == button.getWindowId()
                || !rules.baiduClose.equals(button.getViewIdResourceName())) return false;
        Rect close = new Rect();
        button.getBoundsInScreen(close);
        if (!button.isVisibleToUser() || !button.isClickable() || close.isEmpty()
                || close.centerX() < page.left + page.width() * .75
                || close.centerY() < page.top + page.height() * .15
                || close.centerY() > page.top + page.height() * .35
                || close.width() > page.width() * .12) return false;
        return closePromotion(button, BAIDU, "baidu-promotion");
    }

    private boolean closeDidiPromotion(AccessibilityNodeInfo root, Rect page) {
        if (promoWindow == root.getWindowId()) return false;
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
        nodes.add(root);
        for (int count = 0; count < 40 && !nodes.isEmpty(); count++) {
            AccessibilityNodeInfo node = nodes.removeFirst();
            if (rules.didiParent.equals(node.getViewIdResourceName())) {
                AccessibilityNodeInfo button = null;
                Rect panel = new Rect();
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child == null || !child.isVisibleToUser()) continue;
                    if (rules.didiPanel.equals(child.getViewIdResourceName())
                            && rules.didiPanelDescription.equals(String.valueOf(child.getContentDescription()))) {
                        child.getBoundsInScreen(panel);
                    }
                    if (rules.didiClose.equals(child.getViewIdResourceName())
                            && child.isClickable() && child.isEnabled()) button = child;
                }
                if (button == null || panel.isEmpty()) continue;
                Rect close = new Rect();
                button.getBoundsInScreen(close);
                if (!close.isEmpty() && close.centerX() > page.left + page.width() * .85
                        && close.centerY() >= panel.top && close.centerY() < panel.top + page.height() * .10
                        && close.width() < page.width() * .15) return closePromotion(button, DIDI, "didi-promotion");
            }
            for (int i = 0; i < node.getChildCount() && nodes.size() < 40; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) nodes.addLast(child);
            }
        }
        return false;
    }

    private boolean closeDidiDelivery(AccessibilityNodeInfo root, Rect page) {
        if (promoWindow == root.getWindowId()) return false;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo container = root.getChild(i);
            if (container == null || !rules.didiDeliveryContainer.equals(container.getViewIdResourceName())) continue;
            for (int j = 0; j < container.getChildCount(); j++) {
                AccessibilityNodeInfo button = container.getChild(j);
                if (button == null || !rules.didiDeliveryClose.equals(button.getViewIdResourceName())
                        || !rules.didiDeliveryCloseDescription.equals(String.valueOf(button.getContentDescription()))
                        || !button.isVisibleToUser() || !button.isEnabled() || !button.isClickable()) continue;
                Rect close = new Rect();
                button.getBoundsInScreen(close);
                double centerY = (double) (close.centerY() - page.top) / page.height();
                if (!close.isEmpty() && Math.abs(close.centerX() - page.centerX()) < page.width() * .10
                        && centerY >= .65 && centerY <= .85 && close.width() <= page.width() * .16) {
                    return closePromotion(button, DIDI, "didi-delivery-promotion");
                }
            }
        }
        return false;
    }

    private boolean closeCtripPromotion(AccessibilityNodeInfo root, Rect page) {
        if (promoWindow == root.getWindowId()) return false;
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
        ArrayDeque<AccessibilityNodeInfo> buttons = new ArrayDeque<>();
        nodes.add(root);
        Rect panel = new Rect();
        Rect cta = new Rect();
        for (int count = 0; count < 100 && !nodes.isEmpty(); count++) {
            AccessibilityNodeInfo node = nodes.removeFirst();
            if (node.isVisibleToUser()) {
                if (rules.ctripAdDescription.equals(String.valueOf(node.getContentDescription()))) {
                    node.getBoundsInScreen(panel);
                }
                if (rules.ctripCtaText.equals(String.valueOf(node.getText()))) node.getBoundsInScreen(cta);
                if (node.isEnabled() && node.isClickable()
                        && rules.ctripClose.equals(node.getViewIdResourceName())) buttons.addLast(node);
            }
            for (int i = 0; i < node.getChildCount() && nodes.size() < 100; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) nodes.addLast(child);
            }
        }
        if (panel.isEmpty() || cta.isEmpty() || !panel.contains(cta)) return false;
        AccessibilityNodeInfo match = null;
        Rect bounds = new Rect();
        for (AccessibilityNodeInfo button : buttons) {
            button.getBoundsInScreen(bounds);
            if (bounds.isEmpty() || bounds.width() > page.width() * .12 || bounds.height() > page.height() * .08
                    || bounds.bottom > panel.top || panel.top - bounds.bottom > page.height() * .08
                    || Math.abs(bounds.centerX() - panel.right) > page.width() * .12) continue;
            if (match != null) return false;
            match = button;
        }
        return match != null && closePromotion(match, CTRIP, "ctrip-promotion");
    }

    private boolean closeGenericPromotion(AccessibilityNodeInfo root, Rect page, String source) {
        if (promoWindow == root.getWindowId()) return false;
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
        nodes.add(root);
        AccessibilityNodeInfo match = null;
        for (int count = 0; count < 120 && !nodes.isEmpty(); count++) {
            AccessibilityNodeInfo node = nodes.removeFirst();
            if (isGenericPromotionClose(node, page)) {
                if (match != null) return false;
                match = node;
            }
            for (int i = 0; i < node.getChildCount() && nodes.size() < 120; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) nodes.addLast(child);
            }
        }
        return match != null && closePromotion(match, source, "generic-promotion");
    }

    private boolean isGenericPromotionClose(AccessibilityNodeInfo node, Rect page) {
        if (!node.isVisibleToUser() || !node.isEnabled() || !node.isClickable()
                || !(rules.promotionCloseText.matcher(String.valueOf(node.getText()).trim()).matches()
                    || rules.promotionCloseText.matcher(String.valueOf(node.getContentDescription()).trim()).matches())) {
            return false;
        }
        Rect button = new Rect();
        node.getBoundsInScreen(button);
        if (button.isEmpty() || !page.contains(button) || button.width() > page.width() * .20
                || button.height() > page.height() * .10) return false;
        Rect panel = new Rect();
        AccessibilityNodeInfo parent = node.getParent();
        for (int depth = 0; depth < 3 && parent != null; depth++, parent = parent.getParent()) {
            parent.getBoundsInScreen(panel);
            if (panel.width() < page.width() * .40 || panel.height() < page.height() * .20
                    || panel.height() > page.height() * .85 || !panel.contains(button)) continue;
            boolean nearEdge = (Math.abs(button.centerX() - panel.centerX()) <= panel.width() * .15
                    && panel.bottom - button.bottom <= panel.height() * .12)
                || (panel.right - button.right <= panel.width() * .15
                    && button.top - panel.top <= panel.height() * .12);
            if (nearEdge && hasPromotionAction(parent, panel, button)) return true;
        }
        return false;
    }

    private boolean hasPromotionAction(AccessibilityNodeInfo panel, Rect panelBounds, Rect closeBounds) {
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
        nodes.add(panel);
        Rect action = new Rect();
        for (int count = 0; count < 80 && !nodes.isEmpty(); count++) {
            AccessibilityNodeInfo node = nodes.removeFirst();
            if (node.isVisibleToUser()
                    && (rules.promotionActionText.matcher(String.valueOf(node.getText()).trim()).matches()
                        || rules.promotionActionText.matcher(String.valueOf(node.getContentDescription()).trim()).matches())) {
                node.getBoundsInScreen(action);
                if (!action.isEmpty() && panelBounds.contains(action) && !Rect.intersects(action, closeBounds)) {
                    return true;
                }
            }
            for (int i = 0; i < node.getChildCount() && nodes.size() < 80; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) nodes.addLast(child);
            }
        }
        return false;
    }

    private boolean closeZhixingPromotion(AccessibilityNodeInfo root, Rect page) {
        if (promoWindow == root.getWindowId()) return false;
        AccessibilityNodeInfo nativeButton = findZhixingNativeClose(root, page);
        if (nativeButton != null) return closePromotion(nativeButton, "com.yipiao", "zhixing-native-promotion");
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(rules.zhixingCloseText)) {
            if (isZhixingClose(node, page)) return closePromotion(node, "com.yipiao", "zhixing-promotion");
        }
        return false;
    }

    private AccessibilityNodeInfo findZhixingNativeClose(AccessibilityNodeInfo root, Rect page) {
        if (page.isEmpty() || root.getChildCount() != 2
                || !"android.widget.FrameLayout".equals(String.valueOf(root.getClassName()))) return null;
        AccessibilityNodeInfo action = root.getChild(0);
        AccessibilityNodeInfo close = root.getChild(1);
        if (action == null || close == null || !action.isVisibleToUser() || !action.isClickable()
                || !close.isVisibleToUser() || !close.isEnabled() || !close.isClickable()
                || !"android.view.View".equals(String.valueOf(action.getClassName()))
                || !"android.widget.ImageView".equals(String.valueOf(close.getClassName()))
                || !rules.zhixingNativeCloseId.equals(action.getViewIdResourceName())
                || !rules.zhixingNativeCloseId.equals(close.getViewIdResourceName())) return null;
        Rect actionBounds = new Rect();
        Rect closeBounds = new Rect();
        action.getBoundsInScreen(actionBounds);
        close.getBoundsInScreen(closeBounds);
        if (closeBounds.isEmpty() || !page.contains(closeBounds) || !page.contains(actionBounds)
                || actionBounds.width() < page.width() * .50
                || closeBounds.width() > page.width() * .15 || closeBounds.height() > page.height() * .08
                || Math.abs(closeBounds.centerX() - page.centerX()) > page.width() * .10
                || closeBounds.centerY() < page.top + page.height() * .68
                || closeBounds.centerY() > page.top + page.height() * .88
                || closeBounds.top < actionBounds.bottom
                || closeBounds.top - actionBounds.bottom > page.height() * .15) return null;
        return close;
    }

    private boolean isZhixingClose(AccessibilityNodeInfo node, Rect page) {
        if (!node.isEnabled() || !node.isVisibleToUser()
                || !"android.widget.Image".contentEquals(String.valueOf(node.getClassName()))
                || !rules.zhixingCloseText.equals(String.valueOf(node.getText()))) return false;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return !bounds.isEmpty() && page.contains(bounds)
            && Math.abs(bounds.centerX() - page.centerX()) <= page.width() * .10
            && bounds.centerY() >= page.top + page.height() * .70
            && bounds.centerY() <= page.top + page.height() * .90
            && bounds.width() <= page.width() * .15;
    }

    private boolean closePromotion(AccessibilityNodeInfo button, String source, String ruleId) {
        promoWindow = button.getWindowId();
        int session = launch;
        captureWindow(promoWindow, source, image -> {
            if (session != launch) return;
            AccessibilityNodeInfo root = focusedApplicationRoot();
            if (root != null && source.contentEquals(root.getPackageName())
                    && root.getWindowId() == button.getWindowId() && button.refresh()
                    && button.isVisibleToUser()) {
                Rect page = new Rect();
                root.getBoundsInScreen(page);
                AccessibilityNodeInfo target = "zhixing-native-promotion".equals(ruleId)
                    ? findZhixingNativeClose(root, page) : button;
                if (target != null && !"zhixing-promotion".equals(ruleId)
                        && (!"generic-promotion".equals(ruleId) || isGenericPromotionClose(button, page))
                        && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "promotion closed app=" + source + " rule=" + ruleId);
                    recordIntercept(source, -1, image, ruleId);
                    return;
                }
                if ("zhixing-promotion".equals(ruleId) && isZhixingClose(button, page)) {
                    Rect bounds = new Rect();
                    button.getBoundsInScreen(bounds);
                    Path touch = new Path();
                    touch.moveTo(bounds.centerX(), bounds.centerY());
                    GestureDescription gesture = new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(touch, 0, 40)).build();
                    if (dispatchGesture(gesture, new GestureResultCallback() {
                        @Override public void onCompleted(GestureDescription gesture) {
                            if (!interceptEnabled || session != launch) return;
                            Log.i(TAG, "promotion tap completed app=" + source + " rule=" + ruleId);
                            recordIntercept(source, -1, image, ruleId);
                        }

                        @Override public void onCancelled(GestureDescription gesture) {
                            if (session == launch) promoWindow = -1;
                        }
                    }, null)) return;
                }
            }
            promoWindow = -1;
            if (reported.add("promo_click_failed")) Log.i(TAG, "promotion close rejected app=" + source);
        });
        return true;
    }

    private void reportControl(AccessibilityNodeInfo node) {
        String id = node.getViewIdResourceName();
        if (id == null || !(id.contains("close") || id.contains("dialog") || id.contains("splash")
                || id.contains("popup") || id.contains("skip"))) return;
        if (reported.size() >= 30 || !reported.add(id)) return;
        Log.i(TAG, "Baidu control id=" + id + " clickable=" + node.isClickable());
    }

    private boolean matchesZhixingSkip(AccessibilityNodeInfo node, String source) {
        return "com.yipiao".equals(source) && node.isEnabled()
            && rules.zhixingSkipId.equals(node.getViewIdResourceName())
            && rules.zhixingSkipText.equals(String.valueOf(node.getText()));
    }

    private boolean trySkip(AccessibilityNodeInfo node, Rect page, String source) {
        return trySkip(node, page, source, BAIDU.equals(source) && isBaiduSkip(node));
    }

    private boolean trySkip(AccessibilityNodeInfo node, Rect page, String source, boolean knownButton) {
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        boolean ctripSplash = CTRIP.equals(source) && rules.ctripSkipId.equals(node.getViewIdResourceName())
            && rules.ctripSkipText.equals(String.valueOf(text));
        boolean marketSplash = isMarketAdWindow(source) && matchesMarketSkip(node);
        boolean zhixingSplash = matchesZhixingSkip(node, source);
        if (marketSplash || zhixingSplash) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.centerX() < page.left + page.width() * .75
                    || bounds.centerY() > page.top + page.height() * .20) return false;
        }
        if (!node.isVisibleToUser() || !(knownButton || matches(text) || matches(description))
                || !nearCorner(node, page)) return false;
        AccessibilityNodeInfo target = clickTarget(node, page);
        if (target == null && !BAIDU.equals(source) && !ctripSplash && !marketSplash && !zhixingSplash) return false;
        Log.i(TAG, "skip candidate app=" + source + " id=" + node.getViewIdResourceName()
            + " clickableTarget=" + (target != null));
        clicked = true;
        int seconds = seconds(text);
        int remaining = seconds < 0 ? seconds(description) : seconds;
        if (knownButton && remaining < 0 && rules.baiduSkipIds[1].equals(node.getViewIdResourceName())) {
            for (AccessibilityNodeInfo label : node.findAccessibilityNodeInfosByViewId(rules.baiduSkipIds[0])) {
                remaining = seconds(label.getText());
                if (remaining >= 0) break;
            }
        }
        int attempt = launch;
        int recordedSeconds = remaining;
        String ruleId = zhixingSplash ? "zhixing-splash" : marketSplash ? "xiaomi-market-splash" : ctripSplash ? "ctrip-splash" : !knownButton ? "generic-skip"
            : RAILWAY.equals(source) ? "railway-splash"
            : CHINA_MOBILE.equals(source) ? "china-mobile-splash" : "baidu-splash";
        captureWindow(node.getWindowId(), source,
            image -> finishSkip(node, target, source, attempt, recordedSeconds, image, ruleId));
        return true;
    }

    private void captureWindow(int window, String source, Consumer<Bitmap> done) {
        if (Build.VERSION.SDK_INT < 34 || !getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getBoolean(MainActivity.INTERCEPT_SCREENSHOT, true)) {
            done.accept(null);
            return;
        }
        long requestedAt = SystemClock.elapsedRealtime();
        capturePending = true;
        int captureLaunch = launch;
        AtomicBoolean completed = new AtomicBoolean();
        Consumer<Bitmap> complete = image -> {
            if (destroyed || !completed.compareAndSet(false, true)) return;
            long elapsed = SystemClock.elapsedRealtime() - requestedAt;
            Bitmap timelyImage = elapsed <= SCREENSHOT_WAIT_MS ? image : null;
            Log.i(TAG, "screenshot wait app=" + source + " elapsedMs=" + elapsed
                + " captured=" + (timelyImage != null));
            handler.postAtFrontOfQueue(() -> {
                if (destroyed) return;
                capturePending = false;
                if (!interceptEnabled || captureLaunch != launch) return;
                try {
                    done.accept(timelyImage);
                } finally {
                    if (traceAfterCapture != null) {
                        Runnable trace = traceAfterCapture;
                        traceAfterCapture = null;
                        handler.post(() -> {
                            if (interceptEnabled && captureLaunch == launch) trace.run();
                        });
                    }
                }
            });
        };
        ScheduledFuture<?> timeout = screenshotDeadline.schedule(() -> complete.accept(null),
            SCREENSHOT_WAIT_MS, TimeUnit.MILLISECONDS);
        TakeScreenshotCallback callback = new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult screenshot) {
                    try (android.hardware.HardwareBuffer buffer = screenshot.getHardwareBuffer()) {
                        if (!completed.get()) {
                            complete.accept(Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace()));
                        }
                    } finally {
                        timeout.cancel(false);
                    }
                }

                @Override public void onFailure(int errorCode) {
                    complete.accept(null);
                    timeout.cancel(false);
                }
        };
        screenshots.execute(() -> {
            if (destroyed || completed.get()) return;
            try {
                takeScreenshotOfWindow(window, Runnable::run, callback);
            } catch (RuntimeException error) {
                Log.w(TAG, "screenshot request failed", error);
                complete.accept(null);
                timeout.cancel(false);
            }
        });
    }

    private void finishSkip(AccessibilityNodeInfo node, AccessibilityNodeInfo target, String source, int attempt,
                               int seconds, Bitmap image, String ruleId) {
        boolean marketSplash = "xiaomi-market-splash".equals(ruleId);
        if (marketSplash && !isMarketAdWindow(source)) return;
        if (attempt != launch) {
            Log.i(TAG, "skip cancelled app=" + source + " reason=foreground-changed");
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getWindowId() != node.getWindowId()) {
            root = focusedApplicationRoot();
        }
        if (root != null && source.contentEquals(root.getPackageName())
                && root.getWindowId() == node.getWindowId() && node.refresh()
                && node.isVisibleToUser() && ((BAIDU.equals(source) && isBaiduSkip(node))
                    || (RAILWAY.equals(source) && rules.railwaySkip.equals(node.getViewIdResourceName()))
                    || (CHINA_MOBILE.equals(source) && rules.chinaMobileSkip.equals(node.getViewIdResourceName()))
                    || matches(node.getText()) || matches(node.getContentDescription()))) {
            if (target != null && target.refresh() && target.isVisibleToUser()
                    && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "skip action accepted app=" + source + " seconds=" + seconds);
                recordIntercept(marketSplash ? MARKET : source, seconds, image, ruleId);
                return;
            }
            if (BAIDU.equals(source) || ("zhixing-splash".equals(ruleId) && matchesZhixingSkip(node, source))
                    || (marketSplash && matchesMarketSkip(node)) || ("ctrip-splash".equals(ruleId)
                    && rules.ctripSkipId.equals(node.getViewIdResourceName())
                    && rules.ctripSkipText.equals(String.valueOf(node.getText())))) {
                Rect button = new Rect();
                Rect page = new Rect();
                root.getBoundsInScreen(page);
                node.getBoundsInScreen(button);
                if (nearCorner(node, page)) {
                    Path touch = new Path();
                    touch.moveTo(button.centerX(), button.centerY());
                    GestureDescription gesture = new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(touch, 0, 40)).build();
                    if (dispatchGesture(gesture, new GestureResultCallback() {
                        @Override public void onCompleted(GestureDescription gesture) {
                            if (!interceptEnabled || attempt != launch) return;
                            Log.i(TAG, "skip tap completed app=" + source + " seconds=" + seconds);
                            recordIntercept(marketSplash ? MARKET : source, seconds, image, ruleId);
                        }

                        @Override public void onCancelled(GestureDescription gesture) {
                            Log.i(TAG, "skip tap cancelled app=" + source);
                            if (attempt == launch) resetAttempt();
                        }
                    }, null)) return;
                }
            }
        }
        Log.i(TAG, "skip not sent app=" + source + " window=" + node.getWindowId());
        resetAttempt();
    }

    private void resetAttempt() {
        clicked = false;
        baiduHandledWindow = -1;
    }

    private boolean matches(CharSequence label) {
        return label != null && rules.skipText.matcher(label.toString().trim()).matches();
    }

    private void recordIntercept(String source, int seconds, Bitmap image, String ruleId) {
        if (!interceptEnabled) return;
        String ruleName = rules.name(ruleId);
        if (getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getBoolean(MainActivity.INTERCEPT_NOTICE, true)) {
            if (noticeThread == null) {
                noticeThread = new HandlerThread("InterceptNotice");
                noticeThread.start();
                noticeHandler = new Handler(noticeThread.getLooper());
            }
            noticeHandler.post(() -> showInterceptNotice(ruleName));
        }
        History.record(this, source, seconds, image, ruleName);
    }

    private void showInterceptNotice(String ruleName) {
        if (!interceptEnabled) return;
        noticeHandler.removeCallbacks(hideInterceptNotice);
        removeInterceptNotice();
        TextView notice = new TextView(this);
        notice.setText("已拦截广告\n" + ruleName);
        notice.setTextColor(Color.WHITE);
        notice.setTextSize(14);
        notice.setGravity(Gravity.CENTER);
        notice.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(21, 66, 48));
        background.setCornerRadius(dp(14));
        notice.setBackground(background);

        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(
            -2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        layout.y = dp(32);
        layout.setTitle("AdSkipperInterceptNotice");
        try {
            getSystemService(WindowManager.class).addView(notice, layout);
            interceptNotice = notice;
            noticeHandler.postDelayed(hideInterceptNotice, 600);
            Log.i(TAG, "intercept notice attached rule=" + ruleName);
        } catch (WindowManager.BadTokenException | IllegalStateException error) {
            Log.w(TAG, "intercept notice could not be shown", error);
        }
    }

    private void removeInterceptNotice() {
        if (interceptNotice == null) return;
        getSystemService(WindowManager.class).removeViewImmediate(interceptNotice);
        interceptNotice = null;
        Log.i(TAG, "intercept notice removed");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int seconds(CharSequence label) {
        if (label == null) return -1;
        Matcher match = rules.skipText.matcher(label.toString().trim());
        if (!match.matches()) return -1;
        String seconds = match.group("seconds");
        if (seconds == null) seconds = match.group("leadingSeconds");
        return seconds == null ? -1 : Integer.parseInt(seconds);
    }

    private boolean nearCorner(AccessibilityNodeInfo node, Rect page) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return !bounds.isEmpty() && page.contains(bounds)
            && bounds.width() <= page.width() * 0.35 && bounds.height() <= page.height() * 0.12
            && (bounds.centerX() >= page.left + page.width() * 0.67
                || bounds.centerX() <= page.left + page.width() * 0.20)
            && (bounds.centerY() <= page.top + page.height() * 0.24
                || bounds.centerY() >= page.top + page.height() * 0.76);
    }

    private AccessibilityNodeInfo clickTarget(AccessibilityNodeInfo node, Rect page) {
        for (int depth = 0; depth < 3 && node != null && nearCorner(node, page); depth++, node = node.getParent()) {
            if (node.isVisibleToUser() && node.isEnabled() && node.isClickable()) return node;
        }
        return null;
    }

    @Override public void onInterrupt() {}
}
