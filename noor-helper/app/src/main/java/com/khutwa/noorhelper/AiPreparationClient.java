package com.khutwa.noorhelper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.ArrayList;

final class AiPreparationClient {
    static final class SourceResult {
        final LessonPreparation preparation;
        final String level;
        final List<String> objectives;
        final List<String> strategies;
        final List<String> resources;
        final int periods;
        final String startDate;
        final String endDate;

        SourceResult(LessonPreparation preparation, String level, List<String> objectives,
                     List<String> strategies, List<String> resources,
                     int periods, String startDate, String endDate) {
            this.preparation = preparation;
            this.level = level;
            this.objectives = objectives;
            this.strategies = strategies;
            this.resources = resources;
            this.periods = periods;
            this.startDate = startDate;
            this.endDate = endDate;
        }
    }
    private static final String ENDPOINT = "https://khutwa-ai.hhm1990hhm.workers.dev/";
    private static final String PREFS = "noor_ai_cache_v1";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 60000;

    private final Context context;

    AiPreparationClient(Context context) {
        this.context = context.getApplicationContext();
    }

    LessonPreparation getCached(String title) {
        try {
            String raw = prefs().getString(cacheKey(title), null);
            if (raw == null || raw.trim().isEmpty()) return null;
            return parsePreparation(title, raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    LessonPreparation generateAndCache(String title, List<String> outcomes) throws Exception {
        LessonPreparation cached = getCached(title);
        if (cached != null) return cached;

        ExerciseMap.Lesson lesson = ExerciseMap.find(title);
        String canonicalTitle = lesson == null ? title : lesson.code + " " + lesson.title;
        String prompt = buildCompactPrompt(canonicalTitle, outcomes);

        JSONObject payload = new JSONObject();
        payload.put("message", prompt);

        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");

            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }

            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String response = readAll(stream);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("خدمة الذكاء أعادت HTTP " + code);
            }

            JSONObject envelope = new JSONObject(response);
            String answer = envelope.optString("answer", "").trim();
            if (answer.isEmpty()) throw new IllegalStateException("استجابة الذكاء فارغة");

            String json = cleanJson(answer);
            LessonPreparation preparation = parsePreparation(title, json);
            prefs().edit().putString(cacheKey(title), json).apply();
            return preparation;
        } finally {
            conn.disconnect();
        }
    }

