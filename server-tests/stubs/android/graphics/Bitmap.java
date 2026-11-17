package android.graphics;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import javax.imageio.ImageIO;

/**
 * Заглушка android.graphics.Bitmap для автономных тестов на JVM.
 * Реализация через java.awt.ImageIO повторяет поведение Android: масштабирование и JPEG-сжатие.
 */
public final class Bitmap {
    public enum CompressFormat { JPEG, PNG, WEBP }

    private final BufferedImage image;

    Bitmap(BufferedImage image) { this.image = image; }

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

    public void recycle() { /* no-op в тестовой заглушке */ }

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
