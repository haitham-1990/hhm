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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final DiscoveryDiagnostics diagnostics;

    AiPreparationClient(Context context) {
        this.context = context.getApplicationContext();
        this.diagnostics = new DiscoveryDiagnostics(this.context);
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

        String prompt = buildCompactPrompt(title, outcomes);

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

    List<DiscoveredCurriculumStore.Entry> discoverCurriculum(PdfCorpusIndex corpus) throws Exception {
        String planOutline = corpus.planDiscoveryOutline();
        String materialOutline = corpus.materialDiscoveryOutline();
        String key = "catalog_multipass_v3_" + hashShort(planOutline + "\n" + materialOutline);

        String cached = prefs().getString(key, null);
        if (cached != null && !cached.trim().isEmpty()) {
            return parseCatalogArray(cached);
        }

        try {
            JSONObject meta = new JSONObject();
            meta.put("plan_pages", corpus.planPageCount());
            meta.put("material_pages", corpus.materialPageCount());
            meta.put("plan_outline_chars", planOutline.length());
            meta.put("material_outline_chars", materialOutline.length());
            diagnostics.log("discovery_begin", meta);
        } catch (Exception ignored) {}

        JSONObject scope = discoverDocumentScope(planOutline, materialOutline);
        diagnostics.log("scope_result", scope);
        List<Integer> pages = new ArrayList<>();

        int rangeStart = scope.optInt("planStartPage", 0);
        int rangeEnd = scope.optInt("planEndPage", 0);
        if (rangeStart > 0 && rangeEnd >= rangeStart) {
            rangeStart = Math.max(1, rangeStart);
            rangeEnd = Math.min(corpus.planPageCount(), rangeEnd);
            for (int p = rangeStart; p <= rangeEnd; p++) pages.add(p);
        }

        JSONArray pageArray = scope.optJSONArray("planPages");
        if (pageArray != null) {
            for (int i = 0; i < pageArray.length(); i++) {
                int p = pageArray.optInt(i, 0);
                if (p > 0 && p <= corpus.planPageCount() && !pages.contains(p)) pages.add(p);
            }
        }

        // If the scope returned only sample pages, include nearby pages as a safety
        // net. Page-level extraction still filters by the discovered document scope.
        if (!pages.isEmpty()) {
            int min = java.util.Collections.min(pages);
            int max = java.util.Collections.max(pages);
            int from = Math.max(1, min - 3);
            int to = Math.min(corpus.planPageCount(), max + 3);
            for (int p = from; p <= to; p++) if (!pages.contains(p)) pages.add(p);
        }

        // Last-resort generic fallback: inspect all plan pages rather than silently
        // returning a partial curriculum.
        if (pages.isEmpty()) {
            for (int p = 1; p <= corpus.planPageCount(); p++) pages.add(p);
        }

        java.util.Collections.sort(pages);
        List<DiscoveredCurriculumStore.Entry> candidates = new ArrayList<>();

        for (Integer page : pages) {
            if (page == null) continue;
            List<String> segments = corpus.planPageSegments(page);
            if (segments.isEmpty()) segments = java.util.Collections.singletonList(corpus.planPageWindow(page));

            int segmentIndex = 0;
            for (String segment : segments) {
                segmentIndex++;
                List<DiscoveredCurriculumStore.Entry> pageLessons = discoverLessonsOnPlanPage(
                        page,
                        scope,
                        segment
                );
                try {
                    JSONObject meta = new JSONObject();
                    meta.put("plan_page", page);
                    meta.put("segment", segmentIndex);
                    meta.put("segment_chars", segment == null ? 0 : segment.length());
                    JSONArray found = new JSONArray();
                    for (DiscoveredCurriculumStore.Entry e : pageLessons) {
                        JSONObject x = new JSONObject();
                        x.put("code", e.lesson.code);
                        x.put("title", e.lesson.title);
                        x.put("unit", e.lesson.unit);
                        found.put(x);
                    }
                    meta.put("count", found.length());
                    meta.put("lessons", found);
                    diagnostics.log("plan_segment_result", meta);
                } catch (Exception ignored) {}
                mergeExactCandidates(candidates, pageLessons);
            }
        }

        diagnostics.logLessons("candidates_before_hierarchy", candidates);

        if (candidates.isEmpty()) {
            throw new IllegalStateException("تم تحديد صفحات الخطة، لكن لم تُكتشف دروس مستقلة فيها.");
        }

        List<DiscoveredCurriculumStore.Entry> validated =
                validateLessonHierarchy(candidates, planOutline);

        diagnostics.logLessons("catalog_after_hierarchy", validated);

        if (validated.isEmpty()) {
            throw new IllegalStateException("لم تبق دروس صالحة بعد التحقق من بنية الخطة.");
        }

        // Locate every lesson in the material using the full text of every PDF page.
        // This is intentionally independent of any pre-programmed subject/page map
        // and allows multiple lessons to begin on the same physical page.
        validated = corpus.resolveMaterialRanges(validated);
        diagnostics.logLessons("catalog_with_material_ranges", validated);

        String serialized = serializeCatalog(validated);
        prefs().edit().putString(key, serialized).apply();
        return validated;
    }

    private JSONObject discoverDocumentScope(String planOutline, String materialOutline) throws Exception {
        StringBuilder prompt = new StringBuilder(70000);
        prompt.append("قارن مخطط صفحات الخطة الدراسية بمخطط صفحات المادة العلمية. ");
        prompt.append("استنتج فقط من النصين أي مادة/مستوى/فصل دراسي في الخطة يطابق المادة المرفقة. ");
        prompt.append("لا تفترض مادة أو صفاً أو عدداً من الدروس مسبقاً. ");
        prompt.append("حدد كامل المدى المتصل من صفحات PDF في الخطة الذي يغطي هذا المنهج من بدايته إلى نهايته، ");
        prompt.append("ولا ترجع صفحات نموذجية فقط. إذا امتد المنهج على عدة صفحات يجب أن يشمل المدى جميعها. ");
        prompt.append("يمكنك أيضاً إرجاع planPages لأي صفحات إضافية غير متصلة إن وجدت. ");
        prompt.append("لا تعتمد على عدد دروس متوقع مسبقاً. ");
        prompt.append("أرجع JSON فقط: {\"subject\":\"\",\"grade\":\"\",\"semester\":\"\",");
        prompt.append("\"planStartPage\":0,\"planEndPage\":0,\"planPages\":[],");
        prompt.append("\"evidence\":\"سبب مختصر من الوثيقتين\"}.\n");
        prompt.append("\n=== مخطط الخطة صفحة بصفحة ===\n").append(limitRaw(planOutline, 30000));
        prompt.append("\n\n=== مخطط المادة صفحة بصفحة ===\n").append(limitRaw(materialOutline, 32000));

        JSONObject payload = new JSONObject();
        payload.put("message", prompt.toString());
        String answer = cleanJson(postForAnswer(payload));
        return new JSONObject(answer);
    }

    private List<DiscoveredCurriculumStore.Entry> discoverLessonsOnPlanPage(
            int targetPage, JSONObject scope, String planWindow) throws Exception {
        StringBuilder prompt = new StringBuilder(65000);
        prompt.append("حلل نافذة من الخطة الدراسية مع مخطط المادة العلمية، واعتمد فقط ما يظهر في المصدر. ");
        prompt.append("الصفحة المستهدفة هي صفحة PDF رقم ").append(targetPage).append(". ");
        prompt.append("استخرج الدروس المستقلة التي يظهر سجلها/رقمها/عنوانها الأساسي في الصفحة المستهدفة فقط؛ ");
        prompt.append("استخدم الصفحة السابقة والتالية لفهم الاستمرار والهيكل، لكن لا تكرر دروسهما. ");
        prompt.append("استنتج من بنية الخطة نفسها الفرق بين الدرس المستقل وبين عنوان الوحدة أو الموضوع الفرعي أو البند التابع. ");
        prompt.append("لا تعامل كل سطر أو كل حرف فرعي كدرس. لا تفترض عدداً نهائياً للدروس. ");
        prompt.append("احتفظ برمز الدرس كما يظهر في المصدر، وبالوحدة والفصل وعدد الحصص والتواريخ والمخرجات الرسمية إن وجدت. ");
        prompt.append("لا تحاول تحديد صفحات المادة العلمية في هذه المرحلة؛ سيحددها التطبيق لاحقاً من الملف نفسه. ");
        prompt.append("أرجع JSON فقط: {\"lessons\":[{\"code\":\"\",\"title\":\"\",");
        prompt.append("\"unit\":\"\",\"semester\":\"\",\"periods\":0,");
        prompt.append("\"start\":\"\",\"end\":\"\",\"objectives\":[]}]}.\n");
        prompt.append("\nهوية الوثيقة المستنتجة سابقاً: ")
                .append(limitRaw(scope.toString(), 1200));
        prompt.append("\n\n=== نافذة الخطة ===\n").append(limitRaw(planWindow, 26000));


        JSONObject payload = new JSONObject();
        payload.put("message", prompt.toString());
        String answer = cleanJson(postForAnswer(payload));
        return parseDiscoveredCurriculum(answer);
    }

    private List<DiscoveredCurriculumStore.Entry> validateLessonHierarchy(
            List<DiscoveredCurriculumStore.Entry> candidates, String planOutline) throws Exception {
        JSONArray compact = new JSONArray();
        for (int i = 0; i < candidates.size(); i++) {
            DiscoveredCurriculumStore.Entry e = candidates.get(i);
            JSONObject o = new JSONObject();
            o.put("i", i);
            o.put("code", e.lesson.code);
            o.put("title", e.lesson.title);
            o.put("unit", e.lesson.unit);
            compact.put(o);
        }

        StringBuilder prompt = new StringBuilder(50000);
        prompt.append("راجع قائمة مرشحين استُخرجت من خطة دراسية. ");
        prompt.append("مهمتك فقط تصحيح البنية: حدد المرشحين الذين يمثلون دروساً مستقلة فعلاً بحسب تسلسل وعناوين الخطة، ");
        prompt.append("واجمع المرشحين الذين هم موضوعات فرعية أو أجزاء تابعة تحت درسهم الأب. ");
        prompt.append("لا تستخدم معرفة مسبقة عن مادة أو صف، ولا تفترض عدداً مطلوباً من الدروس. ");
        prompt.append("ممنوع إسقاط أي مرشح مستقل من القائمة. مهمتك الدمج فقط عندما يكون المرشح موضوعاً فرعياً تابعاً بوضوح لمرشح أب. ");
        prompt.append("أرجع JSON فقط: {\"merge\":[{\"into\":0,\"from\":[2,3]}]}. ");
        prompt.append("إذا لم يحتج شيء للدمج أرجع merge فارغة.\n");
        prompt.append("\n=== المرشحون ===\n").append(compact.toString());
        prompt.append("\n\n=== مخطط صفحات الخطة للاستدلال على الهيكل ===\n")
                .append(limitRaw(planOutline, 26000));

        JSONObject payload = new JSONObject();
        payload.put("message", prompt.toString());

        try {
            JSONObject decision = new JSONObject(cleanJson(postForAnswer(payload)));
            diagnostics.log("hierarchy_decision", decision);
            return applyHierarchyDecision(candidates, decision);
        } catch (Exception e) {
            diagnostics.logMessage("hierarchy_validation_error", e.getMessage());
            // Safe fallback: exact-deduplicated candidates are still preferable to
            // silently dropping lessons because a validation response failed.
            return candidates;
        }
    }

    private List<DiscoveredCurriculumStore.Entry> applyHierarchyDecision(
            List<DiscoveredCurriculumStore.Entry> source, JSONObject decision) {
        List<DiscoveredCurriculumStore.Entry> working = new ArrayList<>(source);
        JSONArray merges = decision.optJSONArray("merge");
        java.util.Set<Integer> mergedAway = new java.util.HashSet<>();

        if (merges != null) {
            for (int m = 0; m < merges.length(); m++) {
                JSONObject merge = merges.optJSONObject(m);
                if (merge == null) continue;
                int into = merge.optInt("into", -1);
                if (into < 0 || into >= working.size()) continue;

                DiscoveredCurriculumStore.Entry base = working.get(into);
                JSONArray from = merge.optJSONArray("from");
                if (from == null) continue;

                List<String> objectives = new ArrayList<>(base.lesson.objectives);
                int startPage = base.materialStartPage;
                int endPage = base.materialEndPage;

                for (int j = 0; j < from.length(); j++) {
                    int idx = from.optInt(j, -1);
                    if (idx < 0 || idx >= working.size() || idx == into) continue;
                    DiscoveredCurriculumStore.Entry child = working.get(idx);
                    mergedAway.add(idx);
                    for (String objective : child.lesson.objectives) {
                        if (!objectives.contains(objective)) objectives.add(objective);
                    }
                    if (child.materialStartPage > 0 && (startPage <= 0 || child.materialStartPage < startPage)) {
                        startPage = child.materialStartPage;
                    }
                    if (child.materialEndPage > endPage) endPage = child.materialEndPage;
                }

                CurriculumLesson merged = new CurriculumLesson(
                        base.lesson.code,
                        base.lesson.title,
                        base.lesson.unit,
                        base.lesson.semester,
                        base.lesson.periods,
                        base.lesson.periodStart,
                        base.lesson.periodEnd,
                        base.lesson.level,
                        objectives,
                        base.lesson.strategies,
                        base.lesson.resources
                );
                working.set(into, new DiscoveredCurriculumStore.Entry(merged, startPage, endPage));
            }
        }

        List<DiscoveredCurriculumStore.Entry> out = new ArrayList<>();
        for (int i = 0; i < working.size(); i++) {
            if (mergedAway.contains(i)) continue;
            out.add(working.get(i));
        }
        return out;
    }

    private void mergeExactCandidates(List<DiscoveredCurriculumStore.Entry> base,
                                      List<DiscoveredCurriculumStore.Entry> additions) {
        for (DiscoveredCurriculumStore.Entry item : additions) {
            int match = -1;
            String itemKey = normalizeTitle(item.lesson.code + "|" + item.lesson.title);
            for (int i = 0; i < base.size(); i++) {
                DiscoveredCurriculumStore.Entry existing = base.get(i);
                String existingKey = normalizeTitle(existing.lesson.code + "|" + existing.lesson.title);
                if (!itemKey.isEmpty() && itemKey.equals(existingKey)) {
                    match = i;
                    break;
                }
            }
            if (match < 0) {
                base.add(item);
                continue;
            }

            DiscoveredCurriculumStore.Entry existing = base.get(match);
            List<String> objectives = new ArrayList<>(existing.lesson.objectives);
            for (String objective : item.lesson.objectives) {
                if (!objectives.contains(objective)) objectives.add(objective);
            }

            int ps = existing.materialStartPage;
            if (item.materialStartPage > 0 && (ps <= 0 || item.materialStartPage < ps)) {
                ps = item.materialStartPage;
            }
            int pe = Math.max(existing.materialEndPage, item.materialEndPage);

            CurriculumLesson lesson = new CurriculumLesson(
                    existing.lesson.code,
                    existing.lesson.title,
                    existing.lesson.unit.isEmpty() ? item.lesson.unit : existing.lesson.unit,
                    existing.lesson.semester.isEmpty() ? item.lesson.semester : existing.lesson.semester,
                    Math.max(existing.lesson.periods, item.lesson.periods),
                    existing.lesson.periodStart.isEmpty() ? item.lesson.periodStart : existing.lesson.periodStart,
                    existing.lesson.periodEnd.isEmpty() ? item.lesson.periodEnd : existing.lesson.periodEnd,
                    existing.lesson.level,
                    objectives,
                    existing.lesson.strategies,
                    existing.lesson.resources
            );
            base.set(match, new DiscoveredCurriculumStore.Entry(lesson, ps, pe));
        }
    }

    private String serializeCatalog(List<DiscoveredCurriculumStore.Entry> entries) throws Exception {
        JSONArray lessons = new JSONArray();
        for (DiscoveredCurriculumStore.Entry e : entries) {
            JSONObject o = new JSONObject();
            o.put("code", e.lesson.code);
            o.put("title", e.lesson.title);
            o.put("unit", e.lesson.unit);
            o.put("semester", e.lesson.semester);
            o.put("periods", e.lesson.periods);
            o.put("start", e.lesson.periodStart);
            o.put("end", e.lesson.periodEnd);
            o.put("objectives", new JSONArray(e.lesson.objectives));
            o.put("materialStartPage", e.materialStartPage);
            o.put("materialEndPage", e.materialEndPage);
            lessons.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("lessons", lessons);
        return root.toString();
    }

    private List<DiscoveredCurriculumStore.Entry> parseCatalogArray(String json) throws Exception {
        return parseDiscoveredCurriculum(json);
    }

    private List<DiscoveredCurriculumStore.Entry> parseDiscoveredCurriculum(String rawJson) throws Exception {
        JSONObject root = new JSONObject(cleanJson(rawJson));
        JSONArray lessons = root.optJSONArray("lessons");
        List<DiscoveredCurriculumStore.Entry> out = new ArrayList<>();
        if (lessons == null) return out;

        String rootSemester = root.optString("semester", "").trim();
        for (int i = 0; i < lessons.length(); i++) {
            JSONObject o = lessons.optJSONObject(i);
            if (o == null) continue;

            String title = o.optString("title", "").trim();
            if (title.isEmpty()) continue;

            String sourceCode = o.optString("code", "").trim();
            String internalCode = sourceCode.isEmpty()
                    ? "AUTO-" + String.format(java.util.Locale.US, "%03d", i + 1)
                    : sourceCode;

            CurriculumLesson lesson = new CurriculumLesson(
                    internalCode,
                    title,
                    o.optString("unit", "").trim(),
                    o.optString("semester", rootSemester).trim(),
                    Math.max(1, o.optInt("periods", 1)),
                    o.optString("start", "").trim(),
                    o.optString("end", "").trim(),
                    "الفهم",
                    stringArray(o.optJSONArray("objectives")),
                    new ArrayList<>(),
                    new ArrayList<>()
            );

            int ps = Math.max(0, o.optInt("materialStartPage", 0));
            int pe = Math.max(ps, o.optInt("materialEndPage", ps));
            out.add(new DiscoveredCurriculumStore.Entry(lesson, ps, pe));
        }
        return out;
    }

    private String postForAnswer(JSONObject payload) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(Math.max(READ_TIMEOUT_MS, 120000));
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) { out.write(body); }
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String response = readAll(stream);
            if (code < 200 || code >= 300) throw new IllegalStateException("خدمة ذكاء خطوة أعادت HTTP " + code);
            JSONObject envelope = new JSONObject(response);
            String answer = envelope.optString("answer", "").trim();
            if (answer.isEmpty()) throw new IllegalStateException("استجابة ذكاء خطوة فارغة");
            return answer;
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
        StringBuilder b = new StringBuilder(1400);
        b.append("جهز تحضيراً تعليمياً عملياً للدرس التالي اعتماداً على عنوانه ومخرجاته فقط. ");
        b.append("لا تفترض مادة أو صفاً غير مذكورين. لا تخترع مخرجاً رسمياً.\n");
        b.append("الدرس: ").append(limit(title, 220)).append("\n");
        b.append("المخرجات:");
        if (outcomes != null && !outcomes.isEmpty()) {
            for (int i = 0; i < Math.min(8, outcomes.size()); i++) {
                b.append("\n- ").append(limit(outcomes.get(i), 180));
            }
        } else {
            b.append(" غير متوفرة في المصدر.");
        }
        b.append("\nأرجع JSON فقط:");
        b.append("{\"c\":\"المفاهيم\",\"i\":\"التهيئة والتعلم القبلي\",");
        b.append("\"p\":\"إجراءات وأنشطة مرقمة\",\"f\":\"التقويم التكويني\",");
        b.append("\"s\":\"التقويم الختامي\",\"w\":\"ملاحظة أسبوعية للطالب وولي الأمر\"}.");
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
