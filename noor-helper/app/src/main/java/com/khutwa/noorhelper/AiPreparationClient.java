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
        String key = "catalog_fullscan_v5_longmaterial_" + hashShort(planOutline + "\n" + materialOutline);

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

        // Do not trust one predicted start/end range as the sole source of truth.
        // The plan may be long, split across pages, or the model may identify only
        // a sample of the matching section. Inspect every plan page and let the AI
        // reject pages that do not belong to the discovered subject/grade/semester.
        List<Integer> pages = new ArrayList<>();
        for (int p = 1; p <= corpus.planPageCount(); p++) pages.add(p);

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
                    meta.put("segment_excerpt", limitRaw(segment == null ? "" : segment, 1400));
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

        // A timetable often prints unit dates once in a footer/header instead of
        // repeating them beside every lesson. Re-read the plan globally and attach
        // those unit-level facts to each discovered lesson. This is generic and
        // source-driven; it does not contain any subject/grade-specific schedule.
        try {
            validated = enrichPlanningMetadata(validated, planOutline, scope);
            diagnostics.logLessons("catalog_after_planning_metadata", validated);
        } catch (Exception e) {
            diagnostics.logMessage("planning_metadata_enrichment_error", e.getMessage());
        }

        // Locate lesson starts from the material's own page-by-page outline with AI.
        // This avoids confusing objective numbers inside questions with real lesson headings.
        try {
            validated = locateMaterialRanges(corpus, validated);
        } catch (Exception e) {
            diagnostics.logMessage("material_mapping_ai_error", e.getMessage());
            validated = corpus.resolveMaterialRanges(validated);
        }
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
        prompt.append("حلل هذه الصفحة من الخطة الدراسية واعتمد فقط ما يظهر في المصدر. ");
        prompt.append("الصفحة المستهدفة هي صفحة PDF رقم ").append(targetPage).append(". ");
        prompt.append("قارن محتوى الصفحة بهوية الوثيقة المستنتجة سابقاً (المادة/المستوى/الفصل). ");
        prompt.append("إذا كانت الصفحة لا تخص نفس المنهج تحديداً فأرجع lessons فارغة، ولا تستخرج دروس صف أو مادة أخرى. ");
        prompt.append("إذا كانت تخص المنهج، استخرج الدروس المستقلة التي يظهر سجلها/رقمها/عنوانها الأساسي في الصفحة المستهدفة فقط؛ ");
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

    private List<DiscoveredCurriculumStore.Entry> enrichPlanningMetadata(
            List<DiscoveredCurriculumStore.Entry> source,
            String planOutline,
            JSONObject scope) throws Exception {
        if (source == null || source.isEmpty()) return source;

        JSONArray lessons = new JSONArray();
        for (int i = 0; i < source.size(); i++) {
            CurriculumLesson l = source.get(i).lesson;
            JSONObject o = new JSONObject();
            o.put("i", i);
            o.put("code", l.code);
            o.put("title", l.title);
            o.put("unit", l.unit);
            o.put("semester", l.semester);
            o.put("periods", l.periods);
            o.put("start", l.periodStart);
            o.put("end", l.periodEnd);
            lessons.put(o);
        }

        StringBuilder prompt = new StringBuilder(60000);
        prompt.append("أنت تربط بيانات الجدول الزمني بخطة دراسية مكتوبة. ");
        prompt.append("اعتمد حصراً على الخطة المرفقة وهوية الوثيقة. لا تستخدم أي معرفة مسبقة بالمادة أو الصف. ");
        prompt.append("لكل درس في القائمة أعد: اسم الوحدة كما يظهر في الخطة، الفصل الدراسي، عدد الحصص، تاريخ بداية فترة الوحدة وتاريخ نهايتها. ");
        prompt.append("مهم جداً: قد تُكتب تواريخ الوحدة مرة واحدة في أسفل الصفحة أو رأسها تحت اسم الوحدة، ولا تتكرر أمام كل درس. ");
        prompt.append("في هذه الحالة انقل تاريخ بداية ونهاية الوحدة إلى جميع دروس الوحدة نفسها إذا كان الربط واضحاً من ترتيب الأعمدة/العناوين. ");
        prompt.append("لا تخترع تاريخاً. إذا لم يوجد دليل واضح اترك start/end فارغين. ");
        prompt.append("لا تغيّر code أو title ولا تعيد ترتيب الدروس. ");
        prompt.append("أرجع JSON فقط: {\"lessons\":[{\"i\":0,\"unit\":\"\",\"semester\":\"\",\"periods\":0,\"start\":\"YYYY-MM-DD أو فارغ\",\"end\":\"YYYY-MM-DD أو فارغ\"}]}.");
        prompt.append("\n\nهوية الوثيقة: ").append(limitRaw(scope == null ? "" : scope.toString(), 1500));
        prompt.append("\n\n=== الدروس المكتشفة ===\n").append(lessons.toString());
        prompt.append("\n\n=== مخطط الخطة كاملاً ===\n").append(limitRaw(planOutline, 30000));

        JSONObject payload = new JSONObject();
        payload.put("message", prompt.toString());
        JSONObject answer = new JSONObject(cleanJson(postForAnswer(payload)));
        JSONArray rows = answer.optJSONArray("lessons");
        if (rows == null) return source;

        List<DiscoveredCurriculumStore.Entry> out = new ArrayList<>(source);
        for (int r = 0; r < rows.length(); r++) {
            JSONObject row = rows.optJSONObject(r);
            if (row == null) continue;
            int i = row.optInt("i", -1);
            if (i < 0 || i >= source.size()) continue;

            DiscoveredCurriculumStore.Entry old = source.get(i);
            CurriculumLesson l = old.lesson;

            String unit = row.optString("unit", "").trim();
            String semester = row.optString("semester", "").trim();
            int periods = row.optInt("periods", 0);
            String start = row.optString("start", "").trim();
            String end = row.optString("end", "").trim();

            CurriculumLesson enriched = new CurriculumLesson(
                    l.code,
                    l.title,
                    unit.isEmpty() ? l.unit : unit,
                    semester.isEmpty() ? l.semester : semester,
                    periods > 0 ? periods : l.periods,
                    start.isEmpty() ? l.periodStart : start,
                    end.isEmpty() ? l.periodEnd : end,
                    l.level,
                    l.objectives,
                    l.strategies,
                    l.resources
            );
            out.set(i, new DiscoveredCurriculumStore.Entry(
                    enriched, old.materialStartPage, old.materialEndPage));
        }
        return out;
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
        prompt.append("ممنوع إسقاط أي مرشح مستقل من القائمة. ");
        prompt.append("إذا كان الدرس الأب موجوداً ضمن المرشحين فاستخدم merge لضم أجزائه إليه. ");
        prompt.append("إذا ظهرت عدة أجزاء متتابعة برمز فرعي مشترك مثل رقم-رقم-حرف، ولم يوجد الدرس الأب نفسه ضمن المرشحين، ");
        prompt.append("فاجمع هذه الأجزاء في درس أب جديد واحد باستخدام groups، مع عنوان جامع مستنتج فقط من الخطة ورمز الأب بدون الحرف الفرعي. ");
        prompt.append("لا تنشئ مجموعة إلا إذا كانت البنية في المصدر واضحة، ولا تستخدم أي عدد متوقع مسبقاً للدروس. ");
        prompt.append("أرجع JSON فقط بهذه البنية: ");
        prompt.append("{\"merge\":[{\"into\":0,\"from\":[2,3]}],");
        prompt.append("\"groups\":[{\"from\":[10,11,12],\"code\":\"9-2\",\"title\":\"عنوان الدرس الأب\",\"unit\":\"اسم الوحدة\"}]}. ");
        prompt.append("إذا لم توجد عمليات اجعل المصفوفتين فارغتين.\n");
        prompt.append("\n=== المرشحون ===\n").append(compact.toString());
        prompt.append("\n\n=== مخطط صفحات الخطة للاستدلال على الهيكل ===\n")
                .append(limitRaw(planOutline, 26000));

        JSONObject payload = new JSONObject();
        payload.put("message", prompt.toString());

        try {
            JSONObject decision = new JSONObject(cleanJson(postForAnswer(payload)));
            diagnostics.log("hierarchy_decision", decision);
            List<DiscoveredCurriculumStore.Entry> decided = applyHierarchyDecision(candidates, decision);
            List<DiscoveredCurriculumStore.Entry> normalized = normalizeLetteredLessonCodes(decided);
            diagnostics.logLessons("catalog_after_code_structure_normalization", normalized);
            return normalized;
        } catch (Exception e) {
            diagnostics.logMessage("hierarchy_validation_error", e.getMessage());
            // Even when the AI hierarchy response fails, preserve all independent
            // candidates while applying only the generic code hierarchy visible in
            // the source itself (for example N-M-أ / N-M-ب under N-M).
            List<DiscoveredCurriculumStore.Entry> normalized = normalizeLetteredLessonCodes(candidates);
            diagnostics.logLessons("catalog_after_code_structure_fallback", normalized);
            return normalized;
        }
    }

    private List<DiscoveredCurriculumStore.Entry> normalizeLetteredLessonCodes(
            List<DiscoveredCurriculumStore.Entry> source) {
        if (source == null || source.isEmpty()) return source;

        Pattern subPattern = Pattern.compile("^\\s*([0-9٠-٩]+)\\s*[-–]\\s*([0-9٠-٩]+)\\s*[-–]\\s*([\\p{L}]+)\\s*$");
        Map<String, Integer> parentIndex = new LinkedHashMap<>();
        Map<String, List<Integer>> children = new LinkedHashMap<>();

        for (int i = 0; i < source.size(); i++) {
            String code = source.get(i).lesson.code == null ? "" : source.get(i).lesson.code.trim();
            Matcher sub = subPattern.matcher(code);
            if (sub.matches()) {
                String base = sub.group(1) + "-" + sub.group(2);
                if (!children.containsKey(base)) children.put(base, new ArrayList<>());
                children.get(base).add(i);
            } else {
                String base = baseLessonCode(code);
                if (!base.isEmpty() && base.equals(code.replaceAll("\\s+", ""))) {
                    parentIndex.put(base, i);
                }
            }
        }

        List<DiscoveredCurriculumStore.Entry> working = new ArrayList<>(source);
        java.util.Set<Integer> remove = new java.util.HashSet<>();

        for (Map.Entry<String, List<Integer>> groupEntry : children.entrySet()) {
            String base = groupEntry.getKey();
            List<Integer> idxs = groupEntry.getValue();
            if (idxs == null || idxs.size() < 2) continue;
            java.util.Collections.sort(idxs);

            // Only collapse a contiguous sibling run. This avoids combining
            // unrelated codes that happen to share the same numeric prefix.
            boolean contiguous = true;
            for (int j = 1; j < idxs.size(); j++) {
                if (idxs.get(j) != idxs.get(j - 1) + 1) {
                    contiguous = false;
                    break;
                }
            }
            if (!contiguous) continue;

            Integer parent = parentIndex.get(base);
            int anchor = parent != null ? parent : idxs.get(0);
            DiscoveredCurriculumStore.Entry baseEntry = working.get(anchor);

            List<String> objectives = new ArrayList<>(baseEntry.lesson.objectives);
            List<String> strategies = new ArrayList<>(baseEntry.lesson.strategies);
            List<String> resources = new ArrayList<>(baseEntry.lesson.resources);
            int parentPeriods = parent != null ? Math.max(1, baseEntry.lesson.periods) : 0;
            int childPeriods = 0;
            int startPage = baseEntry.materialStartPage;
            int endPage = baseEntry.materialEndPage;
            String periodStart = baseEntry.lesson.periodStart;
            String periodEnd = baseEntry.lesson.periodEnd;

            for (Integer idx : idxs) {
                if (parent != null && idx == parent) continue;
                DiscoveredCurriculumStore.Entry child = working.get(idx);
                if (parent == null || idx != anchor) childPeriods += Math.max(1, child.lesson.periods);
                for (String x : child.lesson.objectives) if (!objectives.contains(x)) objectives.add(x);
                for (String x : child.lesson.strategies) if (!strategies.contains(x)) strategies.add(x);
                for (String x : child.lesson.resources) if (!resources.contains(x)) resources.add(x);
                if (child.materialStartPage > 0 && (startPage <= 0 || child.materialStartPage < startPage)) {
                    startPage = child.materialStartPage;
                }
                if (child.materialEndPage > endPage) endPage = child.materialEndPage;
                if (periodStart.isEmpty() && !child.lesson.periodStart.isEmpty()) periodStart = child.lesson.periodStart;
                if (!child.lesson.periodEnd.isEmpty()) periodEnd = child.lesson.periodEnd;
                if (idx != anchor) remove.add(idx);
            }

            String title = baseEntry.lesson.title;
            String unit = baseEntry.lesson.unit;
            CurriculumLesson parentLesson = new CurriculumLesson(
                    base,
                    title,
                    unit,
                    baseEntry.lesson.semester,
                    Math.max(1, parent != null ? Math.max(parentPeriods, childPeriods) : childPeriods),
                    periodStart,
                    periodEnd,
                    baseEntry.lesson.level,
                    objectives,
                    strategies,
                    resources
            );
            working.set(anchor, new DiscoveredCurriculumStore.Entry(parentLesson, startPage, endPage));
        }

        List<DiscoveredCurriculumStore.Entry> out = new ArrayList<>();
        for (int i = 0; i < working.size(); i++) {
            if (!remove.contains(i)) out.add(working.get(i));
        }
        return out;
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

        JSONArray groups = decision.optJSONArray("groups");
        java.util.Set<Integer> groupedAway = new java.util.HashSet<>();
        if (groups != null) {
            for (int g = 0; g < groups.length(); g++) {
                JSONObject group = groups.optJSONObject(g);
                if (group == null) continue;
                JSONArray from = group.optJSONArray("from");
                if (from == null || from.length() < 2) continue;

                List<Integer> indexes = new ArrayList<>();
                for (int j = 0; j < from.length(); j++) {
                    int idx = from.optInt(j, -1);
                    if (idx < 0 || idx >= working.size() || mergedAway.contains(idx)) continue;
                    if (!indexes.contains(idx)) indexes.add(idx);
                }
                java.util.Collections.sort(indexes);
                if (indexes.size() < 2) continue;

                int anchor = indexes.get(0);
                DiscoveredCurriculumStore.Entry first = working.get(anchor);
                List<String> objectives = new ArrayList<>();
                List<String> strategies = new ArrayList<>();
                List<String> resources = new ArrayList<>();
                int periods = 0;
                int startPage = 0;
                int endPage = 0;
                String semester = first.lesson.semester;
                String periodStart = "";
                String periodEnd = "";

                for (Integer idx : indexes) {
                    DiscoveredCurriculumStore.Entry child = working.get(idx);
                    periods += Math.max(1, child.lesson.periods);
                    for (String x : child.lesson.objectives) if (!objectives.contains(x)) objectives.add(x);
                    for (String x : child.lesson.strategies) if (!strategies.contains(x)) strategies.add(x);
                    for (String x : child.lesson.resources) if (!resources.contains(x)) resources.add(x);
                    if (child.materialStartPage > 0 && (startPage <= 0 || child.materialStartPage < startPage)) {
                        startPage = child.materialStartPage;
                    }
                    if (child.materialEndPage > endPage) endPage = child.materialEndPage;
                    if (periodStart.isEmpty() && !child.lesson.periodStart.isEmpty()) periodStart = child.lesson.periodStart;
                    if (!child.lesson.periodEnd.isEmpty()) periodEnd = child.lesson.periodEnd;
                }

                String code = group.optString("code", "").trim();
                if (code.isEmpty()) code = baseLessonCode(first.lesson.code);
                String title = group.optString("title", "").trim();
                if (title.isEmpty()) title = first.lesson.title;
                String unit = group.optString("unit", "").trim();
                if (unit.isEmpty()) unit = first.lesson.unit;

                CurriculumLesson parent = new CurriculumLesson(
                        code,
                        title,
                        unit,
                        semester,
                        Math.max(1, periods),
                        periodStart,
                        periodEnd,
                        first.lesson.level,
                        objectives,
                        strategies,
                        resources
                );
                working.set(anchor, new DiscoveredCurriculumStore.Entry(parent, startPage, endPage));
                for (int j = 1; j < indexes.size(); j++) groupedAway.add(indexes.get(j));
            }
        }

        List<DiscoveredCurriculumStore.Entry> out = new ArrayList<>();
        for (int i = 0; i < working.size(); i++) {
            if (mergedAway.contains(i) || groupedAway.contains(i)) continue;
            out.add(working.get(i));
        }
        return out;
    }

    private static String baseLessonCode(String raw) {
        String v = raw == null ? "" : raw.trim();
        Matcher m = Pattern.compile("^\\s*([0-9٠-٩]+)\\s*[-–]\\s*([0-9٠-٩]+)").matcher(v);
        if (m.find()) return m.group(1) + "-" + m.group(2);
        return v;
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

    private List<DiscoveredCurriculumStore.Entry> locateMaterialRanges(
            PdfCorpusIndex corpus, List<DiscoveredCurriculumStore.Entry> catalog) throws Exception {
        JSONArray compact = new JSONArray();
        for (int i = 0; i < catalog.size(); i++) {
            DiscoveredCurriculumStore.Entry e = catalog.get(i);
            JSONObject o = new JSONObject();
            o.put("i", i);
            o.put("code", e.lesson.code);
            o.put("title", e.lesson.title);
            o.put("unit", e.lesson.unit);
            compact.put(o);
        }

        int[] starts = new int[catalog.size()];
        int[] ranks = new int[catalog.size()];
        String[] evidences = new String[catalog.size()];
        List<String> batches = corpus.materialDiscoveryBatches(22000);

        for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            String batch = batches.get(batchIndex);
            StringBuilder prompt = new StringBuilder(52000);
            prompt.append("لدي قائمة دروس مرتبة ومقطع متصل من مخطط صفحات ملف المادة العلمية. ");
            prompt.append("ابحث فقط عن بدايات الدروس التي يظهر عنوانها الفعلي داخل هذا المقطع. ");
            prompt.append("لا تعتبر الفهرس أو قائمة المحتويات أو رقم هدف أو سؤال أو مثال عنوان درس. ");
            prompt.append("لا تستنتج صفحة لدرس غير ظاهر فعلياً في هذا المقطع، ولا تملأ الدروس المفقودة اعتماداً على الترتيب وحده. ");
            prompt.append("إذا ظهر عنوان الدرس الحقيقي، أعد رقم صفحة PDF كما هو مكتوب في علامة [MATERIAL PDF N]. ");
            prompt.append("يمكن أن يبدأ درسان في الصفحة نفسها. أرجع فقط العناصر التي وجدت لها دليلاً في هذا المقطع. ");
            prompt.append("لا تستخدم معرفة مسبقة بالمادة أو الصف. ");
            prompt.append("أرجع JSON فقط: {\"starts\":[{\"i\":0,\"page\":5,\"confidence\":\"high\",\"evidence\":\"عنوان الدرس ظاهر\"}]}.");
            prompt.append("\n\n=== الدروس المرتبة ===\n").append(compact.toString());
            prompt.append("\n\n=== مقطع مخطط المادة ").append(batchIndex + 1)
                    .append(" من ").append(batches.size()).append(" ===\n").append(batch);

            JSONObject payload = new JSONObject();
            payload.put("message", prompt.toString());
            JSONObject decision = new JSONObject(cleanJson(postForAnswer(payload)));

            try {
                JSONObject meta = new JSONObject();
                meta.put("batch", batchIndex + 1);
                meta.put("batch_count", batches.size());
                meta.put("decision", decision);
                diagnostics.log("material_mapping_batch_decision", meta);
            } catch (Exception ignored) {}

            JSONArray mapped = decision.optJSONArray("starts");
            if (mapped == null) continue;
            for (int j = 0; j < mapped.length(); j++) {
                JSONObject m = mapped.optJSONObject(j);
                if (m == null) continue;
                int idx = m.optInt("i", -1);
                int page = m.optInt("page", 0);
                if (idx < 0 || idx >= starts.length || page < 1 || page > corpus.materialPageCount()) continue;

                String confidence = m.optString("confidence", "").trim().toLowerCase(java.util.Locale.ROOT);
                int rank = "high".equals(confidence) ? 3 : ("medium".equals(confidence) ? 2 : 1);

                // Prefer stronger evidence. On equal evidence prefer the later occurrence:
                // an early contents page often repeats every lesson title before the real heading.
                if (rank > ranks[idx] || (rank == ranks[idx] && page > starts[idx])) {
                    starts[idx] = page;
                    ranks[idx] = rank;
                    evidences[idx] = m.optString("evidence", "").trim();
                }
            }
        }

        List<DiscoveredCurriculumStore.Entry> lexical = corpus.resolveMaterialRanges(catalog);
        for (int i = 0; i < starts.length; i++) {
            if (starts[i] <= 0 && i < lexical.size()) {
                int lexicalStart = lexical.get(i).materialStartPage;
                if (lexicalStart > 0) {
                    starts[i] = lexicalStart;
                    ranks[i] = 1;
                    evidences[i] = "مطابقة نصية احتياطية داخل كامل ملف المادة";
                }
            }
        }

        // Keep source order monotonic while preserving same-page lesson starts.
        int floor = 1;
        for (int i = 0; i < starts.length; i++) {
            if (starts[i] <= 0) continue;
            if (starts[i] < floor) starts[i] = floor;
            floor = starts[i];
        }

        try {
            JSONObject merged = new JSONObject();
            JSONArray mapped = new JSONArray();
            for (int i = 0; i < starts.length; i++) {
                JSONObject m = new JSONObject();
                m.put("i", i);
                if (starts[i] > 0) m.put("page", starts[i]); else m.put("page", JSONObject.NULL);
                m.put("confidence", ranks[i] >= 3 ? "high" : (ranks[i] == 2 ? "medium" : (ranks[i] == 1 ? "fallback" : "unresolved")));
                m.put("evidence", evidences[i] == null ? "" : evidences[i]);
                mapped.put(m);
            }
            merged.put("batch_count", batches.size());
            merged.put("starts", mapped);
            diagnostics.log("material_mapping_decision", merged);
        } catch (Exception ignored) {}

        List<DiscoveredCurriculumStore.Entry> out = new ArrayList<>();
        for (int i = 0; i < catalog.size(); i++) {
            DiscoveredCurriculumStore.Entry old = catalog.get(i);
            int start = starts[i];
            if (start <= 0) {
                out.add(new DiscoveredCurriculumStore.Entry(old.lesson, 0, 0));
                continue;
            }

            int nextStart = 0;
            for (int j = i + 1; j < starts.length; j++) {
                if (starts[j] > 0) {
                    nextStart = starts[j];
                    break;
                }
            }

            int end;
            if (nextStart > 0) {
                end = Math.max(start, nextStart);
            } else {
                // Never let an unresolved tail make one lesson swallow the rest of
                // a long PDF. Use lexical evidence when available; otherwise keep a
                // conservative one-page range until a later mapping resolves it.
                int lexicalEnd = i < lexical.size() ? lexical.get(i).materialEndPage : 0;
                if (lexicalEnd >= start && lexicalEnd <= corpus.materialPageCount()) {
                    end = lexicalEnd;
                } else {
                    end = start;
                }
            }

            out.add(new DiscoveredCurriculumStore.Entry(old.lesson, start, end));
        }
        return out;
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
        String key = "source_flow_v2_" + hashShort(sourceKey);
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
            SourceResult result;
            try {
                result = parseSourceResult(title, json);
            } catch (Exception firstParseError) {
                diagnostics.logMessage("preparation_json_parse_error",
                        title + ": " + firstParseError.getMessage());
                JSONObject repairPayload = new JSONObject();
                repairPayload.put("message",
                        "أصلح النص التالي إلى JSON صحيح فقط دون أي شرح، مع الحفاظ على المعنى وجميع الحقول "
                        + "o,l,periods,start,end,st,rs,c,i,p,f,s,w. "
                        + "الحقل p يمكن أن يكون مصفوفة مراحل تدريسية بعناصر t,teacher,student,source,check,pg. "
                        + "لا تضف مفاتيح أخرى. الدرس: " + limit(title, 180)
                        + "\nالنص غير الصالح:\n" + limitRaw(answer, 7000));
                String repaired = cleanJson(postForAnswer(repairPayload));
                result = parseSourceResult(title, repaired);
                json = repaired;
                diagnostics.logMessage("preparation_json_repaired", title);
            }
            prefs().edit().putString(key, json).apply();
            return result;
        } finally {
            conn.disconnect();
        }
    }

    private static String buildSourcePrompt(String title, List<String> noorOutcomes,
                                            String planContext, String materialContext) {
        StringBuilder b = new StringBuilder(26000);
        b.append("أنت محرك تحضير تربوي عام لمنصة نور في سلطنة عمان. ");
        b.append("المعلم قد يدرّس أي مادة وأي صف؛ لا تفترض الرياضيات أو نوعاً محدداً من الدروس. ");
        b.append("اعتمد حصراً على مقتطف الخطة الدراسية ومقتطف المادة العلمية أدناه. ");
        b.append("افهم المادة العلمية أولاً: ميّز بين الشرح، المفهوم، المثال، النص، الشكل، الجدول، التجربة، النشاط، السؤال أو التمرين بحسب ما يظهر فعلياً. ");
        b.append("بعد الفهم، أعد تنظيم الدرس تربوياً؛ لا تنسخ صفحات المصدر كسرد طويل، ولا تجعل بنية سير الدرس قالباً ثابتاً لا يناسب المادة. ");
        b.append("لا تخترع مخرجاً رسمياً أو تاريخاً أو عدد حصص غير مذكور في الخطة. ");
        b.append("إذا لم يظهر تاريخ أو عدد حصص بوضوح أرجع قيمة فارغة/0.\n");
        b.append("الدرس المستهدف: ").append(limit(title, 220)).append("\n");

        if (noorOutcomes != null && !noorOutcomes.isEmpty()) {
            b.append("المخرجات الظاهرة حالياً في نور (للمقارنة فقط):\n");
            for (int i = 0; i < Math.min(8, noorOutcomes.size()); i++) {
                b.append("- ").append(limit(noorOutcomes.get(i), 220)).append("\n");
            }
        }

        b.append("\n=== مقتطف الخطة الدراسية ===\n").append(limitRaw(planContext, 7000));
        b.append("\n\n=== مقتطف المادة العلمية ===\n").append(limitRaw(materialContext, 14000));

        b.append("\n\nأنشئ سير درس عملياً واضحاً من 3 إلى 6 مراحل بحسب طبيعة المحتوى نفسه. ");
        b.append("قد تكون المرحلة استكشافاً، قراءة وتحليلاً، تجربة، نمذجة، مثالاً موجهاً، مناقشة، تطبيقاً، ممارسة أو غير ذلك؛ اختر ما يناسب المصدر. ");
        b.append("لكل مرحلة اذكر: عنواناً قصيراً، دور المعلم، دور الطالب، ما يستخدم من المادة العلمية، وطريقة تحقق سريعة من التعلم. ");
        b.append("اجعل التعليمات قابلة للتنفيذ داخل الحصة وليست عبارات عامة. ");
        b.append("لا تضع إجابات مباشرة لأسئلة الكتاب أو التمارين، ويمكنك وصف طريقة التوجيه دون كشف الحل. ");
        b.append("إذا كانت صفحة معينة تحتوي عنصراً بصرياً مفيداً للمرحلة مثل شكل أو جدول أو خريطة أو تجربة أو مثال يستحق العرض، ");
        b.append("ضع رقم صفحة PDF الحقيقي في pg كما يظهر فقط في علامة [MATERIAL PDF N]. ");
        b.append("لا تضع رقم صفحة بالتخمين، ولا تستخدم pg لمجرد أن الصفحة تحتوي نصاً. ");
        b.append("استخدم بحد أقصى 3 صفحات بصرية فريدة في الدرس كله، ويمكن أن تكون pg فارغة تماماً.");

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
        b.append("\"c\":\"المفاهيم الأساسية من المصدر\",");
        b.append("\"i\":\"تهيئة قصيرة مرتبطة بالتعلم القبلي ومحتوى الدرس\",");
        b.append("\"p\":[");
        b.append("{\"t\":\"عنوان المرحلة\",\"teacher\":\"دور المعلم\",\"student\":\"دور الطالب\",");
        b.append("\"source\":\"المحتوى أو المثال أو النص أو الشكل المستخدم من المادة دون اختلاق\",");
        b.append("\"check\":\"تحقق سريع\",\"pg\":[0]}");
        b.append("],");
        b.append("\"f\":\"تقويم تكويني مرتبط بما تم تعلمه\",");
        b.append("\"s\":\"تقويم ختامي يقيس مخرج الدرس\",");
        b.append("\"w\":\"ملاحظة أسبوعية مختصرة مناسبة للطالب وولي الأمر\"}.");
        b.append("\nفي pg احذف 0 واستبدله فقط بأرقام صفحات PDF الموجودة فعلياً في المقتطف، أو أرجع [] إذا لا توجد صفحة بصرية مهمة.");
        return b.toString();
    }

    private static SourceResult parseSourceResult(String title, String rawJson) throws Exception {
        JSONObject o = new JSONObject(cleanJson(rawJson));
        LessonPreparation preparation = new LessonPreparation(
                title,
                required(o, "c"),
                required(o, "i"),
                procedureHtml(o),
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

    private static String procedureHtml(JSONObject root) {
        JSONArray stages = root.optJSONArray("p");
        if (stages == null || stages.length() == 0) {
            String fallback = root.optString("p", "").trim();
            if (fallback.isEmpty()) throw new IllegalArgumentException("حقل ناقص من الذكاء: p");
            return "<div dir=\"rtl\"><p>" + html(fallback).replace("\n", "<br>") + "</p></div>";
        }

        StringBuilder out = new StringBuilder(5000);
        out.append("<div dir=\"rtl\">");
        int written = 0;
        Set<Integer> usedPages = new LinkedHashSet<>();

        for (int i = 0; i < stages.length() && written < 6; i++) {
            JSONObject stage = stages.optJSONObject(i);
            if (stage == null) continue;

            String t = stage.optString("t", "").trim();
            String teacher = stage.optString("teacher", "").trim();
            String student = stage.optString("student", "").trim();
            String source = stage.optString("source", "").trim();
            String check = stage.optString("check", "").trim();
            if (t.isEmpty() && teacher.isEmpty() && student.isEmpty() && source.isEmpty() && check.isEmpty()) continue;

            written++;
            out.append("<div style=\"margin:0 0 18px 0;\">");
            out.append("<p><strong>").append(written).append(". ")
                    .append(html(t.isEmpty() ? "مرحلة التعلم" : t)).append("</strong></p>");
            appendProcedureRow(out, "دور المعلم", teacher);
            appendProcedureRow(out, "دور الطالب", student);
            appendProcedureRow(out, "من المادة العلمية", source);
            appendProcedureRow(out, "تحقق سريع", check);

            JSONArray pages = stage.optJSONArray("pg");
            if (pages != null) {
                for (int p = 0; p < pages.length() && usedPages.size() < 3; p++) {
                    int page = pages.optInt(p, 0);
                    if (page <= 0 || usedPages.contains(page)) continue;
                    usedPages.add(page);
                    out.append("<div data-khutwa-page=\"").append(page)
                            .append("\" style=\"margin:8px 0;\"></div>");
                }
            }
            out.append("</div>");
            if (written < 6) out.append("<hr>");
        }

        if (written == 0) throw new IllegalArgumentException("سير الدرس فارغ");
        out.append("</div>");
        return out.toString();
    }

    private static void appendProcedureRow(StringBuilder out, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        out.append("<p><strong>").append(html(label)).append(":</strong> ")
                .append(html(value.trim()).replace("\n", "<br>")).append("</p>");
    }

    private static String html(String raw) {
        if (raw == null) return "";
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
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
