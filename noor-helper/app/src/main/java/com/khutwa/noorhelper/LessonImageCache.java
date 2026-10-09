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
        File lessonDir = lessonDir(context, lessonCode);
        deleteRecursively(lessonDir);
        if (!lessonDir.mkdirs() && !lessonDir.isDirectory()) {
            throw new IllegalStateException("تعذر إنشاء مجلد صور الدرس.");
        }

        try {
            int saved = PdfExerciseExtractor.saveBetweenToDirectory(
                    context, materialUri,
                    lessonTitle, nextLessonTitle,
                    startPage, endPage, previousEndPage, nextStartPage,
                    lessonDir
            );
            if (saved <= 0) {
                deleteRecursively(lessonDir);
                throw new IllegalStateException("لم تُحفظ أي صورة للدرس.");
            }
            return saved;
        } catch (OutOfMemoryError e) {
            deleteRecursively(lessonDir);
            throw e;
        } catch (Exception e) {
            deleteRecursively(lessonDir);
            throw e;
        }
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
        return imageCount(context, lessonCode) > 0;
    }

    static int imageCount(Context context, String lessonCode) {
        File[] files = imageFiles(context, lessonCode);
        return files.length;
    }

    static Bitmap loadAt(Context context, String lessonCode, int index) {
        File[] files = imageFiles(context, lessonCode);
        if (index < 0 || index >= files.length) return null;
        return BitmapFactory.decodeFile(files[index].getAbsolutePath());
    }

    private static File[] imageFiles(Context context, String lessonCode) {
        File dir = lessonDir(context, lessonCode);
        File[] files = dir.listFiles((d, name) -> name != null && name.endsWith(".jpg"));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
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
