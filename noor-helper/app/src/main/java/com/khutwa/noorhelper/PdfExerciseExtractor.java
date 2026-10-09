package com.khutwa.noorhelper;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.rendering.ImageType;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PdfExerciseExtractor {

    static final class ExtractResult {
        final List<Bitmap> images;
        final int startPage;
        final int endPage;
        final String matchedText;
        final boolean automatic;

        ExtractResult(List<Bitmap> images, int startPage, int endPage, String matchedText, boolean automatic) {
            this.images = images;
            this.startPage = startPage;
            this.endPage = endPage;
            this.matchedText = matchedText;
            this.automatic = automatic;
        }
    }

    private static final int MAX_FALLBACK_PAGES = 4;
    private static final int MAX_WIDTH = 1200;

    private PdfExerciseExtractor() {}

    static ExtractResult extract(Context context, Uri uri, String lessonTitle) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(in)) {
            if (doc.getNumberOfPages() == 0) throw new IllegalStateException("ملف PDF فارغ");

            ExerciseMap.Lesson mapped = ExerciseMap.find(lessonTitle);
            if (mapped != null && doc.getNumberOfPages() >= 109) {
                return extractMapped(doc, mapped);
            }

            return extractByTextSearch(doc, lessonTitle);
        }
    }

    static ExtractResult renderPages(Context context, Uri uri, List<Integer> pdfPages) throws Exception {
        if (pdfPages == null || pdfPages.isEmpty()) {
            throw new IllegalArgumentException("لا توجد صفحات محفوظة لهذا الدرس.");
        }
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(in)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            List<Bitmap> images = new ArrayList<>();
            int first = Integer.MAX_VALUE;
            int last = -1;

            LinkedHashSet<Integer> unique = new LinkedHashSet<>(pdfPages);
            for (Integer pageNo : unique) {
                if (pageNo == null) continue;
                int pageIndex = pageNo - 1;
                if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) continue;
                Bitmap page = renderer.renderImageWithDPI(pageIndex, 135, ImageType.RGB);
                page = downscale(page, MAX_WIDTH);
                images.add(page);
                first = Math.min(first, pageNo);
                last = Math.max(last, pageNo);
            }

            if (images.isEmpty()) throw new IllegalStateException("تعذر قراءة الصفحات المحفوظة من المادة العلمية.");
            return new ExtractResult(images, first, last, "صفحات محفوظة في قاعدة البيانات", true);
        }
    }

    private static ExtractResult extractMapped(PDDocument doc, ExerciseMap.Lesson lesson) throws Exception {
        PDFRenderer renderer = new PDFRenderer(doc);
        List<Bitmap> images = new ArrayList<>();
        int first = Integer.MAX_VALUE;
        int last = -1;

        for (ExerciseMap.Segment segment : lesson.segments) {
            int pageIndex = segment.pdfPage - 1;
            if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) continue;

            Bitmap page = renderer.renderImageWithDPI(pageIndex, 135, ImageType.RGB);
            Bitmap crop = page;

            if (segment.side != ExerciseMap.Side.FULL) {
                int half = page.getWidth() / 2;
                int x = segment.side == ExerciseMap.Side.LEFT ? 0 : half;
                int width = segment.side == ExerciseMap.Side.LEFT ? half : page.getWidth() - half;
                crop = Bitmap.createBitmap(page, x, 0, width, page.getHeight());
                if (crop != page) page.recycle();
            }

            crop = downscale(crop, MAX_WIDTH);
            images.add(crop);
            first = Math.min(first, segment.pdfPage);
            last = Math.max(last, segment.pdfPage);
        }

        if (images.isEmpty()) throw new IllegalStateException("لم أتمكن من استخراج صفحات الدرس من الخريطة المحفوظة");
        return new ExtractResult(
                images,
                first,
                last,
                "خريطة ثابتة: " + lesson.code + " " + lesson.title,
                true
        );
    }

    private static ExtractResult extractByTextSearch(PDDocument doc, String lessonTitle) throws Exception {
        LessonKey key = LessonKey.from(lessonTitle);
        int bestPage = -1;
        int bestScore = -1;
        String bestText = "";

        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            String text = pageText(doc, i);
            int score = score(text, key);
            if (score > bestScore || (score == bestScore && score > 0 && i > bestPage)) {
                bestScore = score;
                bestPage = i;
                bestText = text;
            }
        }

        if (bestPage < 0 || bestScore < 18) {
            throw new IllegalStateException("لم أتمكن من مطابقة الدرس تلقائيًا داخل ملف التمارين");
        }

        PageLocator startLocator = PageLocator.locate(doc, bestPage, key);
        float startY = startLocator.bestStartY;
        int lastPage = Math.min(doc.getNumberOfPages() - 1, bestPage + MAX_FALLBACK_PAGES - 1);
        float endY = -1f;

        if (!key.nextCode.isEmpty()) {
            boolean foundEnd = false;
            for (int p = bestPage; p <= lastPage; p++) {
                PageLocator locator = PageLocator.locateNext(doc, p, key.nextCode, p == bestPage ? startY + 20f : 0f);
                if (locator.bestNextY >= 0) {
                    lastPage = p;
                    endY = locator.bestNextY;
                    foundEnd = true;
                    break;
                }
            }
            if (!foundEnd) endY = -1f;
        }

        PDFRenderer renderer = new PDFRenderer(doc);
        List<Bitmap> images = new ArrayList<>();
        for (int p = bestPage; p <= lastPage; p++) {
            Bitmap page = renderer.renderImageWithDPI(p, 130, ImageType.RGB);
            float pageHeightPt = doc.getPage(p).getCropBox().getHeight();

            int top = 0;
            int bottom = page.getHeight();

            if (p == bestPage && startY > 0) {
                top = Math.max(0, Math.round((startY - 26f) / pageHeightPt * page.getHeight()));
            }
            if (p == lastPage && endY > 0) {
                bottom = Math.min(page.getHeight(), Math.round((endY - 18f) / pageHeightPt * page.getHeight()));
            }

            if (bottom - top < page.getHeight() / 5) {
                top = Math.max(0, top - page.getHeight() / 10);
                bottom = Math.min(page.getHeight(), bottom + page.getHeight() / 4);
            }

            Bitmap crop = Bitmap.createBitmap(page, 0, top, page.getWidth(), Math.max(1, bottom - top));
            if (crop != page) page.recycle();
            crop = downscale(crop, MAX_WIDTH);
            images.add(crop);
        }

        if (images.isEmpty()) throw new IllegalStateException("تعذر إنشاء صور تمارين الدرس");
        return new ExtractResult(images, bestPage + 1, lastPage + 1, cleanSnippet(bestText), true);
    }

    static ExtractResult extractRange(Context context, Uri uri, int startPageOneBased, int endPageOneBased) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(in)) {
            int start = Math.max(0, startPageOneBased - 1);
            int end = Math.min(doc.getNumberOfPages() - 1, Math.max(start, endPageOneBased - 1));
            if (start >= doc.getNumberOfPages()) throw new IllegalArgumentException("رقم صفحة البداية أكبر من عدد صفحات الملف");

            PDFRenderer renderer = new PDFRenderer(doc);
            List<Bitmap> images = new ArrayList<>();
            for (int p = start; p <= end && images.size() < 8; p++) {
                Bitmap page = renderer.renderImageWithDPI(p, 130, ImageType.RGB);
                images.add(downscale(page, MAX_WIDTH));
            }
            return new ExtractResult(images, start + 1, Math.min(end + 1, start + 8), "تحديد يدوي", false);
        }
    }

    private static Bitmap downscale(Bitmap source, int maxWidth) {
        if (source.getWidth() <= maxWidth) return source;
        int h = Math.max(1, Math.round(source.getHeight() * (maxWidth / (float) source.getWidth())));
        Bitmap scaled = Bitmap.createScaledBitmap(source, maxWidth, h, true);
        if (scaled != source) source.recycle();
        return scaled;
    }

    private static String pageText(PDDocument doc, int pageIndex) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        return stripper.getText(doc);
    }

    private static int score(String pageText, LessonKey key) {
        String compact = compact(pageText);
        if (compact.isEmpty()) return 0;

        int score = 0;
        if (!key.titleCompact.isEmpty() && compact.contains(key.titleCompact)) score += 140;
        if (!key.codeCompact.isEmpty() && compact.contains(key.codeCompact)) score += 42;

        for (String token : key.tokens) {
            if (token.length() >= 3 && compact.contains(token)) score += 9;
        }

        if (compact.length() > 400) score += 3;
        return score;
    }

    private static String cleanSnippet(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 220 ? s.substring(0, 220) + "…" : s;
    }

    private static String compact(String s) {
        if (s == null) return "";
        s = arabicDigitsToLatin(s.toLowerCase(Locale.ROOT));
        s = s.replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "");
        s = s.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي');
        return s.replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static String arabicDigitsToLatin(String s) {
        String ar = "٠١٢٣٤٥٦٧٨٩";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int idx = ar.indexOf(c);
            out.append(idx >= 0 ? (char) ('0' + idx) : c);
        }
        return out.toString();
    }

    private static final class LessonKey {
        final String code;
        final String nextCode;
        final String codeCompact;
        final String titleCompact;
        final List<String> tokens;

        LessonKey(String code, String nextCode, String titleCompact, List<String> tokens) {
            this.code = code;
            this.nextCode = nextCode;
            this.codeCompact = compact(code);
            this.titleCompact = titleCompact;
            this.tokens = tokens;
        }

        static LessonKey from(String raw) {
            String normalized = arabicDigitsToLatin(raw == null ? "" : raw);
            Pattern p = Pattern.compile("(\\d+)\\s*[-–]\\s*(\\d+)");
            Matcher m = p.matcher(normalized);
            String code = "";
            String next = "";
            String title = normalized;
            if (m.find()) {
                code = m.group(1) + "-" + m.group(2);
                try {
                    next = m.group(1) + "-" + (Integer.parseInt(m.group(2)) + 1);
                } catch (Exception ignored) {}
                title = normalized.substring(m.end()).trim();
            }
            String tc = compact(title);
            List<String> toks = new ArrayList<>();
            for (String word : title.split("\\s+")) {
                String t = compact(word);
                if (t.length() >= 3) toks.add(t);
            }
            return new LessonKey(code, next, tc, toks);
        }
    }

    private static final class TextChunk {
        final String text;
        final float y;

        TextChunk(String text, float y) {
            this.text = text;
            this.y = y;
        }
    }

    private static final class PageLocator extends PDFTextStripper {
        final List<TextChunk> chunks = new ArrayList<>();
        float bestStartY = -1f;
        float bestNextY = -1f;

        private PageLocator() throws Exception {
            super();
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (positions == null || positions.isEmpty()) return;
            float y = positions.get(0).getYDirAdj();
            chunks.add(new TextChunk(text, y));
        }

        static PageLocator locate(PDDocument doc, int pageIndex, LessonKey key) throws Exception {
            PageLocator l = new PageLocator();
            l.setStartPage(pageIndex + 1);
            l.setEndPage(pageIndex + 1);
            l.getText(doc);

            int best = -1;
            for (TextChunk c : l.chunks) {
                String cc = compact(c.text);
                int score = 0;
                if (!key.titleCompact.isEmpty() && cc.contains(key.titleCompact)) score += 100;
                if (!key.codeCompact.isEmpty() && cc.contains(key.codeCompact)) score += 50;
                for (String t : key.tokens) if (cc.contains(t)) score += 8;
                if (score > best) {
                    best = score;
                    l.bestStartY = c.y;
                }
            }
            return l;
        }

        static PageLocator locateNext(PDDocument doc, int pageIndex, String nextCode, float minY) throws Exception {
            PageLocator l = new PageLocator();
            l.setStartPage(pageIndex + 1);
            l.setEndPage(pageIndex + 1);
            l.getText(doc);
            String nc = compact(nextCode);
            for (TextChunk c : l.chunks) {
                String cc = compact(c.text);
                if (c.y >= minY && !nc.isEmpty() && cc.startsWith(nc)) {
                    l.bestNextY = c.y;
                    break;
                }
            }
            return l;
        }
    }
}
