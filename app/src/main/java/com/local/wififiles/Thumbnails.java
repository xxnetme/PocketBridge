package com.local.wififiles;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/** Миниатюры для плитки: декодирование с прореживанием и JPEG-сжатие. */
final class Thumbnails {
    static final int MIN_WIDTH = 64;
    static final int MAX_WIDTH = 512;

    private Thumbnails() {}

    static byte[] jpeg(File file, int maxWidth) throws IOException {
        int width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, maxWidth));

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0) throw new FileServer.ApiException(400, "Не удалось прочитать изображение");

        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) throw new FileServer.ApiException(400, "Не удалось прочитать изображение");

        try {
            int sourceWidth = bitmap.getWidth();
            if (sourceWidth > width) {
                int scaledHeight = Math.max(1, Math.round(bitmap.getHeight() * (width / (float) sourceWidth)));
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, scaledHeight, true);
                bitmap.recycle();
                bitmap = scaled;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, buffer)) throw new FileServer.ApiException(500, "Не удалось сжать миниатюру");
            return buffer.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }
}
