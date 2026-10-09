package com.khutwa.noorhelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class Grade9SchedulePlanner {
    private static final SimpleDateFormat ISO = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private Grade9SchedulePlanner() {}

    static List<String> datesFor(Grade9Curriculum.Lesson target) {
        return datesFor(target, Grade9Curriculum.allLessons());
    }

    static List<String> datesFor(Grade9Curriculum.Lesson target, List<Grade9Curriculum.Lesson> all) {
        if (all == null) all = new ArrayList<>();
        List<Grade9Curriculum.Lesson> unit = new ArrayList<>();
        int offset = 0;
        int total = 0;

        boolean beforeTarget = true;
        for (Grade9Curriculum.Lesson l : all) {
            if (sameUnit(l, target)) {
                unit.add(l);
                if (l.code.equals(target.code)) {
                    beforeTarget = false;
                } else if (beforeTarget) {
                    offset += l.periods;
                }
                total += l.periods;
            }
        }

        List<String> schoolDays = schoolDays(target.periodStart, target.periodEnd);
        Set<String> dates = new LinkedHashSet<>();
        if (schoolDays.isEmpty() || total <= 0) {
            dates.add(target.periodStart);
            return new ArrayList<>(dates);
        }

        for (int session = 0; session < Math.max(1, target.periods); session++) {
            int absoluteSession = offset + session;
            int dayIndex = (int) Math.floor((absoluteSession * schoolDays.size()) / (double) total);
            dayIndex = Math.max(0, Math.min(schoolDays.size() - 1, dayIndex));
            dates.add(schoolDays.get(dayIndex));
        }

        if (dates.isEmpty()) dates.add(target.periodStart);
        return new ArrayList<>(dates);
    }

    private static boolean sameUnit(Grade9Curriculum.Lesson a, Grade9Curriculum.Lesson b) {
        return a.unit.equals(b.unit)
                && a.periodStart.equals(b.periodStart)
                && a.periodEnd.equals(b.periodEnd);
    }

    private static List<String> schoolDays(String start, String end) {
        List<String> out = new ArrayList<>();
        try {
            Date s = ISO.parse(start);
            Date e = ISO.parse(end);
            Calendar c = Calendar.getInstance();
            c.setTime(s);
            Calendar last = Calendar.getInstance();
            last.setTime(e);
            while (!c.after(last)) {
                int dow = c.get(Calendar.DAY_OF_WEEK);
                // Oman school week is Sunday–Thursday; Friday and Saturday are skipped.
                if (dow != Calendar.FRIDAY && dow != Calendar.SATURDAY) {
                    out.add(ISO.format(c.getTime()));
                }
                c.add(Calendar.DAY_OF_MONTH, 1);
            }
        } catch (Exception ignored) {}
        return out;
    }
}
