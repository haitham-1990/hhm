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

final class PdfLessonContext {
    static final class Result {
        final String planContext;
        final String materialContext;
        final List<Integer> planPages;
        final List<Integer> materialPages;

        Result(String planContext, String materialContext,
               List<Integer> planPages, List<Integer> materialPages) {
            this.planContext = planContext;
            this.materialContext = materialContext;
            this.planPages = planPages;
            this.materialPages = materialPages;
        }

        String summary() {
            return "الخطة: " + pagesText(planPages) + " | المادة: " + pagesText(materialPages);
        }

        private static String pagesText(List<Integer> pages) {
            if (pages == null || pages.isEmpty()) return "لم تتم المطابقة";
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < pages.size(); i++) {
                if (i > 0) b.append("، ");
                b.append("ص").append(pages.get(i));
            }
            return b.toString();
        }
    }

    private static final int PLAN_MAX_CHARS = 7000;
    private static final int MATERIAL_MAX_CHARS = 14000;
    private static final int MATERIAL_TOP_PAGES = 4;

    private PdfLessonContext() {}

    static Result extract(Context context, Uri planUri, Uri materialUri, String lessonTitle) throws Exception {
        if (planUri == null) throw new IllegalArgumentException("لم يتم اختيار ملف الخطة الدراسية.");
        if (materialUri == null) throw new IllegalArgumentException("لم يتم اختيار ملف المادة العلمية.");

        LessonKey key = LessonKey.from(lessonTitle);
        DocSelection plan = select(context, planUri, key, true);
        DocSelection material = select(context, materialUri, key, false);

        if (plan.text.trim().isEmpty()) {
            throw new IllegalStateException("لم أجد نصًا مرتبطًا بالدرس داخل ملف الخطة.");
        }
        if (material.text.trim().isEmpty()) {
            throw new IllegalStateException("لم أجد نصًا مرتبطًا بالدرس داخل ملف المادة العلمية.");
        }

        return new Result(plan.text, material.text, plan.pages, material.pages);
    }

    private static DocSelection select(Context context, Uri uri, LessonKey key, boolean plan) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(in)) {
            if (doc.getNumberOfPages() <= 0) return new DocSelection("", new ArrayList<>());

            List<ScoredPage> scored = new ArrayList<>();
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                String text = pageText(doc, i);
                int score = score(text, key, plan);
                scored.add(new ScoredPage(i, score, text));
            }

            Collections.sort(scored, new Comparator<ScoredPage>() {
                @Override
                public int compare(ScoredPage a, ScoredPage b) {
                    if (a.score != b.score) return Integer.compare(b.score, a.score);
                    return Integer.compare(a.index, b.index);
                }
            });

            Set<Integer> selected = new LinkedHashSet<>();
            if (plan) {
                if (!scored.isEmpty() && scored.get(0).score > 0) {
                    int best = scored.get(0).index;
                    if (best > 0) selected.add(best - 1);
                    selected.add(best);
                    if (best + 1 < doc.getNumberOfPages()) selected.add(best + 1);
                }
            } else {
                int count = 0;
                for (ScoredPage p : scored) {
                    if (p.score <= 0) break;
                    selected.add(p.index);
                    count++;
                    if (count >= MATERIAL_TOP_PAGES) break;
                }
                if (!selected.isEmpty()) {
                    int first = selected.iterator().next();
                    if (first > 0) selected.add(first - 1);
                    if (first + 1 < doc.getNumberOfPages()) selected.add(first + 1);
                }
            }

            if (selected.isEmpty()) return new DocSelection("", new ArrayList<>());

            List<Integer> ordered = new ArrayList<>(selected);
            Collections.sort(ordered);
            StringBuilder out = new StringBuilder();
            List<Integer> humanPages = new ArrayList<>();
            int max = plan ? PLAN_MAX_CHARS : MATERIAL_MAX_CHARS;
            for (int pageIndex : ordered) {
                String text = null;
                for (ScoredPage p : scored) {
                    if (p.index == pageIndex) {
                        text = p.text;
                        break;
                    }
                }
                if (text == null) text = pageText(doc, pageIndex);
                text = clean(text);
                if (text.isEmpty()) continue;
                String block = "\n--- صفحة PDF " + (pageIndex + 1) + " ---\n" + text;
                if (out.length() + block.length() > max) {
                    int remain = max - out.length();
                    if (remain > 250) out.append(block, 0, Math.min(block.length(), remain));
                    break;
                }
                out.append(block);
                humanPages.add(pageIndex + 1);
            }
            return new DocSelection(out.toString().trim(), humanPages);
        }
    }

    private static String pageText(PDDocument doc, int pageIndex) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.setSortByPosition(true);
        return stripper.getText(doc);
    }

    private static int score(String raw, LessonKey key, boolean plan) {
        String text = normalize(raw);
        if (text.isEmpty()) return 0;
        int score = 0;

        if (!key.code.isEmpty()) {
            String compact = compactCode(text);
            String target = compactCode(key.code);
            if (!target.isEmpty() && compact.contains(target)) score += plan ? 180 : 100;
        }

        if (!key.title.isEmpty()) {
            String nt = normalize(key.title);
            if (text.contains(nt)) score += plan ? 140 : 180;
        }

        for (String word : key.words) {
            int occurrences = occurrences(text, word);
            score += Math.min(occurrences, 6) * (plan ? 18 : 24);
        }
        return score;
    }

    private static int occurrences(String text, String word) {
        if (word == null || word.isEmpty()) return 0;
        int n = 0, at = 0;
        while ((at = text.indexOf(word, at)) >= 0) {
            n++;
            at += Math.max(1, word.length());
        }
        return n;
    }

    private static String clean(String s) {
        if (s == null) return "";
        String v = s.replace('\u0000', ' ').replaceAll("[\\t ]+", " ")
                .replaceAll("\\n{3,}", "\n\n").trim();
        return v;
    }

    private static String normalize(String s) {
        String v = s == null ? "" : s;
        v = arabicDigitsToLatin(v);
        v = v.replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "")
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
                .replace('ى', 'ي')
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return v;
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

    private static final class DocSelection {
        final String text;
        final List<Integer> pages;
        DocSelection(String text, List<Integer> pages) {
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

            String nt = normalize(title);
            String[] parts = nt.split(" ");
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
