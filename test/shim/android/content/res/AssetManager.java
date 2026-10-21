package android.content.res;

/** Desktop stand-in (tests only). */
public class AssetManager {
    private final java.io.File dir;
    public AssetManager(java.io.File dir) { this.dir = dir; }
    public java.io.InputStream open(String name) throws java.io.IOException { return new java.io.FileInputStream(new java.io.File(dir, name)); }
}
