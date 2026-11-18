package com.local.wififiles;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** Миниатюры и превью: изображения (включая HEIC на Android 9+), кадры видео. */
final class Thumbnails {
    static final int MIN_WIDTH = 64;
    static final int MAX_WIDTH = 512;
    static final int PREVIEW_WIDTH = 2400;

    private Thumbnails() {}

    static byte[] jpeg(File file, int maxWidth) throws IOException {
        Bitmap bitmap = decode(file, Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, maxWidth)));
        try {
            return compress(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    /** Полноразмерное превью для форматов, которые браузер не умеет (HEIC/RAW): перекодируем в JPEG. */
    static byte[] previewJpeg(File file) throws IOException {
        Bitmap bitmap = decode(file, PREVIEW_WIDTH);
        try {
            return compress(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    /** Кадр видео (середина файла, ближайший ключевой кадр). */
    static byte[] videoFrame(File file, int maxWidth) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long timeUs = 0;
            try {
                if (duration != null) timeUs = Math.max(0, Long.parseLong(duration) * 500);
            } catch (NumberFormatException ignored) {}
            Bitmap frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) throw new FileServer.ApiException(400, "Не удалось извлечь кадр из видео");
            try {
                int width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, maxWidth));
                if (frame.getWidth() > width) {
                    int height = Math.max(1, Math.round(frame.getHeight() * (width / (float) frame.getWidth())));
                    Bitmap scaled = Bitmap.createScaledBitmap(frame, width, height, true);
                    if (scaled != frame) {
                        frame.recycle();
                        try {
                            return compress(scaled);
                        } finally {
                            scaled.recycle();
                        }
                    }
                }
                return compress(frame);
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

    private static Bitmap decode(File file, int width) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0) throw new FileServer.ApiException(415, unsupportedMessage(file));

        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) throw new FileServer.ApiException(415, unsupportedMessage(file));

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

    private static byte[] compress(Bitmap bitmap) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, buffer)) throw new FileServer.ApiException(500, "Не удалось сжать изображение");
        return buffer.toByteArray();
    }
}
