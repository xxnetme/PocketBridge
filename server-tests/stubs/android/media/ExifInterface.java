package android.media;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Заглушка android.media.ExifInterface для автономных JVM-тестов:
 * разбирает настоящий сегмент JPEG APP1 (Exif TIFF IFD0, тег 0x0112 Orientation).
 */
public class ExifInterface {
    public static final String TAG_ORIENTATION = "Orientation";
    public static final int ORIENTATION_UNDEFINED = 0;
    public static final int ORIENTATION_NORMAL = 1;
    public static final int ORIENTATION_FLIP_HORIZONTAL = 2;
    public static final int ORIENTATION_ROTATE_180 = 3;
    public static final int ORIENTATION_FLIP_VERTICAL = 4;
    public static final int ORIENTATION_TRANSPOSE = 5;
    public static final int ORIENTATION_ROTATE_90 = 6;
    public static final int ORIENTATION_TRANSVERSE = 7;
    public static final int ORIENTATION_ROTATE_270 = 8;

    private final int orientation;

    public ExifInterface(String filename) throws IOException {
        this.orientation = readJpegOrientation(new File(filename));
    }

    public int getAttributeInt(String tag, int defaultValue) {
        if (TAG_ORIENTATION.equals(tag)) {
            return orientation > 0 ? orientation : defaultValue;
        }
        return defaultValue;
    }

    private static int readJpegOrientation(File f) {
        if (f == null || !f.isFile() || f.length() < 12) return ORIENTATION_UNDEFINED;
        try (FileInputStream in = new FileInputStream(f)) {
            int max = (int) Math.min(f.length(), 128 * 1024);
            byte[] b = new byte[max];
            int off = 0;
            while (off < max) {
                int r = in.read(b, off, max - off);
                if (r <= 0) break;
                off += r;
            }
            if (off < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) return ORIENTATION_UNDEFINED;
            int pos = 2;
            while (pos + 4 <= off) {
                if (u8(b, pos) != 0xFF) break;
                int marker = u8(b, pos + 1);
                pos += 2;
                if (marker == 0xD9 || marker == 0xDA) break;
                if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) continue;
                if (pos + 2 > off) break;
                int segLen = u16(b, pos, false);
                if (segLen < 2 || pos + segLen > off) break;
                if (marker == 0xE1 && segLen >= 14) {
                    String hdr = new String(b, pos + 2, 6, StandardCharsets.US_ASCII);
                    if ("Exif\0\0".equals(hdr)) {
                        int tiff = pos + 8;
                        int tiffEnd = pos + segLen;
                        if (tiff + 8 <= tiffEnd) {
                            boolean le;
                            if (b[tiff] == 'I' && b[tiff + 1] == 'I') le = true;
                            else if (b[tiff] == 'M' && b[tiff + 1] == 'M') le = false;
                            else return ORIENTATION_UNDEFINED;
                            if (u16(b, tiff + 2, le) != 42) return ORIENTATION_UNDEFINED;
                            int ifdOff = i32(b, tiff + 4, le);
                            int ifd = tiff + ifdOff;
                            if (ifd >= tiff && ifd + 2 <= tiffEnd) {
                                int count = u16(b, ifd, le);
                                int p = ifd + 2;
                                for (int i = 0; i < count && p + 12 <= tiffEnd; i++, p += 12) {
                                    int tag = u16(b, p, le);
                                    if (tag == 0x0112) {
                                        return u16(b, p + 8, le);
                                    }
                                }
                            }
                        }
                    }
                }
                pos += segLen;
            }
        } catch (Exception ignored) {}
        return ORIENTATION_UNDEFINED;
    }

    private static int u8(byte[] b, int o) { return b[o] & 0xFF; }
    private static int u16(byte[] b, int o, boolean le) {
        return le ? (u8(b, o) | (u8(b, o + 1) << 8)) : ((u8(b, o) << 8) | u8(b, o + 1));
    }
    private static int i32(byte[] b, int o, boolean le) {
        return le
                ? (u8(b, o) | (u8(b, o + 1) << 8) | (u8(b, o + 2) << 16) | (u8(b, o + 3) << 24))
                : ((u8(b, o) << 24) | (u8(b, o + 1) << 16) | (u8(b, o + 2) << 8) | u8(b, o + 3));
    }
}
