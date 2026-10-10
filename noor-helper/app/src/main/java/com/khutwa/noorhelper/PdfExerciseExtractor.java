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
        if (uri == null || pageOneBased <= 0
                || questionStart == null || questionStart.trim().isEmpty()
                || questionEnd == null || questionEnd.trim().isEmpty()) {
            return "";
        }

        try (InputStream in = context.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(in, MemoryUsageSetting.setupTempFileOnly())) {
            int pageIndex = pageOneBased - 1;
            if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) return "";

            float pageWidthPt = doc.getPage(pageIndex).getCropBox().getWidth();
            float pageHeightPt = doc.getPage(pageIndex).getCropBox().getHeight();

            PageLocator locator = new PageLocator();
            locator.setStartPage(pageIndex + 1);
            locator.setEndPage(pageIndex + 1);
            locator.getText(doc);

            PhraseMatch start = locatePhraseMatch(locator, pageWidthPt, questionStart, 0f);
            if (start == null || start.score < 90) return "";

            PhraseMatch end = locatePhraseMatch(locator, pageWidthPt, questionEnd,
                    Math.max(0f, start.y1 - 2f));
            if (end == null || end.score < 88) return "";

            // qend must belong to the same visual block and follow q.
            if (end.y2 + 3f < start.y1) return "";
            float centerDistance = Math.abs(end.centerX() - start.centerX());
            boolean horizontallyRelated = rangesOverlap(start.x1, start.x2, end.x1, end.x2)
                    || centerDistance <= pageWidthPt * 0.38f;
            if (!horizontallyRelated) return "";

            float topPt = Math.max(0f, Math.min(start.y1, end.y1) - 8f);
            float bottomPt = Math.min(pageHeightPt, Math.max(start.y2, end.y2) + 20f);
            float leftPt = Math.min(start.x1, end.x1);
            float rightPt = Math.max(start.x2, end.x2);
            float seedCenter = (leftPt + rightPt) / 2f;

            // Expand only with text that is spatially part of the same local block.
            // This prevents another column/question on the same row from contaminating
            // the crop while still allowing wrapped question text to widen the box.
            boolean expanded;
            int expandGuard = 0;
            do {
                expanded = false;
                float allowance = Math.max(22f, pageWidthPt * 0.10f);
                for (TextChunk chunk : locator.chunks) {
                    if (chunk.bottom() < topPt - 5f || chunk.y > bottomPt + 20f) continue;
                    float center = chunk.centerX();
                    boolean nearCurrent = rangesOverlap(
                            leftPt - allowance, rightPt + allowance, chunk.x, chunk.right());
                    boolean nearSeed = Math.abs(center - seedCenter) <= pageWidthPt * 0.28f;
                    if (!nearCurrent || !nearSeed) continue;

                    float nl = Math.min(leftPt, chunk.x);
                    float nr = Math.max(rightPt, chunk.right());
                    float nt = Math.min(topPt, Math.max(0f, chunk.y - 4f));
                    float nb = Math.max(bottomPt, Math.min(pageHeightPt, chunk.bottom() + 8f));

                    if (nl < leftPt - 0.5f || nr > rightPt + 0.5f
                            || nt < topPt - 0.5f || nb > bottomPt + 0.5f) {
                        leftPt = nl;
                        rightPt = nr;
                        topPt = nt;
                        bottomPt = nb;
                        expanded = true;
                    }
                }
            } while (expanded && ++expandGuard < 3);

            float minCropWidth = pageWidthPt * 0.34f;
            float naturalWidth = rightPt - leftPt;
            if (naturalWidth < minCropWidth) {
                float half = minCropWidth / 2f;
                leftPt = Math.max(0f, seedCenter - half);
                rightPt = Math.min(pageWidthPt, seedCenter + half);
            }

            float padX = Math.max(10f, pageWidthPt * 0.025f);
            leftPt = Math.max(0f, leftPt - padX);
            rightPt = Math.min(pageWidthPt, rightPt + padX);

            // If the matched text itself is local, never silently turn it back into
            // a full-width strip. A wide crop is exactly what caused adjacent
            // questions and columns to appear together in 1.0.20.
            float maxLocalWidth = pageWidthPt * 0.76f;
            if (naturalWidth < pageWidthPt * 0.62f && rightPt - leftPt > maxLocalWidth) {
                float half = maxLocalWidth / 2f;
                leftPt = Math.max(0f, seedCenter - half);
                rightPt = Math.min(pageWidthPt, seedCenter + half);
            }

            PDFRenderer renderer = new PDFRenderer(doc);
            Bitmap page = null;
            Bitmap crop = null;
            Bitmap finalBitmap = null;
            try {
                page = renderer.renderImageWithDPI(pageIndex, 160, ImageType.RGB);
                int w = page.getWidth();
                int h = page.getHeight();

                int approxLeft = clamp(Math.round(leftPt / pageWidthPt * w), 0, w - 2);
                int approxRight = clamp(Math.round(rightPt / pageWidthPt * w), approxLeft + 2, w);
                int approxTop = clamp(Math.round(topPt / pageHeightPt * h), 0, h - 2);
                int approxBottom = clamp(Math.round(bottomPt / pageHeightPt * h), approxTop + 2, h);

                // Extend down to a local blank band so diagrams/tables attached to
                // the question are preserved, but inspect only this question's
                // horizontal region instead of the whole page.
                int localBottom = findSafeWhitespaceAfter(
                        page,
                        approxBottom,
                        Math.max(90, h / 7),
                        approxLeft,
                        approxRight
                );
                if (localBottom > approxBottom) approxBottom = localBottom;

                int localTop = findSafeWhitespaceBefore(
                        page,
                        approxTop,
                        Math.max(55, h / 18),
                        approxLeft,
                        approxRight
                );
                if (localTop >= 0 && localTop < approxTop) approxTop = localTop;

                // Look for gutters around the local block. These are especially
                // important for worksheets laid out as two or three columns.
                int gutterLeft = findSafeWhitespaceLeft(
                        page, approxLeft, approxTop, approxBottom, Math.max(80, w / 4));
                int gutterRight = findSafeWhitespaceRight(
                        page, approxRight, approxTop, approxBottom, Math.max(80, w / 4));
                if (gutterLeft >= 0) approxLeft = gutterLeft;
                if (gutterRight > approxRight) approxRight = gutterRight;

                int cropWidth = approxRight - approxLeft;
                int cropHeight = approxBottom - approxTop;
                if (cropWidth < Math.max(160, w / 5)
                        || cropHeight < Math.max(80, h / 24)) {
                    return "";
                }

                // Reject suspiciously huge local crops rather than showing an
                // unrelated neighbouring block.
                if (cropWidth > Math.round(w * 0.88f)
                        && naturalWidth < pageWidthPt * 0.62f) {
                    return "";
                }
                if (cropHeight > Math.round(h * 0.60f)) return "";

                crop = Bitmap.createBitmap(page, approxLeft, approxTop, cropWidth, cropHeight);
                if (!page.isRecycled()) page.recycle();
                page = null;

                finalBitmap = downscale(trimOuterBackground(crop), MAX_WIDTH);
                crop = null;

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (!finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) return "";
                return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            } finally {
                if (finalBitmap != null && !finalBitmap.isRecycled()) finalBitmap.recycle();
                if (crop != null && !crop.isRecycled()) crop.recycle();
                if (page != null && !page.isRecycled()) page.recycle();
            }
        }
    }

    private static PhraseMatch locatePhraseMatch(PageLocator locator, float pageWidthPt,
                                                 String phrase, float minY) {
        String target = compact(phrase);
        if (target.isEmpty() || locator == null) return null;

        List<String> targetTokens = new ArrayList<>();
        for (String word : phrase.split("\\s+")) {
            String token = compact(word);
            if (token.length() >= 2 && !targetTokens.contains(token)) targetTokens.add(token);
        }
        if (targetTokens.isEmpty()) return null;

        PhraseMatch best = null;

        for (int i = 0; i < locator.chunks.size(); i++) {
            TextChunk first = locator.chunks.get(i);
            if (first.y + 2f < minY) continue;

            StringBuilder joined = new StringBuilder();
            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float minMatchY = Float.MAX_VALUE;
            float maxMatchY = -Float.MAX_VALUE;

            for (int j = i; j < locator.chunks.size() && j < i + 9; j++) {
                TextChunk part = locator.chunks.get(j);
                float dy = Math.abs(part.y - first.y);
                if (dy > 82f) break;

                // Do not merge horizontally distant chunks from another column on
                // the same row. This was the main source of the mixed snippets.
                boolean overlapsFirst = rangesOverlap(
                        first.x - pageWidthPt * 0.08f,
                        first.right() + pageWidthPt * 0.08f,
                        part.x,
                        part.right());
                boolean centerNear = Math.abs(part.centerX() - first.centerX())
                        <= pageWidthPt * 0.34f;
                if (j > i && !overlapsFirst && !centerNear) continue;

                if (joined.length() > 0) joined.append(' ');
                joined.append(part.text);

                minX = Math.min(minX, part.x);
                maxX = Math.max(maxX, part.right());
                minMatchY = Math.min(minMatchY, part.y);
                maxMatchY = Math.max(maxMatchY, part.bottom());

                String cc = compact(joined.toString());
                if (cc.isEmpty()) continue;

                int score = 0;
                if (cc.equals(target)) score += 420;
                else if (cc.contains(target)) score += 340;
                else if (target.contains(cc)
                        && cc.length() >= Math.max(16, Math.min(42, target.length() / 2))) {
                    score += 180;
                }

                int hits = 0;
                int longHits = 0;
                for (String token : targetTokens) {
                    if (cc.contains(token)) {
                        hits++;
                        if (token.length() >= 5) longHits++;
                    }
                }
                score += Math.round((hits * 175f) / targetTokens.size());
                score += Math.min(4, longHits) * 18;

                if (hits == targetTokens.size()) score += 55;
                String firstToken = targetTokens.get(0);
                if (cc.contains(firstToken)) score += 28;

                // Penalize windows that already span most of the page; those are
                // likely accidental combinations of neighbouring questions.
                float span = maxX - minX;
                if (span > pageWidthPt * 0.80f && target.length() < 90) score -= 90;

                if (score >= 78 && (best == null || score > best.score)) {
                    best = new PhraseMatch(minX, minMatchY, maxX, maxMatchY, score);
                }
            }
        }
        return best;
    }

    private static boolean rangesOverlap(float a1, float a2, float b1, float b2) {
        return Math.min(a2, b2) >= Math.max(a1, b1);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int findSafeWhitespaceAfter(Bitmap bitmap, int approxY, int radius) {
        return findSafeWhitespaceAfter(bitmap, approxY, radius,
                Math.max(0, bitmap.getWidth() / 20),
                Math.min(bitmap.getWidth(), bitmap.getWidth() - bitmap.getWidth() / 20));
    }

    private static int findSafeWhitespaceAfter(Bitmap bitmap, int approxY, int radius,
                                               int x0, int x1) {
        if (bitmap == null || bitmap.getWidth() < 20 || bitmap.getHeight() < 20) return -1;

        x0 = clamp(x0, 0, bitmap.getWidth() - 2);
        x1 = clamp(x1, x0 + 2, bitmap.getWidth());
        int minY = Math.max(2, approxY - 4);
        int maxY = Math.min(bitmap.getHeight() - 2, approxY + Math.max(50, radius));
        int minBand = Math.max(8, bitmap.getHeight() / 190);
        int xStep = Math.max(3, (x1 - x0) / 150);

        int runStart = -1;
        for (int y = minY; y <= maxY; y++) {
            boolean blank = isLowInformationRow(bitmap, y, x0, x1, xStep);
            if (blank && runStart < 0) runStart = y;
            if ((!blank || y == maxY) && runStart >= 0) {
                int runEnd = blank && y == maxY ? y : y - 1;
                if (runEnd - runStart + 1 >= minBand) return (runStart + runEnd) / 2;
                runStart = -1;
            }
        }
        return -1;
    }

    private static int findSafeWhitespaceBefore(Bitmap bitmap, int approxY, int radius,
                                                int x0, int x1) {
        if (bitmap == null || bitmap.getWidth() < 20 || bitmap.getHeight() < 20) return -1;

        x0 = clamp(x0, 0, bitmap.getWidth() - 2);
        x1 = clamp(x1, x0 + 2, bitmap.getWidth());
        int minY = Math.max(2, approxY - Math.max(35, radius));
        int maxY = Math.min(bitmap.getHeight() - 2, approxY + 2);
        int minBand = Math.max(8, bitmap.getHeight() / 190);
        int xStep = Math.max(3, (x1 - x0) / 150);

        int bestCenter = -1;
        int runStart = -1;
        for (int y = minY; y <= maxY; y++) {
            boolean blank = isLowInformationRow(bitmap, y, x0, x1, xStep);
            if (blank && runStart < 0) runStart = y;
            if ((!blank || y == maxY) && runStart >= 0) {
                int runEnd = blank && y == maxY ? y : y - 1;
                if (runEnd - runStart + 1 >= minBand) {
                    int center = (runStart + runEnd) / 2;
                    if (center <= approxY) bestCenter = center;
                }
                runStart = -1;
            }
        }
        return bestCenter;
    }

    private static int findSafeWhitespaceLeft(Bitmap bitmap, int approxX,
                                              int y0, int y1, int radius) {
        if (bitmap == null) return -1;
        int minX = Math.max(1, approxX - Math.max(40, radius));
        int maxX = Math.min(bitmap.getWidth() - 2, approxX);
        int minBand = Math.max(7, bitmap.getWidth() / 220);
        int runStart = -1;
        int best = -1;

        for (int x = maxX; x >= minX; x--) {
            boolean blank = isLowInformationColumn(bitmap, x, y0, y1);
            if (blank && runStart < 0) runStart = x;
            if ((!blank || x == minX) && runStart >= 0) {
                int runEnd = blank && x == minX ? x : x + 1;
                int width = Math.abs(runStart - runEnd) + 1;
                if (width >= minBand) {
                    best = (runStart + runEnd) / 2;
                    break;
                }
                runStart = -1;
            }
        }
        return best;
    }

    private static int findSafeWhitespaceRight(Bitmap bitmap, int approxX,
                                               int y0, int y1, int radius) {
        if (bitmap == null) return -1;
        int minX = Math.max(1, approxX);
        int maxX = Math.min(bitmap.getWidth() - 2, approxX + Math.max(40, radius));
        int minBand = Math.max(7, bitmap.getWidth() / 220);
        int runStart = -1;

        for (int x = minX; x <= maxX; x++) {
            boolean blank = isLowInformationColumn(bitmap, x, y0, y1);
            if (blank && runStart < 0) runStart = x;
            if ((!blank || x == maxX) && runStart >= 0) {
                int runEnd = blank && x == maxX ? x : x - 1;
                if (runEnd - runStart + 1 >= minBand) return (runStart + runEnd) / 2;
                runStart = -1;
            }
        }
        return -1;
    }

    private static boolean isLowInformationColumn(Bitmap bitmap, int x, int y0, int y1) {
        y0 = clamp(y0, 0, bitmap.getHeight() - 2);
        y1 = clamp(y1, y0 + 2, bitmap.getHeight());
        int step = Math.max(3, (y1 - y0) / 120);
        int count = 0;
        long sr = 0, sg = 0, sb = 0;

        for (int y = y0; y < y1; y += step) {
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

        for (int y = y0; y < y1; y += step) {
            int px = bitmap.getPixel(x, y);
            int r = (px >> 16) & 0xff;
            int g = (px >> 8) & 0xff;
            int b = px & 0xff;
            int delta = Math.abs(r - ar) + Math.abs(g - ag) + Math.abs(b - ab);
            if (delta <= 38) close++;
        }
        return close >= Math.ceil(count * 0.97);
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
            this.width = Math.max(0f, width);
            this.height = Math.max(1f, height);
        }

        float right() { return x + width; }
        float bottom() { return y + height; }
        float centerX() { return x + width / 2f; }
    }

    private static final class PhraseMatch {
        final float x1;
        final float y1;
        final float x2;
        final float y2;
        final int score;

        PhraseMatch(float x1, float y1, float x2, float y2, int score) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.score = score;
        }

        float centerX() { return (x1 + x2) / 2f; }
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
            float maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;

            for (TextPosition p : positions) {
                float x = p.getXDirAdj();
                float y = p.getYDirAdj();
                float w = Math.max(0f, p.getWidthDirAdj());
                float h = Math.max(1f, p.getHeightDir());

                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x + w);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y + h);
            }

            if (minX == Float.MAX_VALUE || minY == Float.MAX_VALUE) return;
            chunks.add(new TextChunk(text, minX, minY,
                    Math.max(1f, maxX - minX), Math.max(1f, maxY - minY)));
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
