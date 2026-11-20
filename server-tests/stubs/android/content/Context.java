package android.content;
import android.content.res.AssetManager;
import java.io.File;
public class Context {
    private final AssetManager assets;
    private final File files;
    public Context(File assetDir) {
        this.assets = new AssetManager(assetDir);
        String configured = System.getProperty("pb.files");
        if (configured != null) this.files = new File(configured);
        else try { this.files = java.nio.file.Files.createTempDirectory("pb-private").toFile(); }
        catch (java.io.IOException failed) { throw new IllegalStateException(failed); }
    }
    public File getFilesDir() { return files; }
    public Context getApplicationContext(){ return this; }
    public AssetManager getAssets(){ return assets; }
}
