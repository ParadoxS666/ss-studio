package android.content;

/** Desktop stand-in (tests only). */
public class Context {
    private final android.content.res.Resources res;
    private final android.content.res.AssetManager assets;
    private final java.io.File files;

    public Context(float density, float fontScale, java.io.File assetDir, java.io.File filesDir) {
        res = new android.content.res.Resources(density, fontScale);
        assets = new android.content.res.AssetManager(assetDir);
        files = filesDir;
    }

    public android.content.res.Resources getResources() { return res; }
    public android.content.res.AssetManager getAssets() { return assets; }
    public java.io.File getFilesDir() { return files; }
}
