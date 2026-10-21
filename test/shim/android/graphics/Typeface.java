package android.graphics;

/** Desktop stand-in (tests only). */
public class Typeface {
    public static final int NORMAL = 0, BOLD = 1;
    public static final Typeface DEFAULT = new Typeface("SansSerif", java.awt.Font.PLAIN);
    public static final Typeface MONOSPACE = new Typeface("Monospaced", java.awt.Font.PLAIN);
    final String family;
    final int awtStyle;

    Typeface(String family, int awtStyle) { this.family = family; this.awtStyle = awtStyle; }

    public static Typeface create(String name, int style) {
        boolean bold = style == BOLD || (name != null && name.contains("medium"));
        return new Typeface("SansSerif", bold ? java.awt.Font.BOLD : java.awt.Font.PLAIN);
    }
}
