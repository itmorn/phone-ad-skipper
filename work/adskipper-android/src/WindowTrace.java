package local.adskipper;

import android.graphics.Rect;
import android.os.Process;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import org.json.JSONObject;

final class WindowTrace {
    private static final int MAX_NODES = 250;
    private int sequence;

    void record(AccessibilityNodeInfo root, String trigger, long timestamp) {
        String id = timestamp + "-" + Process.myPid() + "-" + (++sequence);
        write(id, "BEGIN trigger=" + trigger + " window=" + (root == null ? "unavailable" : root.getWindowId()));
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
        if (root != null) nodes.add(root);
        Rect bounds = new Rect();
        int count = 0;
        while (!nodes.isEmpty() && count < MAX_NODES) {
            AccessibilityNodeInfo node = nodes.removeFirst();
            node.getBoundsInScreen(bounds);
            write(id, "node=" + (++count) + " class=" + node.getClassName()
                + " id=" + node.getViewIdResourceName()
                + " text=" + quote(node.getText()) + " description=" + quote(node.getContentDescription())
                + " bounds=" + bounds.toShortString() + " visible=" + node.isVisibleToUser()
                + " clickable=" + node.isClickable() + " enabled=" + node.isEnabled());
            for (int i = 0; i < node.getChildCount() && count + nodes.size() < MAX_NODES; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) nodes.addLast(child);
            }
        }
        write(id, "END nodes=" + count + (count == MAX_NODES ? " capped=true" : ""));
    }

    private String quote(CharSequence value) {
        return value == null ? "null"
            : JSONObject.quote(value.subSequence(0, Math.min(value.length(), 256)).toString());
    }

    private void write(String id, String message) {
        Log.i("AdSkipTree", id + " " + message);
    }
}
