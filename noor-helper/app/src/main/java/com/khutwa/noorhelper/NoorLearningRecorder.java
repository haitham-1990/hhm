package com.khutwa.noorhelper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class NoorLearningRecorder {
    private static final String PREFS = "noor_learning_v1";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_EVENTS = "events";
    private static final String KEY_STARTED = "started";
    private static final String KEY_STOPPED = "stopped";
    private static final int MAX_EVENTS = 900;

    private final SharedPreferences prefs;

    NoorLearningRecorder(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized void startSession(String url) {
        JSONArray events = new JSONArray();
        JSONObject start = new JSONObject();
        try {
            start.put("kind", "session_start");
            start.put("time", System.currentTimeMillis());
            start.put("url", safe(url));
            events.put(start);
        } catch (Exception ignored) {}
        prefs.edit()
                .putBoolean(KEY_ACTIVE, true)
                .putLong(KEY_STARTED, System.currentTimeMillis())
                .remove(KEY_STOPPED)
                .putString(KEY_EVENTS, events.toString())
                .apply();
    }

    synchronized void stopSession(String url) {
        recordSimple("session_stop", url);
        prefs.edit()
                .putBoolean(KEY_ACTIVE, false)
                .putLong(KEY_STOPPED, System.currentTimeMillis())
                .apply();
    }

    boolean isActive() {
        return prefs.getBoolean(KEY_ACTIVE, false);
    }

    synchronized void record(String rawJson) {
        if (!isActive() || rawJson == null || rawJson.trim().isEmpty()) return;
        try {
            JSONObject o = new JSONObject(rawJson);
            sanitize(o);
            append(o);
        } catch (Exception ignored) {}
    }

    synchronized void appendSnapshot(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("kind", "page_snapshot");
            o.put("time", System.currentTimeMillis());
            o.put("snapshot", new JSONObject(rawJson));
            append(o);
        } catch (Exception ignored) {}
    }

    int eventCount() {
        try {
            return new JSONArray(prefs.getString(KEY_EVENTS, "[]")).length();
        } catch (Exception e) {
            return 0;
        }
    }

    synchronized JSONArray learnedDescriptors() {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        try {
            JSONArray events = new JSONArray(prefs.getString(KEY_EVENTS, "[]"));
            for (int i = 0; i < events.length(); i++) {
                JSONObject e = events.optJSONObject(i);
                if (e == null) continue;
                JSONObject el = e.optJSONObject("element");
                if (el == null) continue;
                String tag = el.optString("tag", "");
                String id = el.optString("id", "");
                String name = el.optString("name", "");
                String label = el.optString("label", "");
                String placeholder = el.optString("placeholder", "");
                String text = el.optString("text", "");
                if (tag.isEmpty()) continue;
                String key = tag + "|" + id + "|" + name + "|" + label + "|" + placeholder + "|" + text;
                if (!seen.add(key)) continue;

                JSONObject d = new JSONObject();
                d.put("tag", tag);
                d.put("id", id);
                d.put("name", name);
                d.put("type", el.optString("type", ""));
                d.put("label", trim(label, 140));
                d.put("placeholder", trim(placeholder, 100));
                d.put("text", trim(text, 140));
                d.put("path", trim(el.optString("path", ""), 220));
                out.put(d);
                if (out.length() >= 120) break;
            }
        } catch (Exception ignored) {}
        return out;
    }

    synchronized String buildReport(String currentUrl) {
        JSONObject root = new JSONObject();
        try {
            root.put("report_version", 1);
            root.put("app", "Noor Helper 0.5.0");
            root.put("generated_at", iso(System.currentTimeMillis()));
            root.put("started_at", iso(prefs.getLong(KEY_STARTED, 0)));
            root.put("stopped_at", iso(prefs.getLong(KEY_STOPPED, 0)));
            root.put("current_url", safe(currentUrl));
            root.put("privacy", "Passwords, cookies, authorization headers and typed text values are not recorded.");
            root.put("event_count", eventCount());
            root.put("learned_elements", learnedDescriptors());
            root.put("events", new JSONArray(prefs.getString(KEY_EVENTS, "[]")));
        } catch (Exception ignored) {}
        return root.toString();
    }

    private void recordSimple(String kind, String url) {
        try {
            JSONObject o = new JSONObject();
            o.put("kind", kind);
            o.put("time", System.currentTimeMillis());
            o.put("url", safe(url));
            append(o);
        } catch (Exception ignored) {}
    }

    private void append(JSONObject o) {
        try {
            JSONArray events = new JSONArray(prefs.getString(KEY_EVENTS, "[]"));
            if (!o.has("time")) o.put("time", System.currentTimeMillis());
            events.put(o);

            if (events.length() > MAX_EVENTS) {
                JSONArray trimmed = new JSONArray();
                int start = Math.max(0, events.length() - MAX_EVENTS);
                for (int i = start; i < events.length(); i++) trimmed.put(events.get(i));
                events = trimmed;
            }
            prefs.edit().putString(KEY_EVENTS, events.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static void sanitize(JSONObject o) throws Exception {
        JSONObject el = o.optJSONObject("element");
        if (el != null) {
            String type = el.optString("type", "");
            if ("password".equalsIgnoreCase(type)) {
                el.remove("value");
                el.put("value_redacted", true);
            }
            if (el.has("typedText")) el.remove("typedText");
            String value = el.optString("value", "");
            if (!value.isEmpty() && !safeValueType(type, el.optString("tag", ""))) {
                el.remove("value");
                el.put("value_length", value.length());
            }
        }

        // Never persist sensitive request material if a page script unexpectedly sends it.
        o.remove("cookie");
        o.remove("cookies");
        o.remove("authorization");
        o.remove("headers");
        o.remove("body");
        o.remove("requestBody");
        o.remove("password");
    }

    private static boolean safeValueType(String type, String tag) {
        if ("SELECT".equalsIgnoreCase(tag)) return true;
        return "checkbox".equalsIgnoreCase(type)
                || "radio".equalsIgnoreCase(type)
                || "date".equalsIgnoreCase(type)
                || "number".equalsIgnoreCase(type);
    }

    private static String safe(String s) {
        return s == null ? "" : trim(s, 1000);
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String iso(long t) {
        if (t <= 0) return "";
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date(t));
    }
}
