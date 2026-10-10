package com.khutwa.noorhelper;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Base64;

import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.rendering.ImageType;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.text.Normalizer;

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
             PDDocument doc = PDDocument.load(in, MemoryUsageSetting.setupTempFileOnly())) {
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
             PDDocument doc = PDDocument.load(in, MemoryUsageSetting.setupTempFileOnly())) {
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
                    // Never guess a crop boundary. If no reliable blank band exists,
                    // keep the whole top of the page so no question/content is cut.
                    top = safe >= 0 ? safe : 0;
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
                images.add(downscale(trimOuterBackground(out), MAX_WIDTH));
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

    static int saveBetweenToDirectory(Context context, Uri uri,
                                      String currentTitle, String nextTitle,
                                      int startPageOneBased, int endPageOneBased,
                                      int previousEndPageOneBased,
                                      int nextStartPageOneBased,
                                      File outputDir) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(in, MemoryUsageSetting.setupTempFileOnly())) {
            if (doc.getNumberOfPages() == 0) throw new IllegalStateException("ملف PDF فارغ");

            int start = Math.max(0, startPageOneBased - 1);
            if (start >= doc.getNumberOfPages()) throw new IllegalArgumentException("صفحة بداية الدرس خارج الملف");
            int end = Math.min(doc.getNumberOfPages() - 1,
                    Math.max(start, endPageOneBased > 0 ? endPageOneBased - 1 : start));

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
            int saved = 0;

            for (int p = start; p <= end; p++) {
                Bitmap page = null;
                Bitmap finalBitmap = null;
                try {
                    // Render one page, save it, then release it before moving on.
                    // This keeps memory use nearly constant even for long lessons.
                    page = renderer.renderImageWithDPI(p, 125, ImageType.RGB);
                    float pageHeightPt = doc.getPage(p).getCropBox().getHeight();

                    int top = 0;
                    int bottom = page.getHeight();

                    if (p == start && cropTopShared && currentHeadingY > 0) {
                        int approx = Math.max(0,
                                Math.round((currentHeadingY - 10f) / pageHeightPt * page.getHeight()));
                        int safe = findSafeWhitespaceBefore(page, approx, 140);
                        top = safe >= 0 ? safe : Math.max(0, approx - 55);
                    }

                    if (p == nextPage && cropBottomShared && nextHeadingY > 0) {
                        int approx = Math.min(page.getHeight(),
                                Math.round((nextHeadingY - 8f) / pageHeightPt * page.getHeight()));
                        int safe = findSafeWhitespaceBefore(page, approx, 160);
                        if (safe >= 0) bottom = safe;
                    }

                    if (bottom <= top + Math.max(30, page.getHeight() / 25)) {
                        top = 0;
                        bottom = page.getHeight();
                    }

                    Bitmap out;
                    if (top == 0 && bottom == page.getHeight()) {
                        out = page;
                    } else {
                        out = Bitmap.createBitmap(page, 0, top, page.getWidth(), bottom - top);
                        if (!page.isRecycled()) page.recycle();
                        page = null;
                    }

                    finalBitmap = downscale(trimOuterBackground(out), MAX_WIDTH);
                    File outFile = new File(outputDir, String.format(Locale.US, "%03d.jpg", saved + 1));
                    try (FileOutputStream outStream = new FileOutputStream(outFile)) {
                        if (!finalBitmap.compress(Bitmap.CompressFormat.JPEG, 82, outStream)) {
                            throw new IllegalStateException("تعذر ضغط صورة الدرس.");
                        }
                        outStream.flush();
                    }
                    saved++;
                } finally {
                    if (finalBitmap != null && !finalBitmap.isRecycled()) finalBitmap.recycle();
                    if (page != null && !page.isRecycled()) page.recycle();
                }
            }

            if (saved == 0) throw new IllegalStateException("تعذر إنشاء صور الدرس");
            return saved;
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
            boolean blank = isLowInformationRow(bitmap, y, x0, x1, xStep);
            if (blank && runStart < 0) runStart = y;

            boolean closes = !blank || y == maxY;
            if (closes && runStart >= 0) {
                int runEnd = blank && y == maxY ? y : y - 1;
                int length = runEnd - runStart + 1;
                if (length >= minBand) {
                    int center = (runStart + runEnd) / 2;
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

    private static boolean isLowInformationRow(Bitmap bitmap, int y, int x0, int x1, int step) {
        int count = 0;
        long sr = 0, sg = 0, sb = 0;
        for (int x = x0; x < x1; x += step) {
            int px = bitmap.getPixel(x, y);
            sr += (px >> 16) & 0xff;
            sg += (px >> 8) & 0xff;
            sb += px & 0xff;
            count++;
        }
        if (count < 4) return false;

        int ar = (int) (sr / count);
        int ag = (int) (sg / count);
        int ab = (int) (sb / count);
        int close = 0;

        for (int x = x0; x < x1; x += step) {
            int px = bitmap.getPixel(x, y);
            int r = (px >> 16) & 0xff;
            int g = (px >> 8) & 0xff;
            int b = px & 0xff;
            int delta = Math.abs(r - ar) + Math.abs(g - ag) + Math.abs(b - ab);
            if (delta <= 42) close++;
        }

        return close >= Math.ceil(count * 0.965);
    }

    private static Bitmap trimOuterBackground(Bitmap source) {
        if (source == null || source.getWidth() < 40 || source.getHeight() < 40) return source;

        int w = source.getWidth();
        int h = source.getHeight();
        int x0 = Math.max(0, w / 30);
        int x1 = Math.min(w, w - w / 30);
        int xStep = Math.max(4, w / 180);

        int top = 0;
        int bottom = h - 1;

        int topLimit = Math.min(h / 3, Math.max(40, h / 5));
        while (top < topLimit && isLowInformationRow(source, top, x0, x1, xStep)) top++;

        int bottomLimit = Math.max((h * 2) / 3, h - Math.max(40, h / 5));
        while (bottom > bottomLimit && isLowInformationRow(source, bottom, x0, x1, xStep)) bottom--;

        int pad = Math.max(10, h / 120);
        top = Math.max(0, top - pad);
        bottom = Math.min(h - 1, bottom + pad);

        if (bottom <= top + h / 4) return source;
        if (top <= 2 && bottom >= h - 3) return source;

        Bitmap cropped = Bitmap.createBitmap(source, 0, top, w, bottom - top + 1);
        if (cropped != source && !source.isRecycled()) source.recycle();
        return cropped;
    }



    static String questionCropBase64(Context context, Uri uri, int pageOneBased,
                                     String questionStart, String questionEnd) throws Exception {
        if (uri == null || pageOneBased <= 0 || questionStart == null || questionStart.trim().isEmpty()) {
            return "";
        }

        try (InputStream in = context.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(in, MemoryUsageSetting.setupTempFileOnly())) {
            int pageIndex = pageOneBased - 1;
            if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) return "";

            float pageWidthPt = doc.getPage(pageIndex).getCropBox().getWidth();
            float pageHeightPt = doc.getPage(pageIndex).getCropBox().getHeight();

            PhraseAnchor startAnchor = locatePhraseAnchor(
                    doc, pageIndex, questionStart, 0f, pageWidthPt, pageHeightPt);
            if (startAnchor == null) return "";

            PhraseAnchor endAnchor = null;
            if (questionEnd != null && !questionEnd.trim().isEmpty()) {
                endAnchor = locatePhraseAnchor(
                        doc, pageIndex, questionEnd,
                        Math.max(0f, startAnchor.y - 3f),
                        pageWidthPt, pageHeightPt);

                if (endAnchor != null) {
                    boolean wrongOrder = endAnchor.y + 4f < startAnchor.y;
                    boolean oppositeSpreadSide = pageWidthPt > pageHeightPt * 1.18f
                            && Math.abs(endAnchor.centerX() - startAnchor.centerX()) > pageWidthPt * 0.44f;
                    if (wrongOrder || oppositeSpreadSide) endAnchor = null;
                }
            }

            PDFRenderer renderer = new PDFRenderer(doc);
            Bitmap page = null;
            Bitmap crop = null;
            Bitmap finalBitmap = null;
            try {
                page = renderer.renderImageWithDPI(pageIndex, 150, ImageType.RGB);

                int w = page.getWidth();
                int h = page.getHeight();
                float sx = w / Math.max(1f, pageWidthPt);
                float sy = h / Math.max(1f, pageHeightPt);

                int anchorX = clamp(Math.round(startAnchor.centerX() * sx), 0, w - 1);
                int anchorLeft = clamp(Math.round(startAnchor.x * sx), 0, w - 1);
                int anchorRight = clamp(Math.round(startAnchor.right() * sx), 1, w);

                if (endAnchor != null) {
                    anchorLeft = Math.min(anchorLeft, clamp(Math.round(endAnchor.x * sx), 0, w - 1));
                    anchorRight = Math.max(anchorRight, clamp(Math.round(endAnchor.right() * sx), 1, w));
                }

                int[] lane = detectPrintedPageLane(page, anchorX);
                int x0 = lane[0];
                int x1 = lane[1];

                // The crop must always contain the text anchor itself.
                x0 = Math.min(x0, Math.max(0, anchorLeft - Math.max(8, w / 180)));
                x1 = Math.max(x1, Math.min(w, anchorRight + Math.max(8, w / 180)));
                x0 = clamp(x0, 0, w - 2);
                x1 = clamp(x1, x0 + 2, w);

                int approxTop = clamp(
                        Math.round((startAnchor.y - 10f) * sy), 0, h - 2);

                int safeTop = findSafeWhitespaceBefore(
                        page, approxTop, Math.max(80, h / 11), x0, x1);
                int top = safeTop >= 0
                        ? safeTop
                        : Math.max(0, approxTop - Math.max(16, h / 120));

                String qCompact = compact(questionStart);
                int qTokenCount = sourceTokenCount(questionStart);
                boolean headingLike = qCompact.length() < 28 || qTokenCount < 4;

                int approxBottom = -1;
                if (endAnchor != null && endAnchor.y >= startAnchor.y) {
                    approxBottom = clamp(
                            Math.round((endAnchor.bottom() + 16f) * sy), top + 2, h - 1);
                }

                int minBlockHeight = headingLike
                        ? Math.max(170, Math.round(h * 0.19f))
                        : Math.max(110, Math.round(h * 0.10f));
                int searchFrom = Math.min(h - 2, Math.max(
                        approxBottom > 0 ? approxBottom : top + minBlockHeight,
                        top + minBlockHeight
                ));

                int safeBottom = findSafeWhitespaceAfter(
                        page, searchFrom,
                        headingLike ? Math.max(260, h / 3) : Math.max(170, h / 6),
                        x0, x1);

                if (safeBottom < 0 && approxBottom > 0) {
                    safeBottom = findSafeWhitespaceAfter(
                            page, approxBottom, Math.max(260, h / 3), x0, x1);
                }

                // Wrong source blocks are worse than no crop. Never fall back to
                // "the rest of the page" when a safe lower boundary cannot be found.
                if (safeBottom < 0) return "";
                int bottom = safeBottom;

                if (bottom <= top + Math.max(80, h / 22)) return "";
                if (bottom - top > Math.round(h * 0.62f)) return "";

                // For a real sentence/question (not a short section heading), refine
                // horizontally inside the selected printed page. This separates a
                // question column from a parallel glossary/table without chopping a
                // whole visual activity selected by its heading.
                if (!headingLike) {
                    int[] local = refineQuestionColumn(
                            page, x0, x1, top, bottom, anchorX, anchorLeft, anchorRight);
                    if (local != null) {
                        x0 = local[0];
                        x1 = local[1];

                        // Re-evaluate row boundaries inside the narrower column; on
                        // two-page spreads a row can be busy on the other side while
                        // the target question itself has a clean separator.
                        int refinedTop = findSafeWhitespaceBefore(
                                page, approxTop, Math.max(80, h / 11), x0, x1);
                        if (refinedTop >= 0) top = refinedTop;

                        int refinedBottom = findSafeWhitespaceAfter(
                                page, searchFrom,
                                Math.max(170, h / 6), x0, x1);
                        if (refinedBottom >= 0) bottom = refinedBottom;
                    }
                }

                int horizontalPad = Math.max(10, w / 160);
                int verticalPad = Math.max(8, h / 150);
                x0 = Math.max(0, x0 - horizontalPad);
                x1 = Math.min(w, x1 + horizontalPad);
                top = Math.max(0, top - verticalPad);
                bottom = Math.min(h, bottom + verticalPad);

                if (x1 <= x0 + Math.max(100, w / 12)) return "";
                if (bottom <= top + Math.max(80, h / 22)) return "";

                // On a landscape two-page scan, accepting almost the full page width
                // is a strong sign that the lane detector failed. Skip rather than
                // mix two printed pages into one source image.
                if (w > h * 1.18f && x1 - x0 > Math.round(w * 0.72f)) return "";

                crop = Bitmap.createBitmap(page, x0, top, x1 - x0, bottom - top);
                if (!page.isRecycled()) page.recycle();
                page = null;

                finalBitmap = downscale(trimOuterBackground(crop), MAX_WIDTH);
                crop = null;

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (!finalBitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)) return "";
                return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            } finally {
                if (finalBitmap != null && !finalBitmap.isRecycled()) finalBitmap.recycle();
                if (crop != null && !crop.isRecycled()) crop.recycle();
                if (page != null && !page.isRecycled()) page.recycle();
            }
        }
    }

    private static PhraseAnchor locatePhraseAnchor(
            PDDocument doc, int pageIndex, String phrase, float minY,
            float pageWidthPt, float pageHeightPt) throws Exception {
        String target = compact(phrase);
        if (target.isEmpty()) return null;

        List<String> targetTokens = new ArrayList<>();
        for (String word : phrase.split("\\s+")) {
            String token = compact(word);
            if (token.length() >= 2 && !targetTokens.contains(token)) targetTokens.add(token);
        }

        PageLocator locator = new PageLocator();
        locator.setStartPage(pageIndex + 1);
        locator.setEndPage(pageIndex + 1);
        locator.getText(doc);

        PhraseAnchor best = null;

        for (int i = 0; i < locator.chunks.size(); i++) {
            TextChunk first = locator.chunks.get(i);
            if (first.y + 3f < minY) continue;

            StringBuilder joined = new StringBuilder();
            float minX = Float.MAX_VALUE;
            float minYY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;

            for (int j = i; j < locator.chunks.size() && j < i + 12; j++) {
                TextChunk part = locator.chunks.get(j);

                if (j > i && Math.abs(part.y - first.y) > 115f) break;
                if (!sameVisualSide(first, part, pageWidthPt, pageHeightPt)) continue;

                if (joined.length() > 0) joined.append(' ');
                joined.append(part.text);

                minX = Math.min(minX, part.x);
                minYY = Math.min(minYY, part.y);
                maxX = Math.max(maxX, part.right());
                maxY = Math.max(maxY, part.bottom());

                String cc = compact(joined.toString());
                if (cc.isEmpty()) continue;

                boolean exact = cc.contains(target);
                int hits = 0;
                int longHits = 0;
                for (String token : targetTokens) {
                    if (cc.contains(token)) {
                        hits++;
                        if (token.length() >= 5) longHits++;
                    }
                }

                int score = 0;
                if (exact) {
                    score = 520;
                } else {
                    if (target.length() < 12 || targetTokens.size() < 2) continue;
                    int needed = Math.max(2, (int) Math.ceil(targetTokens.size() * 0.72));
                    if (hits < needed) continue;
                    score = 170
                            + Math.round((hits * 150f) / Math.max(1, targetTokens.size()))
                            + Math.min(4, longHits) * 14;
                }

                String firstToken = targetTokens.isEmpty() ? "" : targetTokens.get(0);
                if (!firstToken.isEmpty() && cc.contains(firstToken)) score += 22;

                if (best == null || score > best.score) {
                    best = new PhraseAnchor(
                            minX,
                            minYY,
                            Math.max(1f, maxX - minX),
                            Math.max(1f, maxY - minYY),
                            score,
                            exact
                    );
                }
            }
        }

        return best != null && best.score >= 250 ? best : null;
    }

    private static boolean sameVisualSide(
            TextChunk first, TextChunk other, float pageWidthPt, float pageHeightPt) {
        if (pageWidthPt <= pageHeightPt * 1.18f) return true;

        float mid = pageWidthPt / 2f;
        float guard = Math.max(10f, pageWidthPt * 0.035f);
        float a = first.centerX();
        float b = other.centerX();

        boolean aLeft = a < mid - guard;
        boolean aRight = a > mid + guard;
        boolean bLeft = b < mid - guard;
        boolean bRight = b > mid + guard;

        return !((aLeft && bRight) || (aRight && bLeft));
    }

    private static int sourceTokenCount(String raw) {
        if (raw == null || raw.trim().isEmpty()) return 0;
        int n = 0;
        for (String word : raw.split("\\s+")) {
            if (compact(word).length() >= 2) n++;
        }
        return n;
    }

    private static int[] detectPrintedPageLane(Bitmap bitmap, int anchorX) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int margin = Math.max(4, w / 40);
        int left = margin;
        int right = w - margin;

        if (w <= h * 1.18f) return new int[]{left, right};

        int searchStart = Math.round(w * 0.32f);
        int searchEnd = Math.round(w * 0.68f);
        int y0 = Math.max(2, h / 20);
        int y1 = Math.min(h - 2, h - h / 20);
        int minBand = Math.max(7, w / 180);

        int[] centerBand = bestVerticalSeparatorBand(
                bitmap, searchStart, searchEnd, y0, y1, minBand, w / 2);

        if (centerBand == null) return new int[]{left, right};

        int center = (centerBand[0] + centerBand[1]) / 2;
        int pad = Math.max(3, w / 300);

        if (anchorX < center) {
            right = Math.max(left + 2, centerBand[0] - pad);
        } else {
            left = Math.min(right - 2, centerBand[1] + pad);
        }
        return new int[]{left, right};
    }

    private static int[] refineQuestionColumn(
            Bitmap bitmap, int laneLeft, int laneRight,
            int top, int bottom, int anchorX,
            int anchorLeft, int anchorRight) {
        int laneWidth = laneRight - laneLeft;
        if (laneWidth < 180 || bottom - top < 60) return null;

        int minBand = Math.max(6, bitmap.getWidth() / 220);
        int safeGap = Math.max(18, laneWidth / 18);

        List<int[]> bands = verticalSeparatorBands(
                bitmap,
                laneLeft + Math.max(3, laneWidth / 80),
                laneRight - Math.max(3, laneWidth / 80),
                Math.max(2, top),
                Math.min(bitmap.getHeight() - 2, bottom),
                minBand
        );

        int left = laneLeft;
        int right = laneRight;
        int bestLeft = -1;
        int bestRight = -1;

        for (int[] band : bands) {
            if (band[1] < anchorLeft - safeGap) {
                if (bestLeft < 0 || band[1] > bestLeft) bestLeft = band[1];
            }
            if (band[0] > anchorRight + safeGap) {
                if (bestRight < 0 || band[0] < bestRight) bestRight = band[0];
            }
        }

        if (bestLeft >= 0) left = bestLeft;
        if (bestRight >= 0) right = bestRight;

        int refinedWidth = right - left;
        int minimumUseful = Math.max(150, Math.round(laneWidth * 0.36f));
        if (refinedWidth < minimumUseful) return null;
        if (anchorX <= left || anchorX >= right) return null;

        // Refinement must actually remove a meaningful neighbouring column.
        if (refinedWidth > Math.round(laneWidth * 0.92f)) return null;
        return new int[]{left, right};
    }

    private static int[] bestVerticalSeparatorBand(
            Bitmap bitmap, int xStart, int xEnd,
            int y0, int y1, int minBand, int preferredX) {
        List<int[]> bands = verticalSeparatorBands(
                bitmap, xStart, xEnd, y0, y1, minBand);
        if (bands.isEmpty()) return null;

        int[] best = null;
        double bestScore = -Double.MAX_VALUE;
        for (int[] band : bands) {
            int width = band[1] - band[0] + 1;
            int center = (band[0] + band[1]) / 2;
            double score = width * 8.0 - Math.abs(center - preferredX) * 0.12;
            if (score > bestScore) {
                bestScore = score;
                best = band;
            }
        }
        return best;
    }

    private static List<int[]> verticalSeparatorBands(
            Bitmap bitmap, int xStart, int xEnd,
            int y0, int y1, int minBand) {
        List<int[]> out = new ArrayList<>();
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        xStart = clamp(xStart, 0, w - 1);
        xEnd = clamp(xEnd, xStart + 1, w);
        y0 = clamp(y0, 0, h - 1);
        y1 = clamp(y1, y0 + 1, h);

        int yStep = Math.max(2, (y1 - y0) / 180);
        int runStart = -1;

        for (int x = xStart; x < xEnd; x++) {
            boolean quiet = isLowInformationColumn(bitmap, x, y0, y1, yStep);
            if (quiet && runStart < 0) runStart = x;

            boolean closes = !quiet || x == xEnd - 1;
            if (closes && runStart >= 0) {
                int runEnd = quiet && x == xEnd - 1 ? x : x - 1;
                if (runEnd - runStart + 1 >= minBand) {
                    out.add(new int[]{runStart, runEnd});
                }
                runStart = -1;
            }
        }
        return out;
    }

    private static boolean isLowInformationColumn(
            Bitmap bitmap, int x, int y0, int y1, int step) {
        int count = 0;
        long sr = 0, sg = 0, sb = 0;

        for (int y = y0; y < y1; y += step) {
            int px = bitmap.getPixel(x, y);
            sr += (px >> 16) & 0xff;
            sg += (px >> 8) & 0xff;
            sb += px & 0xff;
            count++;
        }
        if (count < 5) return false;

        int ar = (int) (sr / count);
        int ag = (int) (sg / count);
        int ab = (int) (sb / count);
        int close = 0;

        for (int y = y0; y < y1; y += step) {
            int px = bitmap.getPixel(x, y);
            int r = (px >> 16) & 0xff;
            int g = (px >> 8) & 0xff;
            int b = px & 0xff;
            int delta = Math.abs(r - ar) + Math.abs(g - ag) + Math.abs(b - ab);
            if (delta <= 36) close++;
        }
        return close >= Math.ceil(count * 0.982);
    }

    private static int findSafeWhitespaceBefore(
            Bitmap bitmap, int approxY, int radius, int x0, int x1) {
        int minY = Math.max(2, approxY - Math.max(40, radius));
        int maxY = Math.min(bitmap.getHeight() - 2, approxY + 10);
        return nearestSafeHorizontalBand(
                bitmap, minY, maxY, approxY, true, x0, x1);
    }

    private static int findSafeWhitespaceAfter(
            Bitmap bitmap, int approxY, int radius, int x0, int x1) {
        int minY = Math.max(2, approxY - 5);
        int maxY = Math.min(bitmap.getHeight() - 2, approxY + Math.max(50, radius));
        return nearestSafeHorizontalBand(
                bitmap, minY, maxY, approxY, false, x0, x1);
    }

    private static int nearestSafeHorizontalBand(
            Bitmap bitmap, int minY, int maxY, int approxY,
            boolean preferBefore, int x0, int x1) {
        int h = bitmap.getHeight();
        int w = bitmap.getWidth();
        x0 = clamp(x0, 0, w - 1);
        x1 = clamp(x1, x0 + 1, w);
        minY = clamp(minY, 0, h - 1);
        maxY = clamp(maxY, minY + 1, h - 1);

        int minBand = Math.max(6, h / 220);
        int xStep = Math.max(2, (x1 - x0) / 220);
        int bestCenter = -1;
        int bestDistance = Integer.MAX_VALUE;
        int runStart = -1;

        for (int y = minY; y <= maxY; y++) {
            boolean quiet = isSafeSeparatorRow(bitmap, y, x0, x1, xStep);
            if (quiet && runStart < 0) runStart = y;

            boolean closes = !quiet || y == maxY;
            if (closes && runStart >= 0) {
                int runEnd = quiet && y == maxY ? y : y - 1;
                if (runEnd - runStart + 1 >= minBand) {
                    int center = (runStart + runEnd) / 2;
                    boolean wrongSide = preferBefore ? center > approxY + 3 : center < approxY - 3;
                    int distance = Math.abs(center - approxY) + (wrongSide ? 100000 : 0);
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

    private static boolean isSafeSeparatorRow(
            Bitmap bitmap, int y, int x0, int x1, int step) {
        int count = 0;
        long sr = 0, sg = 0, sb = 0;

        for (int x = x0; x < x1; x += step) {
            int px = bitmap.getPixel(x, y);
            sr += (px >> 16) & 0xff;
            sg += (px >> 8) & 0xff;
            sb += px & 0xff;
            count++;
        }
        if (count < 6) return false;

        int ar = (int) (sr / count);
        int ag = (int) (sg / count);
        int ab = (int) (sb / count);
        int close = 0;

        for (int x = x0; x < x1; x += step) {
            int px = bitmap.getPixel(x, y);
            int r = (px >> 16) & 0xff;
            int g = (px >> 8) & 0xff;
            int b = px & 0xff;
            int delta = Math.abs(r - ar) + Math.abs(g - ag) + Math.abs(b - ab);
            if (delta <= 34) close++;
        }

        // Much stricter than the old full-page cropper: sparse text must not be
        // mistaken for "whitespace" and sliced through.
        return close >= Math.ceil(count * 0.986);
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }


    static ExtractResult renderPages(Context context, Uri uri, List<Integer> pdfPages) throws Exception {
        if (pdfPages == null || pdfPages.isEmpty()) {
            throw new IllegalArgumentException("لا توجد صفحات محفوظة لهذا الدرس.");
        }
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(in, MemoryUsageSetting.setupTempFileOnly())) {
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
                page = downscale(trimOuterBackground(page), MAX_WIDTH);
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
            crop = downscale(trimOuterBackground(crop), MAX_WIDTH);
            images.add(crop);
        }

        if (images.isEmpty()) throw new IllegalStateException("تعذر إنشاء صور تمارين الدرس");
        return new ExtractResult(images, bestPage + 1, lastPage + 1, cleanSnippet(bestText), true);
    }

    static ExtractResult extractRange(Context context, Uri uri, int startPageOneBased, int endPageOneBased) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(in, MemoryUsageSetting.setupTempFileOnly())) {
            int start = Math.max(0, startPageOneBased - 1);
            int end = Math.min(doc.getNumberOfPages() - 1, Math.max(start, endPageOneBased - 1));
            if (start >= doc.getNumberOfPages()) throw new IllegalArgumentException("رقم صفحة البداية أكبر من عدد صفحات الملف");

            PDFRenderer renderer = new PDFRenderer(doc);
            List<Bitmap> images = new ArrayList<>();
            for (int p = start; p <= end && images.size() < 8; p++) {
                Bitmap page = renderer.renderImageWithDPI(p, 130, ImageType.RGB);
                images.add(downscale(trimOuterBackground(page), MAX_WIDTH));
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
        s = Normalizer.normalize(s, Normalizer.Form.NFKC);
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
        final float x;
        final float y;
        final float width;
        final float height;

        TextChunk(String text, float x, float y, float width, float height) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        float right() { return x + width; }
        float bottom() { return y + height; }
        float centerX() { return x + width / 2f; }
    }

    private static final class PhraseAnchor {
        final float x;
        final float y;
        final float width;
        final float height;
        final int score;
        final boolean exact;

        PhraseAnchor(float x, float y, float width, float height, int score, boolean exact) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.score = score;
            this.exact = exact;
        }

        float right() { return x + width; }
        float bottom() { return y + height; }
        float centerX() { return x + width / 2f; }
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

            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;

            for (TextPosition p : positions) {
                if (p == null) continue;
                float x = p.getXDirAdj();
                float y = p.getYDirAdj();
                float w = Math.max(0f, p.getWidthDirAdj());
                float h = Math.max(1f, p.getHeightDir());
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x + w);
                maxY = Math.max(maxY, y + h);
            }

            if (minX == Float.MAX_VALUE || minY == Float.MAX_VALUE) return;
            chunks.add(new TextChunk(
                    text,
                    minX,
                    minY,
                    Math.max(1f, maxX - minX),
                    Math.max(1f, maxY - minY)
            ));
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
