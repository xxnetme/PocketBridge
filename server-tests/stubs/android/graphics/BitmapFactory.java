package android.graphics;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Заглушка android.graphics.BitmapFactory для автономных тестов на JVM. */
public final class BitmapFactory {
    public static class Options {
        public int inSampleSize = 1;
        public boolean inJustDecodeBounds = false;
        public int outWidth = -1;
        public int outHeight = -1;
    }

    private BitmapFactory() {}

    public static Bitmap decodeFile(String path, Options options) {
        try {
            BufferedImage image = ImageIO.read(new File(path));
            if (image == null) return null;
            if (options != null) {
                options.outWidth = image.getWidth();
                options.outHeight = image.getHeight();
                if (options.inJustDecodeBounds) return null;
            }
            int sample = options == null ? 1 : Math.max(1, options.inSampleSize);
            if (sample > 1) {
                int width = Math.max(1, image.getWidth() / sample);
                int height = Math.max(1, image.getHeight() / sample);
                BufferedImage small = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = small.createGraphics();
                g.drawImage(image, 0, 0, width, height, null);
                g.dispose();
                image = small;
            }
            return new Bitmap(image);
        } catch (Exception e) {
            return null;
        }
    }
}
