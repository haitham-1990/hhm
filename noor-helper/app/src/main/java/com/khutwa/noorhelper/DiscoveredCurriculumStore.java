package com.khutwa.noorhelper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class DiscoveredCurriculumStore {
    static final class Entry {
        final CurriculumLesson lesson;
        final int materialStartPage;
        final int materialEndPage;

        Entry(CurriculumLesson lesson, int materialStartPage, int materialEndPage) {
            this.lesson = lesson;
            this.materialStartPage = materialStartPage;
            this.materialEndPage = materialEndPage;
        }
    }

    private static final String PREFS = "noor_discovered_curriculum_v1";
    private static final String KEY_SOURCE_PLAN = "source_plan";
    private static final String KEY_SOURCE_MATERIAL = "source_material";
    private static final String KEY_CATALOG = "catalog";

    private final SharedPreferences prefs;

    DiscoveredCurriculumStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean bindSources(String planUri, String materialUri) {
        String p = prefs.getString(KEY_SOURCE_PLAN, "");
        String m = prefs.getString(KEY_SOURCE_MATERIAL, "");
        String np = planUri == null ? "" : planUri;
        String nm = materialUri == null ? "" : materialUri;
        boolean changed = !p.equals(np) || !m.equals(nm);
        if (changed) {
            prefs.edit().clear()
                    .putString(KEY_SOURCE_PLAN, np)
                    .putString(KEY_SOURCE_MATERIAL, nm)
                    .apply();
        }
        return changed;
    }

    boolean hasCatalog() {
        String raw = prefs.getString(KEY_CATALOG, "");
        return raw != null && !raw.trim().isEmpty();
    }

    void save(List<Entry> entries) throws Exception {
        JSONArray a = new JSONArray();
        if (entries != null) {
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                CurriculumLesson l = e.lesson;
                o.put("code", l.code);
                o.put("title", l.title);
                o.put("unit", l.unit);
                o.put("semester", l.semester);
                o.put("periods", l.periods);
                o.put("start", l.periodStart);
                o.put("end", l.periodEnd);
                o.put("level", l.level);
                o.put("objectives", strings(l.objectives));
                o.put("strategies", strings(l.strategies));
                o.put("resources", strings(l.resources));
                o.put("materialStartPage", e.materialStartPage);
                o.put("materialEndPage", e.materialEndPage);
                a.put(o);
            }
        }
        prefs.edit().putString(KEY_CATALOG, a.toString()).apply();
    }

    List<Entry> load() {
        List<Entry> out = new ArrayList<>();
        String raw = prefs.getString(KEY_CATALOG, "");
        if (raw == null || raw.trim().isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                CurriculumLesson l = new CurriculumLesson(
                        o.optString("code", ""),
                        o.optString("title", ""),
                        o.optString("unit", ""),
                        o.optString("semester", ""),
                        Math.max(1, o.optInt("periods", 1)),
                        o.optString("start", ""),
                        o.optString("end", ""),
                        o.optString("level", "الفهم"),
                        stringList(o.optJSONArray("objectives")),
                        stringList(o.optJSONArray("strategies")),
                        stringList(o.optJSONArray("resources"))
                );
                if (!l.code.trim().isEmpty() && !l.title.trim().isEmpty()) {
                    out.add(new Entry(
                            l,
                            Math.max(0, o.optInt("materialStartPage", 0)),
                            Math.max(0, o.optInt("materialEndPage", 0))
                    ));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    Entry find(String code) {
        for (Entry e : load()) {
            if (e.lesson.code.equals(code)) return e;
        }
        return null;
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
}
