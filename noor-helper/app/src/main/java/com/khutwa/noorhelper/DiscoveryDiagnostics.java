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
        startSession(planName, materialName, true);
    }

    void startSession(String planName, String materialName, boolean reset) {
        synchronized (LOCK) {
            int mode = reset ? Context.MODE_PRIVATE : Context.MODE_APPEND;
            try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                    context.openFileOutput(FILE, mode), StandardCharsets.UTF_8))) {
                JSONObject event = base(reset ? "session_start" : "session_continue");
                event.put("app_version", appVersion());
                event.put("plan_file", safeName(planName));
                event.put("material_file", safeName(materialName));
                event.put("reset", reset);
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

    void logException(String stage, Throwable error) {
        try {
            JSONObject o = new JSONObject();
            if (error == null) {
                o.put("type", "");
                o.put("message", "");
            } else {
                o.put("type", error.getClass().getName());
                o.put("message", error.getMessage() == null ? "" : error.getMessage());
                JSONArray stack = new JSONArray();
                StackTraceElement[] trace = error.getStackTrace();
                if (trace != null) {
                    for (int i = 0; i < Math.min(10, trace.length); i++) {
                        stack.put(trace[i].toString());
                    }
                }
                o.put("stack", stack);
            }
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
                root.put("report_type", "noor_smart_full_diagnostic");
                root.put("report_version", 3);
                root.put("purpose",
                        "تشخيص شامل من رفع الخطة والمادة واكتشاف الدروس وتقسيم الصفحات وبناء التحاضير والصور، "
                                + "حتى قراءة شجرة منصة نور واختيار الدرس وتعبئة الحقول والتواريخ والحصص والمرفقات والحفظ النهائي.");
                root.put("app_version", appVersion());
                root.put("generated_at", isoNow());
                root.put("android_sdk", android.os.Build.VERSION.SDK_INT);
                root.put("device_model", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
                root.put("privacy_note",
                        "لا يسجل التقرير كلمات المرور أو الكوكيز أو قيم حقول تسجيل الدخول أو نصوص ملفات PDF الكاملة. "
                                + "قد يسجل عناوين الدروس، أسماء عناصر شجرة المنهج، بنية حقول التحضير، رسائل التحقق، "
                                + "وحالة الصفحة اللازمة لتشخيص فشل الأتمتة.");

                JSONObject stageCounts = new JSONObject();
                JSONArray errorHighlights = new JSONArray();
                for (int i = 0; i < events.length(); i++) {
                    JSONObject event = events.optJSONObject(i);
                    if (event == null) continue;
                    String stage = event.optString("stage", "");
                    if (!stage.isEmpty()) stageCounts.put(stage, stageCounts.optInt(stage, 0) + 1);
                    String lower = stage.toLowerCase(Locale.ROOT);
                    if (lower.contains("error") || lower.contains("fail") || lower.contains("stop")
                            || lower.contains("timeout") || lower.contains("http")) {
                        if (errorHighlights.length() < 60) errorHighlights.put(event);
                    }
                }
                root.put("stage_counts", stageCounts);
                root.put("error_highlights", errorHighlights);
                if (events.length() > 0) root.put("last_event", events.optJSONObject(events.length() - 1));
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

    private String appVersion() {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception e) {
            return "";
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
