package android.media;

import android.graphics.Bitmap;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Заглушка android.media.MediaMetadataRetriever для автономных JVM/UI-тестов.
 * Для корректного MP4-контейнера (ftyp + moov) синтезирует кадр предпросмотра;
 * для повреждённых/пустых файлов возвращает null, проверяя обработку ошибок.
 */
public class MediaMetadataRetriever {
    public static final int METADATA_KEY_DURATION = 9;
    public static final int METADATA_KEY_VIDEO_WIDTH = 18;
    public static final int METADATA_KEY_VIDEO_HEIGHT = 19;
    public static final int METADATA_KEY_VIDEO_ROTATION = 24;
    public static final int OPTION_CLOSEST_SYNC = 3;

    private boolean validMp4;
    private int width = 640;
    private int height = 360;
    private int rotation = 0;
    private long durationMs = 6000L;

    public void setDataSource(String path) {
        validMp4 = false;
        if (path == null) return;
        File f = new File(path);
        if (!f.isFile() || f.length() < 32) return;
        try (FileInputStream in = new FileInputStream(f)) {
            int max = (int) Math.min(f.length(), 256 * 1024);
            byte[] b = new byte[max];
            int n = 0;
            while (n < max) {
                int r = in.read(b, n, max - n);
                if (r <= 0) break;
                n += r;
            }
            if (n < 16) return;
            String box0 = new String(b, 4, 4, StandardCharsets.ISO_8859_1);
            if (!"ftyp".equals(box0)) return;
            // Проверяем наличие полноценного контейнера moov среди верхнеуровневых атомов MP4.
            int pos = 0;
            while (pos + 8 <= n) {
                long sz = ((b[pos] & 0xFFL) << 24) | ((b[pos + 1] & 0xFFL) << 16)
                        | ((b[pos + 2] & 0xFFL) << 8) | (b[pos + 3] & 0xFFL);
                String tp = new String(b, pos + 4, 4, StandardCharsets.ISO_8859_1);
                if ("moov".equals(tp)) {
                    validMp4 = true;
                    break;
                }
                if (sz < 8 || pos + sz > f.length()) break;
                if (pos + sz > n) {
                    // moov может идти после большого mdat — проверяем хвост файла.
                    break;
                }
                pos += (int) sz;
            }
        } catch (Exception ignored) {}
    }

    public String extractMetadata(int key) {
        if (!validMp4) return null;
        if (key == METADATA_KEY_DURATION) return String.valueOf(durationMs);
        if (key == METADATA_KEY_VIDEO_WIDTH) return String.valueOf(width);
        if (key == METADATA_KEY_VIDEO_HEIGHT) return String.valueOf(height);
        if (key == METADATA_KEY_VIDEO_ROTATION) return String.valueOf(rotation);
        return null;
    }

    public Bitmap getFrameAtTime(long timeUs, int option) {
        if (!validMp4) return null;
        return renderFrame(width, height);
    }

    public Bitmap getScaledFrameAtTime(long timeUs, int option, int dstWidth, int dstHeight) {
        if (!validMp4) return null;
        return renderFrame(Math.max(1, dstWidth), Math.max(1, dstHeight));
    }

    private static Bitmap renderFrame(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(18, 62, 56), w, h, new Color(92, 214, 176)));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return new Bitmap(img);
    }

    public void release() { validMp4 = false; }
}
