package com.khutwa.noorhelper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class LessonImageCache {
    private static final String DIR = "generated_lesson_images_v1";

    private LessonImageCache() {}

    static int build(Context context, Uri materialUri, String lessonCode,
                     String lessonTitle, String nextLessonTitle,
                     int startPage, int endPage,
                     int previousEndPage, int nextStartPage) throws Exception {
        PdfExerciseExtractor.ExtractResult result =
                PdfExerciseExtractor.extractBetween(
                        context, materialUri,
                        lessonTitle, nextLessonTitle,
                        startPage, endPage, previousEndPage, nextStartPage
                );

        File lessonDir = lessonDir(context, lessonCode);
        deleteRecursively(lessonDir);
        if (!lessonDir.mkdirs() && !lessonDir.isDirectory()) {
            throw new IllegalStateException("تعذر إنشاء مجلد صور الدرس.");
        }

        int saved = 0;
        try {
            for (int i = 0; i < result.images.size(); i++) {
                Bitmap bitmap = result.images.get(i);
                if (bitmap == null) continue;
                File outFile = new File(lessonDir, String.format("%03d.jpg", i + 1));
                try (FileOutputStream out = new FileOutputStream(outFile)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 72, out)) {
                        throw new IllegalStateException("تعذر ضغط صورة الدرس.");
                    }
                    out.flush();
                    saved++;
                } finally {
                    if (!bitmap.isRecycled()) bitmap.recycle();
                }
            }
        } catch (Exception e) {
            deleteRecursively(lessonDir);
            throw e;
        }

        if (saved == 0) {
            deleteRecursively(lessonDir);
            throw new IllegalStateException("لم تُحفظ أي صورة للدرس.");
        }
        return saved;
    }


    static List<Bitmap> load(Context context, String lessonCode) {
        List<Bitmap> out = new ArrayList<>();
        File dir = lessonDir(context, lessonCode);
        File[] files = dir.listFiles((d, name) -> name != null && name.endsWith(".jpg"));
        if (files == null) return out;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) out.add(b);
        }
        return out;
    }

    static boolean has(Context context, String lessonCode) {
        File dir = lessonDir(context, lessonCode);
        File[] files = dir.listFiles((d, name) -> name != null && name.endsWith(".jpg"));
        return files != null && files.length > 0;
    }

    static void clearAll(Context context) {
        deleteRecursively(new File(context.getFilesDir(), DIR));
    }

    private static File lessonDir(Context context, String code) {
        String safe = (code == null ? "" : code).replaceAll("[^0-9A-Za-z_-]", "_");
        return new File(new File(context.getFilesDir(), DIR), safe);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        try { file.delete(); } catch (Exception ignored) {}
    }
}
