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

            return extractByTextSearch(doc, lessonTitle);
        }
    }

    static ExtractResult extractBetween(Context context, Uri uri,
                                        String currentTitle, String nextTitle,
                                        int startPageOneBased, int endPageOneBased,
                                        int previousEndPageOneBased,
                                        int nextStartPageOneBased) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(in)) {
            if (doc.getNumberOfPages() == 0) throw new IllegalStateException("ملف PDF فارغ");

            int start = Math.max(0, startPageOneBased - 1);
            if (start >= doc.getNumberOfPages()) throw new IllegalArgumentException("صفحة بداية الدرس خارج الملف");
            int end = Math.min(doc.getNumberOfPages() - 1,
                    Math.max(start, endPageOneBased > 0 ? endPageOneBased - 1 : start));

            // We crop only when a physical PDF page is shared with an adjacent lesson.
            // Otherwise the complete page is preserved. This prevents questions from
            // being cut merely because their text resembles a lesson title.
            boolean cropTopShared = previousEndPageOneBased > 0
                    && previousEndPageOneBased == startPageOneBased;
            boolean cropBottomShared = nextStartPageOneBased > 0
                    && nextStartPageOneBased == endPageOneBased
                    && nextTitle != null && !nextTitle.trim().isEmpty();

            float currentHeadingY = -1f;
            if (cropTopShared) {
                currentHeadingY = PageLocator.locateHeading(doc, start, LessonKey.from(currentTitle));
            }

            int nextPage = cropBottomShared ? nextStartPageOneBased - 1 : -1;
            float nextHeadingY = -1f;
            if (cropBottomShared && nextPage >= start && nextPage <= end) {
                nextHeadingY = PageLocator.locateHeading(doc, nextPage, LessonKey.from(nextTitle));
            }

            PDFRenderer renderer = new PDFRenderer(doc);
            List<Bitmap> images = new ArrayList<>();

            for (int p = start; p <= end; p++) {
                Bitmap page = renderer.renderImageWithDPI(p, 135, ImageType.RGB);
                float pageHeightPt = doc.getPage(p).getCropBox().getHeight();

                int top = 0;
                int bottom = page.getHeight();

                if (p == start && cropTopShared && currentHeadingY > 0) {
                    int approx = Math.max(0,
                            Math.round((currentHeadingY - 10f) / pageHeightPt * page.getHeight()));
                    int safe = findSafeWhitespaceBefore(page, approx, 140);
                    // If no clear blank band exists, preserve extra content above the
                    // heading rather than risking cutting the first question.
                    top = safe >= 0 ? safe : Math.max(0, approx - 55);
                }

                if (p == nextPage && cropBottomShared && nextHeadingY > 0) {
                    int approx = Math.min(page.getHeight(),
                            Math.round((nextHeadingY - 8f) / pageHeightPt * page.getHeight()));
                    int safe = findSafeWhitespaceBefore(page, approx, 160);
                    // If a reliable blank band cannot be found, do not crop the page.
                    // A little overlap is safer than slicing through a question.
                    if (safe >= 0) bottom = safe;
                }

                if (bottom <= top + Math.max(30, page.getHeight() / 25)) {
                    // Safety fallback: never produce a tiny strip that could be a
                    // fragment of a question. Keep the whole page instead.
                    top = 0;
                    bottom = page.getHeight();
                }

                Bitmap out;
                if (top == 0 && bottom == page.getHeight()) {
                    out = page;
                } else {
                    out = Bitmap.createBitmap(page, 0, top, page.getWidth(), bottom - top);
                    page.recycle();
                }
                images.add(downscale(out, MAX_WIDTH));
            }

            if (images.isEmpty()) throw new IllegalStateException("تعذر إنشاء صور الدرس");
            return new ExtractResult(
                    images,
                    start + 1,
                    end + 1,
                    "صفحات كاملة مع قص آمن للصفحات المشتركة فقط",
                    true
            );
        }
    }

    private static int findSafeWhitespaceBefore(Bitmap bitmap, int approxY, int radius) {
        if (bitmap == null || bitmap.getWidth() < 20 || bitmap.getHeight() < 20) return -1;

        int minY = Math.max(2, approxY - Math.max(40, radius));
        int maxY = Math.min(bitmap.getHeight() - 2, approxY + 18);
        int minBand = Math.max(10, bitmap.getHeight() / 170);
        int x0 = Math.max(0, bitmap.getWidth() / 20);
        int x1 = Math.min(bitmap.getWidth(), bitmap.getWidth() - bitmap.getWidth() / 20);
        int xStep = Math.max(4, bitmap.getWidth() / 180);

        int bestCenter = -1;
        int bestDistance = Integer.MAX_VALUE;
        int runStart = -1;

        for (int y = minY; y <= maxY; y++) {
            boolean blank = isMostlyBlankRow(bitmap, y, x0, x1, xStep);
            if (blank && runStart < 0) runStart = y;

            boolean closes = !blank || y == maxY;
            if (closes && runStart >= 0) {
                int runEnd = blank && y == maxY ? y : y - 1;
                int length = runEnd - runStart + 1;
                if (length >= minBand) {
                    int center = (runStart + runEnd) / 2;
                    // Prefer whitespace before the heading, then the closest band.
                    int penalty = center > approxY ? 10000 : 0;
                    int distance = penalty + Math.abs(approxY - center);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestCenter = center;
                    }
                }
                runStart = -1;
            }
        }
        return bestCenter;
    }

    private static boolean isMostlyBlankRow(Bitmap bitmap, int y, int x0, int x1, int step) {
        int samples = 0;
        int light = 0;
        for (int x = x0; x < x1; x += step) {
            int pixel = bitmap.getPixel(x, y);
            int r = (pixel >> 16) & 0xff;
            int g = (pixel >> 8) & 0xff;
            int b = pixel & 0xff;
            samples++;
            if (r >= 238 && g >= 238 && b >= 238) light++;
        }
        return samples > 0 && light >= Math.ceil(samples * 0.965);
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

        static float locateHeading(PDDocument doc, int pageIndex, LessonKey key) throws Exception {
            PageLocator l = new PageLocator();
            l.setStartPage(pageIndex + 1);
            l.setEndPage(pageIndex + 1);
            l.getText(doc);

            // First pass: a real lesson heading normally contains the numeric code
            // and starts with it. Do not accept an occurrence buried inside a question.
            for (TextChunk chunk : l.chunks) {
                String cc = compact(chunk.text);
                if (!key.codeCompact.isEmpty() && cc.startsWith(key.codeCompact)) {
                    if (key.titleCompact.isEmpty() || cc.contains(key.titleCompact)
                            || titleTokenHits(cc, key.tokens) >= Math.min(2, key.tokens.size())) {
                        return chunk.y;
                    }
                }
            }

            // Second pass: title text with multiple matching tokens and a short line.
            float bestY = -1f;
            int best = 0;
            for (TextChunk chunk : l.chunks) {
                String cc = compact(chunk.text);
                int hits = titleTokenHits(cc, key.tokens);
                int score = hits * 20;
                if (!key.titleCompact.isEmpty() && cc.contains(key.titleCompact)) score += 80;
                if (chunk.text != null && chunk.text.length() <= 90) score += 8;
                if (score > best && score >= 48) {
                    best = score;
                    bestY = chunk.y;
                }
            }
            return bestY;
        }

        private static int titleTokenHits(String compactChunk, List<String> tokens) {
            int hits = 0;
            if (tokens == null) return 0;
            for (String token : tokens) {
                if (token != null && token.length() >= 3 && compactChunk.contains(token)) hits++;
            }
            return hits;
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
