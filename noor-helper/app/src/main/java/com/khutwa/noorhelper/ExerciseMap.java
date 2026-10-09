package com.khutwa.noorhelper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ExerciseMap {
    enum Side { FULL, RIGHT, LEFT }

    static final class Segment {
        final int pdfPage;
        final Side side;
        Segment(int pdfPage, Side side) {
            this.pdfPage = pdfPage;
            this.side = side;
        }
    }

    static final class Lesson {
        final String code;
        final String title;
        final Segment[] segments;
        Lesson(String code, String title, Segment... segments) {
            this.code = code;
            this.title = title;
            this.segments = segments;
        }
    }

    private static final Map<String, Lesson> LESSONS = new LinkedHashMap<>();

    static {
        add("1-1","الأنواع المختلفة من الأعداد", f(5), f(6));
        add("1-2","الأعداد الأولية", f(7), f(8), f(9));
        add("1-3","القوى والجذور", f(10), f(11));
        add("1-4","الأعداد الموجهة", f(12), f(13));
        add("1-5","ترتيب العمليات الحسابية", f(14), f(15));

        add("2-1","الكسور المكافئة", f(20));
        add("2-2","العمليات على الكسور", f(21), f(22));
        add("2-3","النسب المئوية", f(23));
        add("2-4","الصيغة العلمية", f(24), r(25));
        add("2-5","استخدام الآلة الحاسبة والصيغة العلمية", l(25));
        add("2-6","الأعداد النسبية", f(26), f(27));

        add("3-1","استخدام الحروف (المتغيرات) لتمثيل القيم", f(32));
        add("3-2","التعويض", f(33));
        add("3-3","تبسيط العبارات الجبرية", f(34), f(35));
        add("3-4","التعامل مع الأقواس", f(36));
        add("3-5","الأسس", f(37), f(38));

        add("4-1","الدائرة", f(44));
        add("4-2","الزوايا", f(45), f(46), f(47));
        add("4-3","الإنشاءات الهندسية", f(48), f(49));
        add("4-4","المثلثات", f(50));
        add("4-5","الأشكال الرباعية", f(51), f(52));
        add("4-6","مضلعات أخرى", f(53));

        add("5-1","تقريب الأعداد", f(58));
        add("5-2","التقدير", f(59));
        add("5-3","الحدود العليا والحدود الدنيا", f(60), f(61));

        add("6-1","فك الأقواس", f(66));
        add("6-2","تحليل العبارات الجبرية إلى عوامل", f(67));
        add("6-3","استخدام الصيغ وإعادة تنظيمها", f(68), f(69));
        add("6-4","حل المعادلات", f(70));
        add("6-5","المعادلات الخطية الآنية", f(71), f(72), f(73));
        add("6-6","كتابة المعادلات وحلها", f(74), f(75));
        add("6-7","المتباينات الخطية", f(76));

        add("7-1","رسم المستقيمات", f(82), f(83), f(84), f(85), f(86));
        add("7-2","القطعة المستقيمة", f(87));

        add("8-1","التماثل في الأشكال ثنائية الأبعاد", f(92));
        add("8-2","التماثل في الأشكال ثلاثية الأبعاد", f(93), f(94));
        add("8-3","التحويلات الهندسية", f(95), f(96), f(97), f(98));
        add("8-4","تركيب التحويلات الهندسية", f(99));

        add("9-1","المتتاليات", f(104), f(105));
        add("9-2","المجموعات", f(106), f(107), f(108), f(109));
    }

    private ExerciseMap() {}

    static Lesson find(String noorTitle) {
        String text = arabicDigitsToLatin(noorTitle == null ? "" : noorTitle);
        Matcher m = Pattern.compile("(\\d+)\\s*[-–]\\s*(\\d+)").matcher(text);
        String c = compact(text);
        if (m.find()) {
            // Noor titles use lesson-unit; our PDF map uses unit-lesson.
            Lesson lesson = LESSONS.get(m.group(2) + "-" + m.group(1));
            if (lesson != null) return lesson;
        }


        Lesson best = null;
        int bestLen = 0;
        for (Lesson lesson : LESSONS.values()) {
            String t = compact(lesson.title);
            if (!t.isEmpty() && (c.contains(t) || t.contains(c)) && t.length() > bestLen) {
                best = lesson;
                bestLen = t.length();
            }
        }
        return best;
    }

    static int lessonCount() {
        return LESSONS.size();
    }

    private static void add(String code, String title, Segment... segments) {
        LESSONS.put(code, new Lesson(code, title, segments));
    }

    private static Segment f(int page) { return new Segment(page, Side.FULL); }
    private static Segment r(int page) { return new Segment(page, Side.RIGHT); }
    private static Segment l(int page) { return new Segment(page, Side.LEFT); }

    private static String compact(String s) {
        if (s == null) return "";
        s = arabicDigitsToLatin(s.toLowerCase(Locale.ROOT));
        s = s.replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "");
        s = s.replace('أ','ا').replace('إ','ا').replace('آ','ا').replace('ى','ي');
        return s.replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static String arabicDigitsToLatin(String s) {
        String ar = "٠١٢٣٤٥٦٧٨٩";
        StringBuilder out = new StringBuilder(s == null ? 0 : s.length());
        if (s == null) return "";
        for (int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            int idx=ar.indexOf(c);
            out.append(idx>=0 ? (char)('0'+idx) : c);
        }
        return out.toString();
    }
}
