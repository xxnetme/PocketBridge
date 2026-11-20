package android.system;

import java.nio.file.Files;
import java.nio.file.Paths;

public class Os {
    public static StructStat stat(String path) throws ErrnoException {
        try { return new StructStat(((Number) Files.getAttribute(Paths.get(path), "unix:dev")).longValue()); }
        catch (Exception e) { throw new ErrnoException(e); }
    }
}
