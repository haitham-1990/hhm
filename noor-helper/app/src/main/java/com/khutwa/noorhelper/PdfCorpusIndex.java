package com.khutwa.noorhelper;

import android.content.Context;
import android.net.Uri;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PdfCorpusIndex {
    private static final int PLAN_MAX_CHARS = 7000;
    private static final int MATERIAL_MAX_CHARS = 14000;
    private static final int MATERIAL_TOP_PAGES = 4;

    private final List<String> planPages;
    private final List<String> materialPages;

    private PdfCorpusIndex(List<String> planPages, List<String> materialPages) {
        this.planPages = planPages;
        this.materialPages = materialPages;
    }

    static PdfCorpusIndex build(Context context, Uri planUri, Uri materialUri) throws Exception {
        if (planUri == null) throw new IllegalArgumentException("لم يتم اختيار ملف الخطة الدراسية.");
        if (materialUri == null) throw new IllegalArgumentException("لم يتم اختيار ملف المادة العلمية.");
        return new PdfCorpusIndex(readAllPages(context, planUri), readAllPages(context, materialUri));
    }

    PdfLessonContext.Result forLesson(String lessonTitle) {
        LessonKey key = LessonKey.from(lessonTitle);
        Selection plan = select(planPages, key, true);
        Selection material = select(materialPages, key, false);

        if (material.text.trim().isEmpty()) {
            ExerciseMap.Lesson mapped = ExerciseMap.find(lessonTitle);
            if (mapped != null && mapped.segments != null) {
                List<Integer> exactPages = new ArrayList<>();
                for (ExerciseMap.Segment segment : mapped.segments) {
                    if (segment != null && segment.pdfPage > 0 && !exactPages.contains(segment.pdfPage)) {
                        exactPages.add(segment.pdfPage);
                    }
                }
                material = selectExact(materialPages, exactPages, MATERIAL_MAX_CHARS);
            }
        }

        return new PdfLessonContext.Result(
                plan.text, material.text, plan.pages, material.pages
        );
    }

    private static List<String> readAllPages(Context context, Uri uri) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(in)) {
            List<String> out = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                out.add(clean(stripper.getText(doc)));
            }
            return out;
        }
    }

    private static Selection selectExact(List<String> pages, List<Integer> humanPages, int maxChars) {
        StringBuilder text = new StringBuilder();
        List<Integer> kept = new ArrayList<>();
        if (pages == null || humanPages == null) return new Selection("", kept);
        for (Integer humanPage : humanPages) {
            if (humanPage == null) continue;
            int index = humanPage - 1;
            if (index < 0 || index >= pages.size()) continue;
            String page = clean(pages.get(index));
            if (page.isEmpty()) continue;
            String block = "\n--- صفحة PDF " + humanPage + " ---\n" + page;
            if (text.length() + block.length() > maxChars) {
                int remain = maxChars - text.length();
                if (remain > 250) text.append(block, 0, Math.min(remain, block.length()));
                break;
            }
            text.append(block);
            kept.add(humanPage);
        }
        return new Selection(text.toString().trim(), kept);
    }

    private static Selection select(List<String> pages, LessonKey key, boolean plan) {
        if (pages == null || pages.isEmpty()) return new Selection("", new ArrayList<>());

        List<ScoredPage> scored = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            String text = pages.get(i);
            scored.add(new ScoredPage(i, score(text, key, plan), text));
        }

        Collections.sort(scored, new Comparator<ScoredPage>() {
            @Override public int compare(ScoredPage a, ScoredPage b) {
                if (a.score != b.score) return Integer.compare(b.score, a.score);
                return Integer.compare(a.index, b.index);
            }
        });

        Set<Integer> chosen = new LinkedHashSet<>();
        if (plan) {
            if (!scored.isEmpty() && scored.get(0).score > 0) {
                int best = scored.get(0).index;
                if (best > 0) chosen.add(best - 1);
                chosen.add(best);
                if (best + 1 < pages.size()) chosen.add(best + 1);
            }
        } else {
            int count = 0;
            for (ScoredPage p : scored) {
                if (p.score <= 0) break;
                chosen.add(p.index);
                if (++count >= MATERIAL_TOP_PAGES) break;
            }
            if (!chosen.isEmpty()) {
                int first = chosen.iterator().next();
                if (first > 0) chosen.add(first - 1);
                if (first + 1 < pages.size()) chosen.add(first + 1);
            }
        }

        if (chosen.isEmpty()) return new Selection("", new ArrayList<>());

        List<Integer> ordered = new ArrayList<>(chosen);
        Collections.sort(ordered);
        int max = plan ? PLAN_MAX_CHARS : MATERIAL_MAX_CHARS;
        StringBuilder text = new StringBuilder();
        List<Integer> human = new ArrayList<>();

        for (Integer pageIndex : ordered) {
            if (pageIndex == null || pageIndex < 0 || pageIndex >= pages.size()) continue;
            String page = clean(pages.get(pageIndex));
            if (page.isEmpty()) continue;
            String block = "\n--- صفحة PDF " + (pageIndex + 1) + " ---\n" + page;
            if (text.length() + block.length() > max) {
                int remain = max - text.length();
                if (remain > 250) text.append(block, 0, Math.min(remain, block.length()));
                break;
            }
            text.append(block);
            human.add(pageIndex + 1);
        }

        return new Selection(text.toString().trim(), human);
    }

    private static int score(String raw, LessonKey key, boolean plan) {
        String text = normalize(raw);
        if (text.isEmpty()) return 0;
        int score = 0;

        if (!key.code.isEmpty()) {
            String target = compactCode(key.code);
            String whole = compactCode(raw);
            if (!target.isEmpty() && whole.contains(target)) score += plan ? 180 : 100;
        }

        if (!key.title.isEmpty()) {
            String nt = normalize(key.title);
            if (!nt.isEmpty() && text.contains(nt)) score += plan ? 140 : 180;
        }

        for (String word : key.words) {
            int occurrences = occurrences(text, word);
            score += Math.min(occurrences, 6) * (plan ? 18 : 24);
        }
        return score;
    }

    private static int occurrences(String text, String word) {
        int n = 0, at = 0;
        if (word == null || word.isEmpty()) return 0;
        while ((at = text.indexOf(word, at)) >= 0) {
            n++;
            at += Math.max(1, word.length());
        }
        return n;
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('\u0000', ' ')
                .replaceAll("[\\t ]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static String normalize(String s) {
        String v = arabicDigitsToLatin(s == null ? "" : s);
        return v.replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "")
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي')
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String compactCode(String s) {
        String v = arabicDigitsToLatin(s == null ? "" : s);
        Matcher m = Pattern.compile("([0-9]+)\\s*[-–]\\s*([0-9]+)").matcher(v);
        StringBuilder b = new StringBuilder(v.length());
        while (m.find()) {
            m.appendReplacement(b, Matcher.quoteReplacement(m.group(1) + "-" + m.group(2)));
        }
        m.appendTail(b);
        return b.toString().replaceAll("\\s+", "");
    }

    private static String arabicDigitsToLatin(String s) {
        String ar = "٠١٢٣٤٥٦٧٨٩";
        StringBuilder out = new StringBuilder(s == null ? 0 : s.length());
        if (s == null) return "";
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int idx = ar.indexOf(c);
            out.append(idx >= 0 ? (char) ('0' + idx) : c);
        }
        return out.toString();
    }

    private static final class Selection {
        final String text;
        final List<Integer> pages;
        Selection(String text, List<Integer> pages) {
            this.text = text;
            this.pages = pages;
        }
    }

    private static final class ScoredPage {
        final int index;
        final int score;
        final String text;
        ScoredPage(int index, int score, String text) {
            this.index = index;
            this.score = score;
            this.text = text == null ? "" : text;
        }
    }

    private static final class LessonKey {
        final String code;
        final String title;
        final List<String> words;

        LessonKey(String code, String title, List<String> words) {
            this.code = code;
            this.title = title;
            this.words = words;
        }

        static LessonKey from(String lessonTitle) {
            String raw = arabicDigitsToLatin(lessonTitle == null ? "" : lessonTitle);
            Matcher m = Pattern.compile("([0-9]+)\\s*[-–]\\s*([0-9]+)").matcher(raw);
            String code = "";
            if (m.find()) code = m.group(1) + "-" + m.group(2);

            String title = raw.replaceFirst("^[\\s0-9٠-٩]+[-–][\\s0-9٠-٩]+", "").trim();
            if (title.isEmpty()) title = raw.trim();

            String[] parts = normalize(title).split(" ");
            List<String> words = new ArrayList<>();
            for (String p : parts) {
                if (p.length() < 3) continue;
                if ("درس".equals(p) || "الوحدة".equals(p) || "على".equals(p)
                        || "الى".equals(p) || "في".equals(p) || "من".equals(p)) continue;
                words.add(p);
            }
            return new LessonKey(code, title, words);
        }
    }
}
