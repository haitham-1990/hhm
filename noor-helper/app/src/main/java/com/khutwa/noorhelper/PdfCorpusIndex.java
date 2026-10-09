package com.khutwa.noorhelper;

import android.content.Context;
import android.net.Uri;

import com.tom_roush.pdfbox.io.MemoryUsageSetting;
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

    String planDiscoveryOutline() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < planPages.size(); i++) {
            String page = clean(planPages.get(i));
            String snippet = compactSnippet(page, 320);
            out.append("\n[PLAN PDF ").append(i + 1).append("] ").append(snippet);
        }
        return out.toString();
    }

    String planPageWindow(int pageOneBased) {
        int index = pageOneBased - 1;
        if (index < 0 || index >= planPages.size()) return "";
        StringBuilder out = new StringBuilder();
        int from = Math.max(0, index - 1);
        int to = Math.min(planPages.size() - 1, index + 1);
        for (int i = from; i <= to; i++) {
            String page = clean(planPages.get(i));
            if (page.isEmpty()) continue;
            out.append("\n--- صفحة الخطة PDF ").append(i + 1).append(" ---\n").append(page);
        }
        return out.toString();
    }

    List<String> planPageSegments(int pageOneBased) {
        List<String> out = new ArrayList<>();
        int index = pageOneBased - 1;
        if (index < 0 || index >= planPages.size()) return out;

        String page = clean(planPages.get(index));
        if (page.isEmpty()) return out;

        String[] lines = page.split("\\n");
        final int MAX_CHARS = 5600;
        final int MAX_LINES = 34;
        final int OVERLAP_LINES = 4;

        int start = 0;
        while (start < lines.length) {
            StringBuilder part = new StringBuilder();
            int end = start;
            while (end < lines.length && end - start < MAX_LINES) {
                String line = clean(lines[end]);
                if (!line.isEmpty()) {
                    if (part.length() + line.length() + 1 > MAX_CHARS && part.length() > 0) break;
                    part.append(line).append("\n");
                }
                end++;
            }

            if (part.length() > 0) {
                out.add("--- صفحة الخطة PDF " + pageOneBased
                        + " | مقطع " + (out.size() + 1) + " ---\n" + part.toString());
            }

            if (end >= lines.length) break;
            start = Math.max(start + 1, end - OVERLAP_LINES);
        }

        if (out.isEmpty()) out.add("--- صفحة الخطة PDF " + pageOneBased + " ---\n" + page);
        return out;
    }

    int planPageCount() {
        return planPages.size();
    }

    int materialPageCount() {
        return materialPages.size();
    }

    private static String compactSnippet(String text, int max) {
        if (text == null) return "";
        String v = text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (v.length() <= max) return v;
        int head = Math.max(1, max / 2);
        int tail = Math.max(1, max - head);
        return v.substring(0, head) + " … " + v.substring(Math.max(head, v.length() - tail));
    }

    String planDiscoveryContext() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < planPages.size(); i++) {
            String page = clean(planPages.get(i));
            if (page.isEmpty()) continue;
            String block = "\n--- صفحة الخطة PDF " + (i + 1) + " ---\n" + page;
            if (out.length() + block.length() > 76000) {
                int remain = 76000 - out.length();
                if (remain > 300) out.append(block, 0, Math.min(block.length(), remain));
                break;
            }
            out.append(block);
        }
        return out.toString();
    }

    String materialDiscoveryOutline() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < materialPages.size(); i++) {
            String page = clean(materialPages.get(i));
            out.append("\n[MATERIAL PDF ").append(i + 1).append("] ")
                    .append(compactSnippet(page, 220));
        }
        return out.toString();
    }


    List<String> materialDiscoveryBatches(int maxChars) {
        List<String> batches = new ArrayList<>();
        int limit = Math.max(6000, maxChars);
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < materialPages.size(); i++) {
            String page = clean(materialPages.get(i));
            String block = "\n[MATERIAL PDF " + (i + 1) + "] " + compactSnippet(page, 260);
            if (current.length() > 0 && current.length() + block.length() > limit) {
                batches.add(current.toString());
                current.setLength(0);
            }
            current.append(block);
        }
        if (current.length() > 0) batches.add(current.toString());
        return batches;
    }

    List<DiscoveredCurriculumStore.Entry> resolveMaterialRanges(
            List<DiscoveredCurriculumStore.Entry> catalog) {
        List<DiscoveredCurriculumStore.Entry> out = new ArrayList<>();
        if (catalog == null || catalog.isEmpty() || materialPages.isEmpty()) return out;

        List<LessonKey> keys = new ArrayList<>();
        for (DiscoveredCurriculumStore.Entry e : catalog) {
            CurriculumLesson l = e.lesson;
            keys.add(LessonKey.from((l.noorCode().isEmpty() ? "" : l.noorCode() + " ") + l.title));
        }

        int[] crowd = new int[materialPages.size()];
        for (int p = 0; p < materialPages.size(); p++) {
            String page = materialPages.get(p);
            int hits = 0;
            for (LessonKey key : keys) {
                if (headingScore(page, key) >= 230) hits++;
            }
            crowd[p] = hits;
        }

        int[] starts = new int[catalog.size()];
        int previousStart = 1;

        for (int i = 0; i < catalog.size(); i++) {
            LessonKey key = keys.get(i);
            int from = Math.max(0, previousStart - 1);
            int bestPage = -1;
            int bestScore = Integer.MIN_VALUE;

            for (int p = from; p < materialPages.size(); p++) {
                String page = materialPages.get(p);
                int s = score(page, key, false) + headingScore(page, key);

                // Pages containing many different lesson headings are usually a
                // contents/index page. Penalize them instead of assuming that all
                // those lessons start there.
                if (crowd[p] >= 4) s -= 180 + (crowd[p] * 18);

                // Preserve document order without forbidding two lessons from
                // beginning on the same physical page.
                int distance = Math.max(0, (p + 1) - previousStart);
                s -= Math.min(90, distance / 2);

                if (s > bestScore) {
                    bestScore = s;
                    bestPage = p;
                }
            }

            if (bestPage >= 0 && bestScore > 80) {
                starts[i] = bestPage + 1;
                previousStart = starts[i];
            } else {
                int fallback = catalog.get(i).materialStartPage;
                starts[i] = fallback > 0 ? fallback : 0;
                if (starts[i] > 0) previousStart = starts[i];
            }
        }

        // Repair occasional backwards jumps by keeping source order monotonic.
        int floor = 1;
        for (int i = 0; i < starts.length; i++) {
            if (starts[i] <= 0) continue;
            if (starts[i] < floor) starts[i] = floor;
            floor = starts[i];
        }

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
                // Include the next lesson's start page. Image extraction will crop
                // at the next heading, so half-page boundaries are preserved.
                end = Math.max(start, nextStart);
            } else {
                // Last lesson: use the last page that still contains evidence for
                // this lesson, with one continuation page when available.
                LessonKey key = keys.get(i);
                int lastEvidence = start;
                for (int p = start - 1; p < materialPages.size(); p++) {
                    int s = score(materialPages.get(p), key, false);
                    if (s > 15) lastEvidence = p + 1;
                }
                end = Math.min(materialPages.size(), Math.max(start, lastEvidence + 1));
            }

            out.add(new DiscoveredCurriculumStore.Entry(old.lesson, start, end));
        }

        return out;
    }

    private static int headingScore(String raw, LessonKey key) {
        if (raw == null || raw.isEmpty()) return 0;
        int best = 0;
        String targetTitle = normalize(key.title);
        String targetCode = compactCode(key.code);

        String[] lines = raw.split("\\n");
        for (String line : lines) {
            String n = normalize(line);
            String cc = compactCode(line);
            int s = 0;

            if (!targetCode.isEmpty() && cc.contains(targetCode)) s += 180;
            if (!targetTitle.isEmpty() && n.contains(targetTitle)) s += 240;

            int wordHits = 0;
            for (String word : key.words) {
                if (word.length() >= 3 && n.contains(word)) wordHits++;
            }
            s += Math.min(5, wordHits) * 32;

            String trimmed = line == null ? "" : line.trim();
            if (trimmed.length() > 0 && trimmed.length() <= 120) s += 22;
            best = Math.max(best, s);
        }
        return best;
    }

    static String readRangeText(Context context, Uri uri, int startPage, int endPage) throws Exception {
        if (uri == null || startPage <= 0 || endPage < startPage) return "";
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(in, MemoryUsageSetting.setupTempFileOnly())) {
            if (doc.getNumberOfPages() <= 0) return "";
            int start = Math.max(1, startPage);
            int end = Math.min(doc.getNumberOfPages(), Math.max(start, endPage));
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            StringBuilder out = new StringBuilder();
            for (int p = start; p <= end; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String page = clean(stripper.getText(doc));
                if (page.isEmpty()) continue;
                String block = "\n--- صفحة PDF " + p + " ---\n" + page;
                if (out.length() + block.length() > MATERIAL_MAX_CHARS) {
                    int remain = MATERIAL_MAX_CHARS - out.length();
                    if (remain > 250) out.append(block, 0, Math.min(block.length(), remain));
                    break;
                }
                out.append(block);
            }
            return out.toString().trim();
        }
    }

    String materialRangeText(int startPage, int endPage) {
        if (startPage <= 0) return "";
        int start = Math.max(0, startPage - 1);
        int end = Math.min(materialPages.size() - 1, Math.max(start, endPage - 1));
        StringBuilder out = new StringBuilder();
        for (int i = start; i <= end; i++) {
            String page = clean(materialPages.get(i));
            if (page.isEmpty()) continue;
            String block = "\n--- صفحة PDF " + (i + 1) + " ---\n" + page;
            if (out.length() + block.length() > MATERIAL_MAX_CHARS) {
                int remain = MATERIAL_MAX_CHARS - out.length();
                if (remain > 250) out.append(block, 0, Math.min(block.length(), remain));
                break;
            }
            out.append(block);
        }
        return out.toString().trim();
    }

    PdfLessonContext.Result forLesson(String lessonTitle) {
        LessonKey key = LessonKey.from(lessonTitle);
        Selection plan = selectPlanWindow(planPages, key);
        Selection material = select(materialPages, key, false);
        return new PdfLessonContext.Result(
                plan.text, material.text, plan.pages, material.pages
        );
    }

    private static List<String> readAllPages(Context context, Uri uri) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(in, MemoryUsageSetting.setupTempFileOnly())) {
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

    private static Selection selectPlanWindow(List<String> pages, LessonKey key) {
        int bestPage = -1;
        int bestLine = -1;
        int bestScore = 0;
        String[] bestLines = null;

        for (int p = 0; p < pages.size(); p++) {
            String raw = pages.get(p) == null ? "" : pages.get(p);
            String[] lines = raw.split("\\n");
            for (int i = 0; i < lines.length; i++) {
                int s = score(lines[i], key, true);
                if (s > bestScore) {
                    bestScore = s;
                    bestPage = p;
                    bestLine = i;
                    bestLines = lines;
                }
            }
        }

        if (bestPage < 0 || bestLines == null || bestScore <= 0) {
            // Safer fallback: best page only. Do not include previous/next pages,
            // because that was the main cause of lesson overlap.
            Selection fallback = select(pages, key, true);
            if (fallback.pages.size() <= 1) return fallback;
            List<Integer> one = new ArrayList<>();
            one.add(fallback.pages.get(fallback.pages.size() / 2));
            return selectExact(pages, one, PLAN_MAX_CHARS);
        }

        int from = Math.max(0, bestLine - 4);
        int to = Math.min(bestLines.length - 1, bestLine + 5);
        StringBuilder out = new StringBuilder();
        for (int i = from; i <= to; i++) {
            String line = clean(bestLines[i]);
            if (!line.isEmpty()) out.append(line).append("\n");
        }

        List<Integer> human = new ArrayList<>();
        human.add(bestPage + 1);
        return new Selection(out.toString().trim(), human);
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
                chosen.add(scored.get(0).index);
            }
        } else {
            int count = 0;
            for (ScoredPage p : scored) {
                if (p.score <= 0) break;
                chosen.add(p.index);
                if (++count >= MATERIAL_TOP_PAGES) break;
            }
            // Do not add neighbouring pages automatically. Each lesson keeps
            // only pages that actually scored for it.
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
