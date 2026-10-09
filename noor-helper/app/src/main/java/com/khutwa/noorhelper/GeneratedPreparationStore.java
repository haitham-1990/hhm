package com.khutwa.noorhelper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class GeneratedPreparationStore {
    static final class Entry {
        final String code;
        final String title;
        final LessonPreparation preparation;
        final String level;
        final List<String> objectives;
        final List<String> strategies;
        final List<String> resources;
        final int periods;
        final String startDate;
        final String endDate;
        final List<Integer> materialPages;
        final String sourceSummary;

        Entry(String code, String title, LessonPreparation preparation, String level,
              List<String> objectives, List<String> strategies, List<String> resources,
              int periods, String startDate, String endDate,
              List<Integer> materialPages, String sourceSummary) {
            this.code = code;
            this.title = title;
            this.preparation = preparation;
            this.level = level;
            this.objectives = objectives;
            this.strategies = strategies;
            this.resources = resources;
            this.periods = periods;
            this.startDate = startDate;
            this.endDate = endDate;
            this.materialPages = materialPages;
            this.sourceSummary = sourceSummary;
        }
    }

    private static final String PREFS = "noor_generated_db_v1";
    private static final String KEY_PLAN = "_source_plan";
    private static final String KEY_MATERIAL = "_source_material";
    private static final String PREFIX = "lesson_flow_v2_";

    private final SharedPreferences prefs;

    GeneratedPreparationStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean bindSources(String planUri, String materialUri) {
        String p = prefs.getString(KEY_PLAN, "");
        String m = prefs.getString(KEY_MATERIAL, "");
        String np = planUri == null ? "" : planUri;
        String nm = materialUri == null ? "" : materialUri;
        boolean changed = !p.equals(np) || !m.equals(nm);
        if (changed) {
            prefs.edit().clear().putString(KEY_PLAN, np).putString(KEY_MATERIAL, nm).apply();
        }
        return changed;
    }

    boolean has(String code) {
        return prefs.contains(PREFIX + safe(code));
    }

    void save(String code, String title, AiPreparationClient.SourceResult source,
              List<Integer> materialPages, String sourceSummary) throws Exception {
        JSONObject o = new JSONObject();
        o.put("code", code);
        o.put("title", title);

        JSONObject p = new JSONObject();
        p.put("title", source.preparation.title);
        p.put("concepts", source.preparation.concepts);
        p.put("intro", source.preparation.intro);
        p.put("procedures", source.preparation.procedures);
        p.put("formative", source.preparation.formative);
        p.put("summative", source.preparation.summative);
        p.put("weekly", source.preparation.weeklyNote);
        o.put("prep", p);

        o.put("level", source.level);
        o.put("objectives", strings(source.objectives));
        o.put("strategies", strings(source.strategies));
        o.put("resources", strings(source.resources));
        o.put("periods", source.periods);
        o.put("start", source.startDate);
        o.put("end", source.endDate);
        JSONArray pages = new JSONArray();
        if (materialPages != null) for (Integer page : materialPages) pages.put(page == null ? 0 : page);
        o.put("pages", pages);
        o.put("sourceSummary", sourceSummary == null ? "" : sourceSummary);

        prefs.edit().putString(PREFIX + safe(code), o.toString()).apply();
    }

    Entry get(String code) {
        String raw = prefs.getString(PREFIX + safe(code), null);
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(raw);
            JSONObject p = o.getJSONObject("prep");
            LessonPreparation prep = new LessonPreparation(
                    p.optString("title", o.optString("title", "")),
                    p.optString("concepts", ""),
                    p.optString("intro", ""),
                    p.optString("procedures", ""),
                    p.optString("formative", ""),
                    p.optString("summative", ""),
                    p.optString("weekly", "")
            );
            return new Entry(
                    o.optString("code", code),
                    o.optString("title", ""),
                    prep,
                    o.optString("level", "الفهم"),
                    stringList(o.optJSONArray("objectives")),
                    stringList(o.optJSONArray("strategies")),
                    stringList(o.optJSONArray("resources")),
                    o.optInt("periods", 0),
                    o.optString("start", ""),
                    o.optString("end", ""),
                    intList(o.optJSONArray("pages")),
                    o.optString("sourceSummary", "")
            );
        } catch (Exception e) {
            return null;
        }
    }

    int count(List<CurriculumLesson> lessons) {
        int n = 0;
        if (lessons == null) return 0;
        for (CurriculumLesson lesson : lessons) if (has(lesson.code)) n++;
        return n;
    }

    void clear() {
        prefs.edit().clear().apply();
    }

    private static JSONArray strings(List<String> values) {
        JSONArray a = new JSONArray();
        if (values != null) for (String v : values) if (v != null && !v.trim().isEmpty()) a.put(v.trim());
        return a;
    }

    private static List<String> stringList(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            String v = a.optString(i, "").trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    private static List<Integer> intList(JSONArray a) {
        List<Integer> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            int v = a.optInt(i, 0);
            if (v > 0) out.add(v);
        }
        return out;
    }

    private static String safe(String code) {
        return (code == null ? "" : code).replaceAll("[^0-9A-Za-z_-]", "_");
    }
}
