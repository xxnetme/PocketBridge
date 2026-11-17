package android.content.res;
import java.io.*;
public class AssetManager {
    private final File dir;
    public AssetManager(File dir){ this.dir = dir; }
    public InputStream open(String name) throws IOException { return new FileInputStream(new File(dir, name)); }
}
