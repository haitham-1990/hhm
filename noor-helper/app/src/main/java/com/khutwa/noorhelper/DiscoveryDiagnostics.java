package com.khutwa.noorhelper;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class DiscoveryDiagnostics {
    private static final Object LOCK = new Object();
    private static final String FILE = "noor_smart_discovery_diagnostic.jsonl";

    private final Context context;

    DiscoveryDiagnostics(Context context) {
        this.context = context.getApplicationContext();
    }

    void startSession(String planName, String materialName) {
        synchronized (LOCK) {
            try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                    context.openFileOutput(FILE, Context.MODE_PRIVATE), StandardCharsets.UTF_8))) {
                JSONObject event = base("session_start");
                event.put("app_version", BuildConfig.VERSION_NAME);
                event.put("plan_file", safeName(planName));
                event.put("material_file", safeName(materialName));
                out.write(event.toString());
                out.newLine();
            } catch (Exception ignored) {}
        }
    }

    void log(String stage, JSONObject data) {
        synchronized (LOCK) {
            try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                    context.openFileOutput(FILE, Context.MODE_APPEND), StandardCharsets.UTF_8))) {
                JSONObject event = base(stage);
                if (data != null) event.put("data", data);
                out.write(event.toString());
                out.newLine();
            } catch (Exception ignored) {}
        }
    }

    void logMessage(String stage, String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("message", message == null ? "" : message);
            log(stage, o);
        } catch (Exception ignored) {}
    }

    void logLessons(String stage, List<DiscoveredCurriculumStore.Entry> entries) {
        try {
            JSONObject o = new JSONObject();
            JSONArray lessons = new JSONArray();
            if (entries != null) {
                for (DiscoveredCurriculumStore.Entry e : entries) {
                    if (e == null || e.lesson == null) continue;
                    JSONObject l = new JSONObject();
                    l.put("code", e.lesson.code);
                    l.put("title", e.lesson.title);
                    l.put("unit", e.lesson.unit);
                    l.put("semester", e.lesson.semester);
                    l.put("periods", e.lesson.periods);
                    l.put("start", e.lesson.periodStart);
                    l.put("end", e.lesson.periodEnd);
                    l.put("objectives_count", e.lesson.objectives == null ? 0 : e.lesson.objectives.size());
                    l.put("material_start_page", e.materialStartPage);
                    l.put("material_end_page", e.materialEndPage);
                    lessons.put(l);
                }
            }
            o.put("count", lessons.length());
            o.put("lessons", lessons);
            log(stage, o);
        } catch (Exception ignored) {}
    }

    String exportReport() {
        synchronized (LOCK) {
            JSONArray events = new JSONArray();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    context.openFileInput(FILE), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    try {
                        events.put(new JSONObject(line));
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}

            try {
                JSONObject root = new JSONObject();
                root.put("report_type", "noor_smart_discovery_diagnostic");
                root.put("app_version", BuildConfig.VERSION_NAME);
                root.put("generated_at", isoNow());
                root.put("privacy_note",
                        "لا يحتوي التقرير على كلمة مرور نور أو الكوكيز أو نصوص ملفات PDF الكاملة. يسجل نتائج الاكتشاف والتقسيم والأخطاء فقط.");
                root.put("event_count", events.length());
                root.put("events", events);
                return root.toString(2);
            } catch (Exception e) {
                return "{\"report_type\":\"noor_smart_discovery_diagnostic\",\"error\":\"تعذر بناء التقرير\"}";
            }
        }
    }

    boolean hasReport() {
        try {
            return context.getFileStreamPath(FILE).exists()
                    && context.getFileStreamPath(FILE).length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static JSONObject base(String stage) throws Exception {
        JSONObject o = new JSONObject();
        o.put("time", isoNow());
        o.put("stage", stage == null ? "" : stage);
        return o;
    }

    private static String isoNow() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date());
    }

    private static String safeName(String name) {
        if (name == null) return "";
        String v = name.replace('\n', ' ').replace('\r', ' ').trim();
        return v.length() <= 180 ? v : v.substring(0, 180);
    }
}
