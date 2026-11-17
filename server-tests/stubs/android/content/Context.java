package android.content;
import android.content.res.AssetManager;
import java.io.File;
public class Context {
    private final AssetManager assets;
    public Context(File assetDir){ this.assets = new AssetManager(assetDir); }
    public Context getApplicationContext(){ return this; }
    public AssetManager getAssets(){ return assets; }
}
