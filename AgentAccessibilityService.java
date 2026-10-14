package com.roohu.phoneagent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AgentAccessibilityService extends AccessibilityService {
    public static volatile AgentAccessibilityService instance;
    private static final String[] BLOCK = {"bank", "paypal", "wallet", "paytm", "phonepe", "easypaisa", "jazzcash", "paisa"};
    private static final String[] RISKY = {"send", "pay", "buy", "purchase", "delete", "remove", "install", "uninstall", "confirm", "transfer", "order", "submit", "post", "call"};
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> log = new ArrayList<>();
    private volatile boolean running = false;
    private volatile boolean stop = false;
    private volatile String lastScreen = "[]";
    private Thread worker;
    private View stopView;
    private WindowManager wm;

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (getSharedPreferences("roohu", MODE_PRIVATE).getBoolean("bot", false)) launchBot();
    }

    @Override
    public boolean onUnbind(Intent i) {
        instance = null;
        botOn = false;
        return super.onUnbind(i);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) { }

    @Override
    public void onInterrupt() { }

    private synchronized void addLog(String s) {
        log.add(s);
        if (log.size() > 200) log.remove(0);
    }

    public synchronized String getStatusJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", running);
            o.put("log", new JSONArray(log));
            o.put("screen", new JSONArray(lastScreen));
            o.put("bot", botOn);
            o.put("boterr", botErr);
            o.put("paired", getSharedPreferences("roohu", MODE_PRIVATE).getLong("paired", 0) != 0);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public void stopNow() {
        stop = true;
        Thread w = worker;
        if (w != null) w.interrupt();
    }

    private void walk(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int d) {
        if (n == null || d > 40 || out.size() > 400) return;
        if (n.isVisibleToUser()) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out, d + 1);
    }

    private List<AccessibilityNodeInfo> nodes() {
        List<AccessibilityNodeInfo> l = new ArrayList<>();
        walk(getRootInActiveWindow(), l, 0);
        return l;
    }

    private String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        return t == null ? "" : t.toString();
    }

    private boolean blocked() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null) return false;
        String p = String.valueOf(r.getPackageName()).toLowerCase(Locale.ROOT);
        for (String b : BLOCK) if (p.contains(b)) return true;
        for (AccessibilityNodeInfo n : nodes()) if (n.isPassword()) return true;
        return false;
    }

    private static final String[] MONEY = {"pay", "buy", "purchase", "delete", "remove", "install", "uninstall", "transfer", "order"};
    private static final String[] MSG = {"send", "post", "call", "submit", "confirm"};

    private String confirmMode() { return getSharedPreferences("roohu", MODE_PRIVATE).getString("confirm", "money"); }

    private boolean risky(String t) {
        String mode = confirmMode();
        if (mode.equals("off")) return false;
        for (String w : t.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            for (String r : MONEY) if (w.equals(r)) return true;
            if (mode.equals("all")) for (String r : MSG) if (w.equals(r)) return true;
        }
        return false;
    }

    private volatile List<AccessibilityNodeInfo> lastItems = new ArrayList<>();
    private volatile String lastTyped = "";

    private volatile List<AccessibilityNodeInfo> lastNodes = new ArrayList<>();

    private int readScreen() {
        JSONArray a = new JSONArray();
        List<AccessibilityNodeInfo> keep = new ArrayList<>();
        try {
            for (AccessibilityNodeInfo n : nodes()) {
                String t = label(n);
                String id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName().replaceAll(".*/", "");
                boolean act = n.isClickable() || n.isEditable() || n.isScrollable();
                if ((t.isEmpty() && !act) || (t.isEmpty() && id.isEmpty()) || a.length() >= 70) continue;
                JSONObject o = new JSONObject();
                o.put("i", keep.size());
                o.put("text", t.isEmpty() ? "(" + id + ")" : t.substring(0, Math.min(80, t.length())));
                if (n.isClickable()) o.put("click", true);
                if (n.isEditable()) o.put("edit", true);
                if (n.isScrollable()) o.put("scroll", true);
                if (n.isFocused()) o.put("focus", true);
                a.put(o);
                keep.add(n);
            }
        } catch (Exception e) { }
        lastNodes = keep;
        lastScreen = a.toString();
        return a.length();
    }

    private boolean tap(String want) {
        String w = norm(want);
        if (w.isEmpty()) return false;
        AccessibilityNodeInfo hit = null;
        for (AccessibilityNodeInfo n : nodes()) {
            if (n.isEditable() && !n.isShowingHintText() && n.getText() != null && n.getText().length() > 0) continue;
            String t = norm(label(n));
            if (t.equals(w)) { hit = n; break; }
            if (hit == null && !t.isEmpty() && t.contains(w)) hit = n;
        }
        return hit != null && tapNode(hit);
    }

    private boolean tapNode(AccessibilityNodeInfo hit) {
        for (AccessibilityNodeInfo c = hit; c != null; c = c.getParent())
            if (c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Rect r = new Rect();
        hit.getBoundsInScreen(r);
        Path p = new Path();
        p.moveTo(r.centerX(), r.centerY());
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build();
        return dispatchGesture(g, null, null);
    }

    private String labelFor(String t) {
        try {
            if (t.startsWith("#")) return label(lastItems.get(Integer.parseInt(t.substring(1).trim())));
        } catch (Exception e) { }
        return t;
    }

    private boolean tapIdx(String t) {
        try { return clickNode(lastItems.get(Integer.parseInt(t.substring(1).trim()))); } catch (Exception e) { return false; }
    }

    private void settle() throws InterruptedException {
        int prev = -1, same = 0;
        for (int i = 0; i < 16 && same < 2; i++) {
            Thread.sleep(500);
            int n = nodes().size();
            if (n > 3 && n == prev) same++; else same = 0;
            prev = n;
        }
    }

    private boolean openLink(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        for (String b : BLOCK) if (u.contains(b)) return false;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (u.contains("google.com/search") && getPackageManager().getLaunchIntentForPackage("com.android.chrome") != null) i.setPackage("com.android.chrome");
            startActivity(i);
            return true;
        } catch (Exception e) {
            try {
                Intent j = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                j.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(j);
                return true;
            } catch (Exception e2) { return false; }
        }
    }

    private volatile String cfgBase = "", cfgKey = "", cfgModel = "", curGoal = "";

    public void setHeal(String goal, String base, String key, String model) {
        curGoal = goal; cfgBase = base; cfgKey = key; cfgModel = model;
    }

    private boolean type(String s) {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null) return false;
        AccessibilityNodeInfo f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null) {
            for (AccessibilityNodeInfo n : nodes()) {
                if (n.isEditable()) { f = n; n.performAction(AccessibilityNodeInfo.ACTION_CLICK); break; }
            }
        }
        if (f == null) return false;
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s);
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    private boolean scroll(boolean down) {
        for (AccessibilityNodeInfo n : nodes()) {
            if (n.isScrollable()) {
                return n.performAction(down ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            }
        }
        return false;
    }

    private String norm(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }

    private String cl(String s) {
        return s.replaceAll("(?i)^(?:the|a|an|my)\\s+", "").replaceAll("(?i)\\s+(?:app|application|button|icon|tab|option|please)$", "").trim();
    }

    private String findPkg(String name) {
        String w = norm(name);
        if (w.isEmpty()) return null;
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        String pkg = null;
        int best = 1000;
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String l = norm(ri.loadLabel(pm).toString());
            if (l.isEmpty()) continue;
            int score = l.equals(w) ? 0 : (l.contains(w) || (l.length() >= 3 && w.contains(l))) ? 1 + Math.abs(l.length() - w.length()) : 1000;
            if (score < best) { best = score; pkg = ri.activityInfo.packageName; }
        }
        return pkg;
    }

    private boolean openApp(String name) {
        String pkg = findPkg(name);
        if (pkg == null) return false;
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        return true;
    }

    private boolean openUrl(String t) {
        try {
            String pkg = null, uri = t;
            int p = t.indexOf('|');
            if (p > 0 && !t.substring(0, p).contains(":")) { pkg = findPkg(t.substring(0, p)); uri = t.substring(p + 1); }
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (pkg != null) i.setPackage(pkg);
            try { startActivity(i); } catch (Exception e) { i.setPackage(null); startActivity(i); }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean needsAsk(String t) {
        int lv = getSharedPreferences("roohu", MODE_PRIVATE).getInt("auto", 1);
        if (lv >= 2) return false;
        if (lv == 0) return risky(t);
        for (String w : t.toLowerCase(Locale.ROOT).split("[^a-z]+"))
            for (String r : new String[]{"pay", "buy", "purchase", "delete", "remove", "install", "uninstall", "transfer", "order"}) if (w.equals(r)) return true;
        return false;
    }

    private String deep(String app, String q) {
        try {
            String a = app == null ? "" : app.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            String e = URLEncoder.encode(q, "UTF-8").replace("+", "%20");
            if (a.equals("youtube")) return "youtube|https://www.youtube.com/results?search_query=" + e;
            if (a.equals("playstore") || a.equals("googleplay") || a.equals("play")) return "market://search?q=" + e + "&c=apps";
            if (a.equals("chrome") || a.equals("googlechrome")) return "chrome|https://www.google.com/search?q=" + e;
            if (a.equals("google") || a.equals("browser") || a.equals("web") || a.equals("internet")) return "https://www.google.com/search?q=" + e;
            if (a.equals("maps") || a.equals("googlemaps")) return "geo:0,0?q=" + e;
            if (a.equals("spotify")) return "spotify:search:" + e;
        } catch (Exception ex) { }
        return null;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private WindowManager.LayoutParams lp(int gravity, int y) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        p.gravity = gravity;
        p.y = y;
        return p;
    }

    private Runnable stopShow;

    private void showStop() {
        stopShow = () -> {
            if (!running || stopView != null) return;
            TextView b = new TextView(this);
            b.setText("\u25A0  Stop");
            b.setTextColor(Color.WHITE);
            b.setTextSize(13);
            b.setPadding(dp(14), dp(8), dp(14), dp(8));
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(Color.argb(210, 20, 24, 36));
            gd.setCornerRadius(dp(18));
            gd.setStroke(dp(1), Color.argb(90, 255, 255, 255));
            b.setBackground(gd);
            b.setOnClickListener(v -> stopNow());
            stopView = b;
            WindowManager.LayoutParams p = lp(Gravity.TOP | Gravity.END, dp(36));
            p.x = dp(12);
            wm.addView(b, p);
        };
        main.postDelayed(stopShow, 2500);
    }

    private void hideStop() {
        main.post(() -> {
            if (stopShow != null) main.removeCallbacks(stopShow);
            try { if (stopView != null) wm.removeView(stopView); } catch (Exception e) { }
            stopView = null;
        });
    }

    private boolean confirm(String what) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        final View[] box = new View[1];
        main.post(() -> {
            LinearLayout l = new LinearLayout(this);
            l.setOrientation(LinearLayout.VERTICAL);
            l.setBackgroundColor(Color.rgb(24, 24, 36));
            l.setPadding(dp(20), dp(20), dp(20), dp(20));
            TextView t = new TextView(this);
            t.setTextColor(Color.WHITE);
            t.setTextSize(18);
            t.setText("Allow this action?\n\n" + what);
            Button y = new Button(this);
            y.setText("Yes, do it");
            y.setOnClickListener(v -> { ok.set(true); latch.countDown(); });
            Button n = new Button(this);
            n.setText("No");
            n.setOnClickListener(v -> latch.countDown());
            l.addView(t);
            l.addView(y);
            l.addView(n);
            box[0] = l;
            wm.addView(l, lp(Gravity.CENTER, 0));
        });
        try { latch.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { }
        final View v0 = box[0];
        main.post(() -> { try { if (v0 != null) wm.removeView(v0); } catch (Exception e) { } });
        return ok.get() && !stop;
    }

    private String step(String a, String t) throws InterruptedException {
        if ((a.equals("open") || a.equals("tap")) && norm(t).equals("roohu")) return "FAIL: that is me";
        switch (a) {
            case "open":
                for (String b : BLOCK) if (t.toLowerCase(Locale.ROOT).contains(b)) return "FAIL: money apps are blocked";
                return openApp(t) ? "ok" : "FAIL: app not found";
            case "link": return openLink(t) ? "ok" : "FAIL: could not open the link";
            case "settle": settle(); return "ok";
            case "tap":
                if (t.startsWith("#")) return tapIdx(t) ? "ok" : "FAIL: item not found";
                for (int k = 0; k < 10; k++) { if (tap(t)) return "ok"; Thread.sleep(500); }
                return "FAIL: not found on screen";
            case "type":
                for (int k = 0; k < 6; k++) { if (type(t)) { lastTyped = t; return "ok"; } Thread.sleep(500); }
                return "FAIL: no text box";
            case "scroll": return scroll(!t.equals("up")) ? "ok" : "FAIL: nothing to scroll";
            case "back": return performGlobalAction(GLOBAL_ACTION_BACK) ? "ok" : "FAIL";
            case "home": return performGlobalAction(GLOBAL_ACTION_HOME) ? "ok" : "FAIL";
            case "url":
                for (String b : BLOCK) if (t.toLowerCase(Locale.ROOT).contains(b)) return "FAIL: money sites are blocked";
                return openUrl(t) ? "ok" : "FAIL: could not open the link";
            case "enter":
                if (pressEnter()) return "ok";
                if (!lastTyped.isEmpty() && tap(lastTyped)) return "ok";
                return "FAIL: could not submit (Enter needs Android 11 or newer)";
            case "wait":
                int s = Math.min(10, Math.max(1, Integer.parseInt(t.isEmpty() ? "2" : t)));
                Thread.sleep(s * 1000L);
                return "ok";
            case "read": return "ok, saw " + readScreen() + " items (see Screen tab)";
            default: return "FAIL: unknown step";
        }
    }

    public synchronized boolean runPlan(String json) {
        if (running) return false;
        JSONArray parsed = null;
        try { parsed = new JSONArray(json); } catch (Exception e) { return false; }
        final JSONArray steps = parsed;
        running = true;
        stop = false;
        log.clear();
        showStop();
        worker = new Thread(() -> {
            try {
                for (int i = 0; i < steps.length() && i < 20 && !stop; i++) {
                    JSONObject s = steps.getJSONObject(i);
                    String a = s.optString("a");
                    String t = s.optString("t");
                    addLog((i + 1) + ". " + a + " " + t);
                    if (!a.equals("wait") && !a.equals("home") && blocked()) {
                        addLog("STOPPED: password or money app on screen.");
                        break;
                    }
                    if (a.equals("tap") && needsAsk(t) && !confirm("tap \"" + t + "\"")) {
                        addLog("Not allowed. Stopped.");
                        break;
                    }
                    String r = step(a, t);
                    addLog("   " + r);
                    if (r.startsWith("FAIL")) {
                        if (!cfgBase.isEmpty() && !cfgKey.isEmpty() && !cfgModel.isEmpty() && !curGoal.isEmpty() && !r.contains("blocked")) {
                            addLog("I will find another way.");
                            agentLoop(curGoal, cfgBase, cfgKey, cfgModel, false);
                        }
                        break;
                    }
                    Thread.sleep(1500);
                }
            } catch (Exception e) { }
            addLog(stop ? "Stopped." : "Finished.");
            running = false;
            hideStop();
        });
        worker.start();
        return true;
    }

    private static final String AGENT_SYS = "You are Roohu, an agent that operates an Android phone for the user. Each turn you get the goal, your recent actions with results, the current app and the visible screen items. Think briefly, then reply with ONE JSON object only: {\"thought\":\"short reason\",\"a\":\"tap|type|enter|scroll|back|home|open|url|wait\",\"i\":ITEM_NUMBER} or with \"t\":\"text\" instead of i. For tap use i (the item number). type needs t (it goes into the focused or first text box). open needs t (app name). url needs t (a web link, or app|link). scroll t is up or down. wait t is seconds. When the goal is done or impossible reply {\"done\":true,\"say\":\"short answer for the user in simple words\"}. Strategy: for web searches and for searching YouTube or Play Store, use url (https://www.google.com/search?q=..., https://www.youtube.com/results?search_query=..., market://search?q=...). To search inside other apps: tap the search icon or bar, type, then enter. If an action failed or the screen did not change, try a different way. For live questions (weather, news, scores, prices) use url with a Google search, wait 3, read the screen items, and put the real answer in say. Never open or tap the app named Roohu. Never touch banking, payment or password screens.";

    private String chat(String base, String key, String model, String sys, String user) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base.replaceAll("/+$", "") + "/chat/completions").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(40000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + key);
        JSONObject b = new JSONObject();
        b.put("model", model);
        b.put("temperature", 0);
        b.put("max_tokens", 400);
        JSONArray m = new JSONArray();
        m.put(new JSONObject().put("role", "system").put("content", sys));
        m.put(new JSONObject().put("role", "user").put("content", user));
        b.put("messages", m);
        OutputStream o = c.getOutputStream();
        o.write(b.toString().getBytes("UTF-8"));
        o.close();
        int code = c.getResponseCode();
        InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        String r = bo.toString("UTF-8");
        if (code >= 400) throw new Exception("AI error " + code + ": " + r.substring(0, Math.min(100, r.length())));
        return new JSONObject(r).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
    }

    private JSONObject parseObj(String s) {
        try {
            return new JSONObject(s.substring(s.indexOf('{'), s.lastIndexOf('}') + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private void agentLoop(String goal, String base, String key, String model, boolean goHome) throws Exception {
        if (goHome) { performGlobalAction(GLOBAL_ACTION_HOME); Thread.sleep(1200); }
        StringBuilder hist = new StringBuilder();
        String lastKey = "";
        int repeat = 0;
        for (int i = 0; i < 25 && !stop; i++) {
            if (blocked()) { addLog("STOPPED: password or money app on screen."); return; }
            readScreen();
            AccessibilityNodeInfo root = getRootInActiveWindow();
            String pkg = root == null ? "none" : String.valueOf(root.getPackageName());
            String out = chat(base, key, model, AGENT_SYS, "Goal: " + goal + "\nCurrent app: " + pkg + "\nActions so far: " + (hist.length() == 0 ? "none" : hist) + "\nScreen items: " + lastScreen);
            JSONObject o = parseObj(out);
            if (o == null) { String w = out.trim(); addLog("SAY " + w.substring(0, Math.min(400, w.length()))); return; }
            if (o.optBoolean("done", false)) { addLog("SAY " + o.optString("say", "Done.")); return; }
            String a = o.optString("a");
            String t = o.optString("t");
            if (a.equals("tap") && o.has("i")) t = "#" + o.optInt("i");
            String k2 = a + "|" + t;
            repeat = k2.equals(lastKey) ? repeat + 1 : 0;
            lastKey = k2;
            if (repeat >= 2) { addLog("SAY I am stuck on this step. Tell me what to do next."); return; }
            String lab = a.equals("tap") ? labelFor(t) : t;
            addLog((i + 1) + ". " + a + " " + lab);
            if (a.equals("tap") && risky(lab) && !confirm("tap \"" + lab + "\"")) { addLog("Not allowed. Stopped."); return; }
            String r = step(a, t);
            addLog("   " + r);
            hist.append(a).append(' ').append(lab).append(r.startsWith("FAIL") ? " -> FAILED; " : " -> ok; ");
            if (hist.length() > 700) hist.delete(0, hist.length() - 700);
            Thread.sleep(1200);
        }
        addLog("SAY I reached my step limit. Tell me if I should continue.");
    }

    public synchronized boolean runAgent(final String goal, final String base, final String key, final String model) {
        if (running) return false;
        running = true;
        stop = false;
        log.clear();
        showStop();
        worker = new Thread(() -> {
            int i = 0;
            try {
                performGlobalAction(GLOBAL_ACTION_HOME);
                Thread.sleep(1200);
                List<String> hist = new ArrayList<>();
                String lastKey = "";
                int same = 0;
                for (; i < 25 && !stop; i++) {
                    if (blocked()) { addLog("STOPPED: password or money app on screen."); break; }
                    readScreen();
                    String h = "";
                    for (int k = Math.max(0, hist.size() - 8); k < hist.size(); k++) h += hist.get(k) + "\n";
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    String pkg = root == null ? "unknown" : String.valueOf(root.getPackageName());
                    String out = chat(base, key, model, AGENT_SYS, "Goal: " + goal + "\nApp now: " + pkg + "\nRecent actions:\n" + (h.isEmpty() ? "none yet\n" : h) + "Screen items: " + lastScreen);
                    JSONObject o = parseObj(out);
                    if (o == null) { String w = out.trim(); addLog("SAY " + w.substring(0, Math.min(300, w.length()))); break; }
                    if (o.optBoolean("done", false)) { addLog("SAY " + o.optString("say", "Done.")); break; }
                    String a = o.optString("a");
                    String t = o.optString("t");
                    AccessibilityNodeInfo target = null;
                    if (a.equals("tap") && o.has("i")) {
                        int idx = o.optInt("i", -1);
                        if (idx >= 0 && idx < lastNodes.size()) {
                            target = lastNodes.get(idx);
                            t = label(target);
                            if (t.isEmpty()) t = "item " + idx;
                        }
                    }
                    String k2 = a + "|" + t + "|" + o.optInt("i", -1);
                    same = k2.equals(lastKey) ? same + 1 : 0;
                    lastKey = k2;
                    if (same >= 2) { addLog("SAY I am stuck on \"" + a + " " + t + "\". Please try different words."); break; }
                    addLog((i + 1) + ". " + a + " " + t);
                    if (a.equals("tap") && needsAsk(t) && !confirm("tap \"" + t + "\"")) { addLog("Not allowed. Stopped."); break; }
                    String r = target != null ? (tapNode(target) ? "ok" : "FAIL: could not tap") : step(a, t);
                    addLog("   " + r);
                    hist.add(a + " " + t + " -> " + r);
                    Thread.sleep(1500);
                }
                if (i >= 25 && !stop) addLog("SAY I tried 25 steps and could not finish.");
            } catch (Exception e) {
                addLog("   FAIL: " + e.getMessage());
            }
            addLog(stop ? "Stopped." : "Finished.");
            running = false;
            hideStop();
        });
        worker.start();
        return true;
    }

    private boolean pressEnter() {
        if (Build.VERSION.SDK_INT < 30) return false;
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null) return false;
        AccessibilityNodeInfo f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        return f != null && f.performAction(android.R.id.accessibilityActionImeEnter);
    }

    private volatile boolean botOn = false;
    private volatile String botErr = "";
    private volatile int botGen = 0;

    private Matcher mt(String re, String s) {
        Matcher m = Pattern.compile(re, Pattern.CASE_INSENSITIVE).matcher(s);
        return m.matches() ? m : null;
    }

    private void add(JSONArray out, String a, String t) {
        try {
            String v = t.trim().replaceAll("^[\"'\u201c\u201d]+|[\"'\u201c\u201d]+$", "");
            if (a.equals("open") || a.equals("tap")) v = cl(v);
            out.put(new JSONObject().put("a", a).put("t", v));
        } catch (Exception e) { }
    }

    private void link(JSONArray out, String url) {
        add(out, "link", url);
        add(out, "settle", "");
    }

    private void weather(JSONArray out, String place) {
        String q = place == null ? "weather today" : "weather " + place;
        try { link(out, "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")); } catch (Exception e) { }
        add(out, "read", "");
    }

    private void search(JSONArray out, String app, String q) {
        String d = app == null ? null : deep(app, q);
        if (d != null) { add(out, "url", d); return; }
        String a = app == null ? "" : norm(app);
        String e = q.trim();
        try { e = URLEncoder.encode(e, "UTF-8"); } catch (Exception ex) { }
        if (a.contains("youtube")) { link(out, "https://www.youtube.com/results?search_query=" + e); return; }
        if (a.contains("playstore") || a.contains("googleplay") || a.equals("play")) { link(out, "market://search?q=" + e + "&c=apps"); return; }
        if (a.contains("maps")) { link(out, "https://www.google.com/maps/search/?api=1&query=" + e); return; }
        if (a.contains("chrome") || a.contains("browser") || a.contains("google") || a.contains("web") || a.contains("internet")) { link(out, "https://www.google.com/search?q=" + e); return; }
        if (app != null) { add(out, "open", app); add(out, "settle", ""); }
        add(out, "tap", "search");
        add(out, "wait", "1");
        add(out, "type", q);
        add(out, "enter", "");
    }

    private static final String OPEN = "(?:open|launch|start|go\\s+to|goto|visit)";

    private void wa(JSONArray out, String name, String msg) {
        add(out, "open", "WhatsApp"); add(out, "settle", "");
        add(out, "tap", "search"); add(out, "wait", "1");
        add(out, "type", name); add(out, "wait", "2");
        add(out, "tap", name); add(out, "wait", "2");
        add(out, "type", msg); add(out, "wait", "1");
        add(out, "tap", "send");
    }

    private String[] splitMsg(String r) {
        Matcher m = mt("([^\"\u201c]+?)\\s+[\"\u201c](.+?)[\"\u201d]?", r);
        if (m == null) m = mt("(.+?)\\s*(?:saying|that says|that|:)\\s+(.+)", r);
        if (m == null) m = mt("(\\S+)\\s+(.+)", r);
        return m == null ? null : new String[]{m.group(1), m.group(2)};
    }

    private JSONArray parseCmd(String text) {
        JSONArray out = new JSONArray();
        for (String raw : text.split("(?i)\\s*(?:,\\s*)?\\b(?:and then|then)\\b\\s*|\\s*[;\\n]\\s*")) {
            String p = raw.trim().replaceAll("^[,\\s]+|[,.\\s]+$", "");
            if (p.isEmpty()) continue;
            Matcher m;
            String[] sm = null;
            if ((m = mt("(?:open\\s+)?whatsapp\\s+(?:and\\s+)?(?:message|msg|text)\\s+(.+)", p)) != null && (sm = splitMsg(m.group(1))) != null) wa(out, sm[0], sm[1]);
            else if ((m = mt("(?:message|msg|text)\\s+(.+?)\\s+on\\s+whatsapp", p)) != null && (sm = splitMsg(m.group(1))) != null) wa(out, sm[0], sm[1]);
            else if ((m = mt("(?:tell me|what(?:'s|\\s+is)|show me|check|how(?:'s|\\s+is))?\\s*(?:the\\s+)?(?:today'?s\\s+|current\\s+)?weather(?:\\s+(?:today|now|like))?(?:\\s+(?:in|for|at)\\s+(.+))?\\??", p)) != null) weather(out, m.group(1));
            else if ((m = mt("(?:google|web\\s+search)\\s+(.+)", p)) != null) add(out, "url", deep("google", m.group(1)));
            else if ((m = mt(OPEN + "\\s+(.+?)\\s+(?:and\\s+)?(?:search|find)(?:\\s+for)?\\s+(.+)", p)) != null) search(out, m.group(1), m.group(2));
            else if ((m = mt("(?:search|find)(?:\\s+for)?\\s+(.+?)\\s+(?:in|on)\\s+(.+)", p)) != null) search(out, m.group(2), m.group(1));
            else if ((m = mt("(?:search|find)(?:\\s+for)?\\s+(.+)", p)) != null) search(out, null, m.group(1));
            else if ((m = mt(OPEN + "\\s+(.+)", p)) != null) add(out, "open", m.group(1));
            else if ((m = mt("(?:tap|click|press|touch|select)\\s+(?:on\\s+)?(.+)", p)) != null) add(out, "tap", m.group(1));
            else if (mt("(?:press\\s+)?enter", p) != null) add(out, "enter", "");
            else if ((m = mt("(?:type|write|enter)\\s+(.+)", p)) != null) add(out, "type", m.group(1));
            else if ((m = mt("scroll(?:\\s+(up|down))?", p)) != null) add(out, "scroll", m.group(1) == null ? "down" : m.group(1).toLowerCase(Locale.ROOT));
            else if (mt("(?:go\\s+)?back", p) != null) add(out, "back", "");
            else if (mt("(?:go\\s+)?home", p) != null) add(out, "home", "");
            else if ((m = mt("wait(?:\\s+(\\d+))?", p)) != null) add(out, "wait", m.group(1) == null ? "2" : m.group(1));
            else if (mt("(?:read|look)(?:\\s+(?:at\\s+)?(?:the\\s+)?screen)?|what(?:'s|\\s+is)\\s+on\\s+(?:my\\s+|the\\s+)?screen\\??", p) != null) add(out, "read", "");
            else return null;
        }
        return out;
    }

    private String http(String method, String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(40000);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            OutputStream o = c.getOutputStream();
            o.write(body.getBytes("UTF-8"));
            o.close();
        }
        InputStream is = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    private void tgSend(String token, long chat, String text) {
        try {
            if (text.length() > 3500) text = text.substring(0, 3500);
            http("POST", "https://api.telegram.org/bot" + token + "/sendMessage", "chat_id=" + chat + "&text=" + URLEncoder.encode(text, "UTF-8"));
        } catch (Exception e) { }
    }

    private synchronized String summary() {
        StringBuilder b = new StringBuilder();
        for (String l : log) b.append(l.startsWith("SAY ") ? l.substring(4) : l.trim()).append('\n');
        return b.length() == 0 ? "Done." : b.toString();
    }

    private void botHandle(String token, long chat, String text) throws Exception {
        SharedPreferences sp = getSharedPreferences("roohu", MODE_PRIVATE);
        long paired = sp.getLong("paired", 0);
        String code = sp.getString("code", "");
        if (text.startsWith("/pair ")) {
            if (paired == 0 && !code.isEmpty() && text.substring(6).trim().equals(code)) {
                sp.edit().putLong("paired", chat).putString("code", "").apply();
                tgSend(token, chat, "Paired. I am Roohu. Send a command, /screen, /stop or /help.");
            }
            return;
        }
        if (paired == 0) { tgSend(token, chat, "Not paired yet. Send /pair and the code shown in the Roohu app, like /pair 123456"); return; }
        if (chat != paired) return;
        String tl = text.toLowerCase(Locale.ROOT);
        if (tl.equals("/stop")) { stopNow(); tgSend(token, chat, "Stopped."); return; }
        if (tl.equals("/start") || tl.equals("/help")) {
            tgSend(token, chat, "Try: open Settings, search cats on YouTube, tap X, type hi, scroll down, back, home, /screen, /stop. Plain words work if an AI is saved in the app.");
            return;
        }
        if (tl.equals("/screen")) {
            if (blocked()) { tgSend(token, chat, "I will not read this screen (password or money app)."); return; }
            readScreen();
            JSONArray a = new JSONArray(lastScreen);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < a.length() && i < 40; i++) b.append(a.getJSONObject(i).optString("text")).append('\n');
            tgSend(token, chat, b.length() == 0 ? "Nothing readable." : b.toString());
            return;
        }
        if (running) { tgSend(token, chat, "I am busy. Send /stop first."); return; }
        setHeal(text, sp.getString("base", ""), sp.getString("key", ""), sp.getString("model", ""));
        JSONArray steps = parseCmd(text);
        boolean ok;
        if (steps != null && steps.length() > 0) {
            ok = runPlan(steps.toString());
        } else {
            String base = sp.getString("base", ""), key = sp.getString("key", ""), model = sp.getString("model", "");
            if (base.isEmpty() || key.isEmpty() || model.isEmpty()) {
                tgSend(token, chat, "I do not understand that. Save an AI in the app for plain words.");
                return;
            }
            ok = runAgent(text, base, key, model);
        }
        if (!ok) return;
        for (int i = 0; i < 240 && running; i++) Thread.sleep(500);
        tgSend(token, chat, summary());
    }

    private void botLoop(int g) {
        long offset = 0;
        while (botOn && g == botGen) {
            try {
                String token = getSharedPreferences("roohu", MODE_PRIVATE).getString("token", "");
                JSONObject jo = new JSONObject(http("GET", "https://api.telegram.org/bot" + token + "/getUpdates?timeout=25&offset=" + offset, null));
                if (!jo.optBoolean("ok", false)) {
                    botErr = jo.optString("description", "Telegram refused the request");
                    Thread.sleep(5000);
                    continue;
                }
                botErr = "";
                JSONArray u = jo.getJSONArray("result");
                for (int i = 0; i < u.length(); i++) {
                    JSONObject up = u.getJSONObject(i);
                    offset = up.getLong("update_id") + 1;
                    JSONObject m = up.optJSONObject("message");
                    if (m == null) continue;
                    botHandle(token, m.getJSONObject("chat").getLong("id"), m.optString("text", "").trim());
                }
            } catch (Exception e) {
                botErr = "Network problem: " + e.getMessage();
                try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private synchronized void launchBot() {
        final int g = ++botGen;
        botOn = true;
        botErr = "";
        new Thread(() -> botLoop(g)).start();
    }

    public void startBot(String token, String code, String base, String key, String model) {
        getSharedPreferences("roohu", MODE_PRIVATE).edit().putString("token", token).putString("code", code).putLong("paired", 0)
                .putString("base", base).putString("key", key).putString("model", model).putBoolean("bot", true).apply();
        launchBot();
    }

    public void stopBot() {
        botOn = false;
        botGen++;
        getSharedPreferences("roohu", MODE_PRIVATE).edit().putBoolean("bot", false).apply();
    }
}
