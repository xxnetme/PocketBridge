package com.local.wififiles;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.os.Build;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Миниатюры, EXIF-ориентация, поворот фото и кадры видео (Android 8–16). */
final class Thumbnails {
    static final int MIN_WIDTH = 64;
    static final int MAX_WIDTH = 512;
    static final int PREVIEW_WIDTH = 2400;
    static final long ROTATE_MAX_BYTES = 40L * 1024 * 1024;
    static final long ROTATE_MAX_PIXELS = 36_000_000L;

    private Thumbnails() {}

    /** Метаданные размеров изображения с учётом EXIF-ориентации (без декодирования пикселей). */
    static int[] readImageInfo(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int orientation = readExifOrientation(file);
            boolean swap = isSwappedOrientation(orientation);
            int w = swap ? bounds.outHeight : bounds.outWidth;
            int h = swap ? bounds.outWidth : bounds.outHeight;
            return new int[]{ w, h, orientation };
        } catch (Throwable e) {
            return null;
        }
    }

    static int readExifOrientation(File file) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            return (o >= 1 && o <= 8) ? o : ExifInterface.ORIENTATION_NORMAL;
        } catch (Throwable e) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static boolean isSwappedOrientation(int orientation) {
        return orientation == ExifInterface.ORIENTATION_TRANSPOSE
                || orientation == ExifInterface.ORIENTATION_ROTATE_90
                || orientation == ExifInterface.ORIENTATION_TRANSVERSE
                || orientation == ExifInterface.ORIENTATION_ROTATE_270;
    }

    private static Matrix exifMatrix(int orientation, int extraDegrees) {
        Matrix m = new Matrix();
        boolean changed = false;
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.postScale(-1f, 1f); changed = true; break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                m.postRotate(180f); changed = true; break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.postScale(1f, -1f); changed = true; break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                m.postRotate(90f); m.postScale(-1f, 1f); changed = true; break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                m.postRotate(90f); changed = true; break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                m.postRotate(270f); m.postScale(-1f, 1f); changed = true; break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                m.postRotate(270f); changed = true; break;
            default:
                break;
        }
        int deg = ((extraDegrees % 360) + 360) % 360;
        if (deg != 0) {
            m.postRotate(deg);
            changed = true;
        }
        return changed ? m : null;
    }

    private static Bitmap applyMatrix(Bitmap src, Matrix m) {
        if (m == null) return src;
        Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (out != src) src.recycle();
        return out;
    }

    static byte[] jpeg(File file, int maxWidth) throws IOException {
        Bitmap bitmap = decode(file, Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, maxWidth)));
        try {
            return compress(bitmap, Bitmap.CompressFormat.JPEG, 82);
        } finally {
            bitmap.recycle();
        }
    }

    /** Полноразмерное превью для форматов, которые браузер не умеет (HEIC/RAW), с учётом EXIF. */
    static byte[] previewJpeg(File file) throws IOException {
        Bitmap bitmap = decode(file, PREVIEW_WIDTH);
        try {
            return compress(bitmap, Bitmap.CompressFormat.JPEG, 86);
        } finally {
            bitmap.recycle();
        }
    }

    /** Поворачивает растровое изображение (JPEG/PNG/WebP) на 90/180/270° с атомарной перезаписью. */
    @SuppressWarnings("deprecation")
    static int[] rotateFile(File file, int degrees) throws IOException {
        int deg = ((degrees % 360) + 360) % 360;
        if (deg != 90 && deg != 180 && deg != 270) {
            throw new FileServer.ApiException(400, "Допустимый угол поворота: 90, 180 или 270");
        }
        if (file.length() > ROTATE_MAX_BYTES) {
            throw new FileServer.ApiException(413, "Файл больше 40 МБ — поверните его на компьютере");
        }
        String low = file.getName().toLowerCase(Locale.ROOT);
        Bitmap.CompressFormat fmt;
        int quality;
        if (low.endsWith(".jpg") || low.endsWith(".jpeg")) {
            fmt = Bitmap.CompressFormat.JPEG;
            quality = 92;
        } else if (low.endsWith(".png")) {
            fmt = Bitmap.CompressFormat.PNG;
            quality = 100;
        } else if (low.endsWith(".webp")) {
            fmt = Bitmap.CompressFormat.WEBP;
            quality = 92;
        } else {
            throw new FileServer.ApiException(400, "Сохранение поворота поддерживается для JPEG, PNG и WebP");
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new FileServer.ApiException(415, unsupportedMessage(file));
        }
        long pixels = (long) bounds.outWidth * (long) bounds.outHeight;
        if (pixels > ROTATE_MAX_PIXELS) {
            throw new FileServer.ApiException(413, "Слишком высокое разрешение для поворота на устройстве");
        }

        File parent = file.getParentFile();
        if (parent != null) {
            long usable = parent.getUsableSpace();
            if (usable > 0L && usable < Math.max(file.length() * 2L, 4L * 1024 * 1024)) {
                throw new FileServer.ApiException(400, "Недостаточно свободного места на телефоне");
            }
        }

        int exifOrientation = readExifOrientation(file);
        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (decoded == null) throw new FileServer.ApiException(415, unsupportedMessage(file));

        Bitmap rotated = null;
        File tmp = File.createTempFile(".wifi-upload-", ".part", parent);
        boolean ok = false;
        int outW, outH;
        try {
            Matrix m = exifMatrix(exifOrientation, deg);
            rotated = applyMatrix(decoded, m);
            decoded = null;
            outW = rotated.getWidth();
            outH = rotated.getHeight();
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
                if (!rotated.compress(fmt, quality, out)) {
                    throw new FileServer.ApiException(500, "Не удалось сохранить повёрнутое изображение");
                }
                out.flush();
            }
            rotated.recycle();
            rotated = null;
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception moveErr) {
                if (file.exists() && !file.delete()) throw new FileServer.ApiException(400, "Не удалось заменить файл");
                if (!tmp.renameTo(file)) throw new FileServer.ApiException(400, "Не удалось сохранить файл");
            }
            ok = true;
        } catch (OutOfMemoryError oom) {
            throw new FileServer.ApiException(413, "Недостаточно памяти для поворота изображения");
        } finally {
            if (decoded != null && !decoded.isRecycled()) decoded.recycle();
            if (rotated != null && !rotated.isRecycled()) rotated.recycle();
            if (!ok && tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        return new int[]{ outW, outH };
    }

    /** Кадр видео (репрезентативный ключевой кадр, с аппаратным даунскейлом на Android 8.1+). */
    static byte[] videoFrame(File file, int maxWidth) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            int targetWidth = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, maxWidth));
            long durationMs = parsePositiveLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            // Берём кадр на ~25% ролика (но не дальше 5 с), чтобы миновать чёрный интро и не искать в глубине длинных видео.
            long timeUs = durationMs >= 4000L
                    ? Math.min(durationMs * 250L, 5_000_000L)
                    : Math.max(0L, durationMs * 500L);

            Bitmap frame = null;
            if (Build.VERSION.SDK_INT >= 27) {
                frame = Api27.scaledFrame(retriever, timeUs, targetWidth);
            }
            if (frame == null) {
                frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null && timeUs > 0L) {
                frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null) {
                throw new FileServer.ApiException(400, "Не удалось извлечь кадр из видео");
            }
            try {
                if (frame.getWidth() > targetWidth) {
                    int height = Math.max(1, Math.round(frame.getHeight() * (targetWidth / (float) frame.getWidth())));
                    Bitmap scaled = Bitmap.createScaledBitmap(frame, targetWidth, height, true);
                    if (scaled != frame) {
                        frame.recycle();
                        try {
                            return compress(scaled, Bitmap.CompressFormat.JPEG, 80);
                        } finally {
                            scaled.recycle();
                        }
                    }
                }
                return compress(frame, Bitmap.CompressFormat.JPEG, 80);
            } finally {
                if (!frame.isRecycled()) frame.recycle();
            }
        } catch (FileServer.ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FileServer.ApiException(400, "Видео не поддерживается устройством: " + e.getMessage());
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    private static long parsePositiveLong(String s) {
        if (s == null || s.isEmpty()) return 0L;
        try { return Math.max(0L, Long.parseLong(s.trim())); }
        catch (NumberFormatException e) { return 0L; }
    }

    @TargetApi(27)
    private static final class Api27 {
        static Bitmap scaledFrame(MediaMetadataRetriever r, long timeUs, int targetWidth) {
            try {
                long vw = parsePositiveLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                long vh = parsePositiveLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                long rot = parsePositiveLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                if (vw <= 0 || vh <= 0) return null;
                boolean swap = (rot == 90L || rot == 270L);
                long effW = swap ? vh : vw;
                long effH = swap ? vw : vh;
                int dstW = (int) Math.min(effW, targetWidth);
                int dstH = Math.max(1, Math.round(effH * (dstW / (float) effW)));
                return r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dstW, dstH);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static Bitmap decode(File file, int width) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new FileServer.ApiException(415, unsupportedMessage(file));
        }

        int orientation = readExifOrientation(file);
        int effectiveWidth = isSwappedOrientation(orientation) ? bounds.outHeight : bounds.outWidth;
        int sample = 1;
        while (effectiveWidth / (sample * 2) >= width) sample *= 2;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) throw new FileServer.ApiException(415, unsupportedMessage(file));

        Matrix m = exifMatrix(orientation, 0);
        if (m != null) {
            bitmap = applyMatrix(bitmap, m);
        }

        int sourceWidth = bitmap.getWidth();
        if (sourceWidth > width) {
            int scaledHeight = Math.max(1, Math.round(bitmap.getHeight() * (width / (float) sourceWidth)));
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, scaledHeight, true);
            if (scaled != bitmap) bitmap.recycle();
            return scaled;
        }
        return bitmap;
    }

    private static String unsupportedMessage(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".heic") || name.endsWith(".heif"))
            return "HEIC поддерживается на Android 9 и новее. Скачайте файл или обновите систему.";
        if (name.endsWith(".dng") || name.endsWith(".cr2") || name.endsWith(".nef") || name.endsWith(".arw") || name.endsWith(".raf"))
            return "RAW-формат не поддерживается для просмотра. Скачайте файл.";
        return "Не удалось прочитать изображение";
    }

    private static byte[] compress(Bitmap bitmap, Bitmap.CompressFormat fmt, int quality) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!bitmap.compress(fmt, quality, buffer)) throw new FileServer.ApiException(500, "Не удалось сжать изображение");
        return buffer.toByteArray();
    }
}
