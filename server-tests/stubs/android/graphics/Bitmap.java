package android.graphics;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import javax.imageio.ImageIO;

/**
 * Заглушка android.graphics.Bitmap для автономных тестов на JVM.
 * Реализация через java.awt.ImageIO повторяет поведение Android: масштабирование, повороты и сжатие.
 */
public final class Bitmap {
    public enum CompressFormat { JPEG, PNG, WEBP }

    private final BufferedImage image;

    public Bitmap(BufferedImage image) { this.image = image; }

    public int getWidth() { return image.getWidth(); }

    public int getHeight() { return image.getHeight(); }

    public boolean compress(CompressFormat format, int quality, OutputStream out) {
        try {
            if (format == CompressFormat.PNG) return ImageIO.write(image, "png", out);
            BufferedImage rgb = toRgb(image);
            return ImageIO.write(rgb, "jpg", out);
        } catch (Exception e) {
            return false;
        }
    }

    public static Bitmap createScaledBitmap(Bitmap source, int width, int height, boolean filter) {
        BufferedImage scaled = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                filter ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(source.image, 0, 0, Math.max(1, width), Math.max(1, height), null);
        g.dispose();
        return new Bitmap(scaled);
    }

    public static Bitmap createBitmap(Bitmap source, int x, int y, int width, int height, Matrix m, boolean filter) {
        BufferedImage cur = source.image.getSubimage(x, y, width, height);
        if (m == null || m.ops.isEmpty()) return new Bitmap(cur);
        for (Matrix.Op op : m.ops) {
            if (op.rotate) {
                int deg = ((Math.round(op.a) % 360) + 360) % 360;
                cur = rotateOrtho(cur, deg);
            } else {
                cur = flipImage(cur, op.a < 0f, op.b < 0f);
            }
        }
        return new Bitmap(cur);
    }

    private static BufferedImage rotateOrtho(BufferedImage src, int deg) {
        int w = src.getWidth(), h = src.getHeight();
        int type = src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        if (deg == 90) {
            BufferedImage dst = new BufferedImage(h, w, type);
            for (int sy = 0; sy < h; sy++)
                for (int sx = 0; sx < w; sx++)
                    dst.setRGB(h - 1 - sy, sx, src.getRGB(sx, sy));
            return dst;
        } else if (deg == 180) {
            BufferedImage dst = new BufferedImage(w, h, type);
            for (int sy = 0; sy < h; sy++)
                for (int sx = 0; sx < w; sx++)
                    dst.setRGB(w - 1 - sx, h - 1 - sy, src.getRGB(sx, sy));
            return dst;
        } else if (deg == 270) {
            BufferedImage dst = new BufferedImage(h, w, type);
            for (int sy = 0; sy < h; sy++)
                for (int sx = 0; sx < w; sx++)
                    dst.setRGB(sy, w - 1 - sx, src.getRGB(sx, sy));
            return dst;
        }
        return src;
    }

    private static BufferedImage flipImage(BufferedImage src, boolean fx, boolean fy) {
        int w = src.getWidth(), h = src.getHeight();
        int type = src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage dst = new BufferedImage(w, h, type);
        for (int sy = 0; sy < h; sy++)
            for (int sx = 0; sx < w; sx++)
                dst.setRGB(fx ? (w - 1 - sx) : sx, fy ? (h - 1 - sy) : sy, src.getRGB(sx, sy));
        return dst;
    }

    public void recycle() { /* no-op в тестовой заглушке */ }

    public boolean isRecycled() { return false; }

    BufferedImage image() { return image; }

    private static BufferedImage toRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) return source;
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return rgb;
    }
}
