package com.khutwa.noorhelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class GenericSchedulePlanner {
    private static final SimpleDateFormat ISO = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private GenericSchedulePlanner() {}

    static List<String> datesFor(CurriculumLesson target, List<CurriculumLesson> all) {
        List<String> out = new ArrayList<>();
        if (target == null) return out;

        if (target.periodStart.isEmpty() && target.periodEnd.isEmpty()) return out;
        if (target.periodEnd.isEmpty()) {
            out.add(target.periodStart);
            return out;
        }

        List<CurriculumLesson> sameBlock = new ArrayList<>();
        int offset = 0;
        int total = 0;
        boolean beforeTarget = true;

        if (all != null) {
            for (CurriculumLesson lesson : all) {
                if (samePlanningBlock(lesson, target)) {
                    sameBlock.add(lesson);
                    if (lesson.code.equals(target.code)) {
                        beforeTarget = false;
                    } else if (beforeTarget) {
                        offset += Math.max(1, lesson.periods);
                    }
                    total += Math.max(1, lesson.periods);
                }
            }
        }

        List<String> days = workingDays(target.periodStart, target.periodEnd);
        Set<String> dates = new LinkedHashSet<>();

        if (days.isEmpty()) {
            if (!target.periodStart.isEmpty()) dates.add(target.periodStart);
            return new ArrayList<>(dates);
        }

        if (total <= 0) {
            int needed = Math.max(1, target.periods);
            for (int i = 0; i < needed && i < days.size(); i++) dates.add(days.get(i));
            return new ArrayList<>(dates);
        }

        for (int session = 0; session < Math.max(1, target.periods); session++) {
            int absolute = offset + session;
            int dayIndex = (int)Math.floor((absolute * days.size()) / (double) total);
            dayIndex = Math.max(0, Math.min(days.size() - 1, dayIndex));
            dates.add(days.get(dayIndex));
        }

        if (dates.isEmpty() && !target.periodStart.isEmpty()) dates.add(target.periodStart);
        return new ArrayList<>(dates);
    }

    private static boolean samePlanningBlock(CurriculumLesson a, CurriculumLesson b) {
        if (a == null || b == null) return false;
        return safe(a.unit).equals(safe(b.unit))
                && safe(a.periodStart).equals(safe(b.periodStart))
                && safe(a.periodEnd).equals(safe(b.periodEnd));
    }

    static List<String> candidateDates(CurriculumLesson lesson) {
        if (lesson == null) return new ArrayList<>();
        return workingDays(lesson.periodStart, lesson.periodEnd);
    }

    private static List<String> workingDays(String start, String end) {
        List<String> out = new ArrayList<>();
        try {
            Date s = ISO.parse(start);
            Date e = ISO.parse(end);
            if (s == null || e == null) return out;
            Calendar c = Calendar.getInstance();
            c.setTime(s);
            Calendar last = Calendar.getInstance();
            last.setTime(e);

            while (!c.after(last)) {
                int dow = c.get(Calendar.DAY_OF_WEEK);
                // Noor is used in Oman; school publishing normally follows Sunday–Thursday.
                if (dow != Calendar.FRIDAY && dow != Calendar.SATURDAY) {
                    out.add(ISO.format(c.getTime()));
                }
                c.add(Calendar.DAY_OF_MONTH, 1);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
