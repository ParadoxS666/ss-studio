package android.content.res;

/** Desktop stand-in (tests only). */
public class Resources {
    private final android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
    private final Configuration cfg = new Configuration();

    public Resources(float density, float fontScale) { dm.density = density; cfg.fontScale = fontScale; }
    public android.util.DisplayMetrics getDisplayMetrics() { return dm; }
    public Configuration getConfiguration() { return cfg; }
}
