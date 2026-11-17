package android.media;

import android.graphics.Bitmap;

/**
 * Заглушка android.media.MediaMetadataRetriever для автономных тестов.
 * На JVM видео не декодируется: сервер должен аккуратно ответить 400.
 */
public class MediaMetadataRetriever {
    public static final int METADATA_KEY_DURATION = 9;
    public static final int OPTION_CLOSEST_SYNC = 3;

    public void setDataSource(String path) { /* нет декодера в песочнице */ }

    public String extractMetadata(int key) { return null; }

    public Bitmap getFrameAtTime(long timeUs, int option) { return null; }

    public void release() { }
}