    SourceResult generateFromSources(String title, List<String> noorOutcomes,
                                     String planContext, String materialContext) throws Exception {
        String sourceKey = title + "\n" + planContext + "\n" + materialContext;
        String key = "source_" + hashShort(sourceKey);
        String cached = prefs().getString(key, null);
        if (cached != null && !cached.trim().isEmpty()) {
            return parseSourceResult(title, cached);
        }

        JSONObject payload = new JSONObject();
        payload.put("message", buildSourcePrompt(title, noorOutcomes, planContext, materialContext));

        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");

            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }

            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String response = readAll(stream);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("خدمة ذكاء خطوة أعادت HTTP " + code);
            }

            JSONObject envelope = new JSONObject(response);
            String answer = envelope.optString("answer", "").trim();
            if (answer.isEmpty()) throw new IllegalStateException("استجابة ذكاء خطوة فارغة");

            String json = cleanJson(answer);
            SourceResult result = parseSourceResult(title, json);
            prefs().edit().putString(key, json).apply();
            return result;
        } finally {
            conn.disconnect();
        }
    }

    private static String buildSourcePrompt(String title, List<String> noorOutcomes,
                                            String planContext, String materialContext) {
        StringBuilder b = new StringBuilder(22000);
        b.append("أنت محرك تحضير دروس لمنصة نور في سلطنة عمان. ");
        b.append("اعتمد حصراً على مقتطف الخطة الدراسية ومقتطف المادة العلمية أدناه. ");
        b.append("لا تخترع مخرجاً رسمياً أو تاريخاً أو عدد حصص غير مذكور في الخطة. ");
        b.append("إذا لم يظهر تاريخ أو عدد حصص بوضوح أرجع قيمة فارغة/0. ");
        b.append("أما الأنشطة والتقويم والتهيئة فيجب بناؤها تربوياً من محتوى المادة العلمية ومخرج الدرس.\n");
        b.append("الدرس المستهدف: ").append(limit(title, 220)).append("\n");
        if (noorOutcomes != null && !noorOutcomes.isEmpty()) {
            b.append("المخرجات الظاهرة حالياً في نور (للمقارنة فقط):\n");
            for (int i = 0; i < Math.min(8, noorOutcomes.size()); i++) {
                b.append("- ").append(limit(noorOutcomes.get(i), 220)).append("\n");
            }
        }
        b.append("\n=== مقتطف الخطة الدراسية ===\n").append(limitRaw(planContext, 7000));
        b.append("\n\n=== مقتطف المادة العلمية ===\n").append(limitRaw(materialContext, 14000));

        b.append("\n\nاختر الاستراتيجيات فقط من هذه القائمة وبالأسماء نفسها: ");
        b.append("الفصل المقلوب، التعلم التعاوني، التعلم التشاركي، التعلم الذاتي، التعلم بالاكتشاف، ");
        b.append("التعلم المبني على حل المشكلات، التعلم المبني على المشاريع، التعلم المبني على اللعب، ");
        b.append("التعلم بالنمذجة، التعلم المتمايز، الخرائط الذهنية، العصف الذهني، رحلات تعليمية، ");
        b.append("رحلات تعليمية افتراضية، تجارب معملية، تجارب معملية افتراضية، تقارير كتب، دراسة حالة، أخرى.");
        b.append("\nواختر المصادر التعليمية فقط من هذه القائمة وبالأسماء نفسها: ");
        b.append("الكتاب، كتب إلكترونية، السبورة التقليدية، السبورة الذكية، الأقلام، جهاز عرض البيانات، ");
        b.append("جهاز الحاسب، صورة توضيحية، عروض تقديمية، نماذج مجسمة، الوسائط المتعددة، الوسائط الاجتماعية، ");
        b.append("برمجيات/تطبيقات مثل Edpuzzle/Padlet/Kahoot، المصادر التعليمية الأخرى.");

        b.append("\nأرجع JSON فقط دون شرح خارجي بهذه البنية:");
        b.append("{\"o\":[\"المخرجات الرسمية المستخرجة حرفياً أو بصياغة قريبة جداً من الخطة\"],");
        b.append("\"l\":\"الفهم أو التطبيق أو التحليل\",");
        b.append("\"periods\":0,\"start\":\"YYYY-MM-DD أو فارغ\",\"end\":\"YYYY-MM-DD أو فارغ\",");
        b.append("\"st\":[\"استراتيجية\"],\"rs\":[\"مصدر\"],");
        b.append("\"c\":\"المفاهيم\",\"i\":\"التهيئة والتعلم القبلي\",");
        b.append("\"p\":\"إجراءات سير درس مرقمة تتضمن نشاطاً، تمايزاً، وسؤال تفكير أعلى\",");
        b.append("\"f\":\"التقويم التكويني\",\"s\":\"التقويم الختامي\",");
        b.append("\"w\":\"ملاحظة أسبوعية مختصرة مناسبة للطالب وولي الأمر\"}.");
        b.append("\nلا تضع حلول أسئلة الكتاب، ولا تنسب شيئاً للخطة إذا لم يظهر في المقتطف.");
        return b.toString();
    }

    private static SourceResult parseSourceResult(String title, String rawJson) throws Exception {
        JSONObject o = new JSONObject(cleanJson(rawJson));
        LessonPreparation preparation = new LessonPreparation(
                title,
                required(o, "c"),
                required(o, "i"),
                required(o, "p"),
                required(o, "f"),
                required(o, "s"),
                required(o, "w")
        );
        return new SourceResult(
                preparation,
                safeLevel(o.optString("l", "الفهم")),
                stringArray(o.optJSONArray("o")),
                stringArray(o.optJSONArray("st")),
                stringArray(o.optJSONArray("rs")),
                Math.max(0, o.optInt("periods", 0)),
                o.optString("start", "").trim(),
                o.optString("end", "").trim()
        );
    }

    private static List<String> stringArray(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            String v = a.optString(i, "").trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    private static String safeLevel(String v) {
        String s = v == null ? "" : v.trim();
        if ("التحليل".equals(s) || "التطبيق".equals(s) || "الفهم".equals(s)) return s;
        return "الفهم";
    }

    private static String limitRaw(String s, int max) {
        String v = s == null ? "" : s.trim();
        return v.length() <= max ? v : v.substring(0, max);
    }

    private static String hashShort(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest((raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 14; i++) hex.append(String.format("%02x", dig[i]));
            return hex.toString();
        } catch (Exception e) {
            return String.valueOf(Math.abs((raw == null ? "" : raw).hashCode()));
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String buildCompactPrompt(String title, List<String> outcomes) {
        StringBuilder b = new StringBuilder(1200);
        b.append("أنت معلم رياضيات عماني خبير. جهز درس الصف التاسع للفصل الدراسي الأول لمنصة نور. ");
        b.append("اكتب عمليًا ومباشرًا ومناسبًا لحصة مدرسية، ولا تكرر عنوان الدرس أو الأهداف.\n");
        b.append("الدرس: ").append(limit(title, 180)).append("\n");
        b.append("المخرجات:");
        if (outcomes != null && !outcomes.isEmpty()) {
            int n = Math.min(6, outcomes.size());
            for (int i=0;i<n;i++) b.append("\n- ").append(limit(outcomes.get(i), 150));
        } else {
            b.append(" استخدم المخرجات المتوقعة من عنوان الدرس.");
        }
        b.append("\nأرجع JSON فقط بهذه المفاتيح القصيرة:");
        b.append("{\"c\":\"المفاهيم\",\"i\":\"التهيئة والتعلم القبلي\",\"p\":\"إجراءات وأنشطة مرقمة\",\"f\":\"التقويم التكويني\",\"s\":\"التقويم الختامي\",\"w\":\"ملاحظة أسبوعية للطالب وولي الأمر\"}");
        b.append("\nفي الإجراءات: ابدأ بنشاط تمهيدي قصير، ثم شرح/اكتشاف، ثم تعلم تعاوني أو فردي، ثم تمايز للمتعثر والمتقدم، ثم سؤال تفكير أعلى. ");
        b.append("اختم بعبارة: ينفذ الطلبة تمارين الدرس المرفقة من ورقة خطواتي نحو التميز. ");
        b.append("اجعل كل حقل مختصرًا لكن كافيًا؛ لا تكتب حلول التمارين.");
        return b.toString();
    }

    private static LessonPreparation parsePreparation(String title, String rawJson) throws Exception {
        JSONObject o = new JSONObject(cleanJson(rawJson));
        return new LessonPreparation(
                title,
                required(o,"c"),
                required(o,"i"),
                required(o,"p"),
                required(o,"f"),
                required(o,"s"),
                required(o,"w")
        );
    }

    private static String required(JSONObject o, String key) {
        String v = o.optString(key, "").trim();
        if (v.isEmpty()) throw new IllegalArgumentException("حقل ناقص من الذكاء: " + key);
        return v;
    }

    private static String cleanJson(String answer) {
        String s = answer == null ? "" : answer.trim();
        int a = s.indexOf('{');
        int z = s.lastIndexOf('}');
        if (a >= 0 && z > a) s = s.substring(a, z + 1);
        return s.trim();
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    private static String cacheKey(String title) {
        try {
            String raw = normalizeTitle(title);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("prep_");
            for (int i=0;i<12;i++) hex.append(String.format("%02x", dig[i]));
            return hex.toString();
        } catch (Exception e) {
            return "prep_" + Math.abs((title == null ? "" : title).hashCode());
        }
    }

    private static String normalizeTitle(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String limit(String s, int max) {
        String v = normalizeTitle(s);
        return v.length() <= max ? v : v.substring(0, max);
    }
}
