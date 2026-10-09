package com.khutwa.noorhelper;

import java.util.ArrayList;
import java.util.List;

final class CurriculumLesson {
    final String code;
    final String title;
    final String unit;
    final String semester;
    final int periods;
    final String periodStart;
    final String periodEnd;
    final String level;
    final List<String> objectives;
    final List<String> strategies;
    final List<String> resources;

    CurriculumLesson(String code, String title, String unit, String semester,
                     int periods, String periodStart, String periodEnd,
                     String level, List<String> objectives,
                     List<String> strategies, List<String> resources) {
        this.code = code == null ? "" : code.trim();
        this.title = title == null ? "" : title.trim();
        this.unit = unit == null ? "" : unit.trim();
        this.semester = semester == null ? "" : semester.trim();
        this.periods = Math.max(1, periods);
        this.periodStart = periodStart == null ? "" : periodStart.trim();
        this.periodEnd = periodEnd == null ? "" : periodEnd.trim();
        this.level = level == null || level.trim().isEmpty() ? "الفهم" : level.trim();
        this.objectives = objectives == null ? new ArrayList<>() : objectives;
        this.strategies = strategies == null ? new ArrayList<>() : strategies;
        this.resources = resources == null ? new ArrayList<>() : resources;
    }

    String noorCode() {
        return code.startsWith("AUTO-") ? "" : code;
    }

    String displayName() {
        String visibleCode = noorCode();
        return visibleCode.isEmpty() ? title : visibleCode + " — " + title;
    }

    String planSummary() {
        StringBuilder b = new StringBuilder();
        if (!semester.isEmpty()) b.append(semester);
        if (!unit.isEmpty()) {
            if (b.length() > 0) b.append("\n");
            b.append(unit);
        }
        b.append("\nعدد الحصص: ").append(periods);
        if (!periodStart.isEmpty() || !periodEnd.isEmpty()) {
            b.append("\nالفترة: ").append(periodStart).append(" إلى ").append(periodEnd);
        }
        return b.toString();
    }
}
