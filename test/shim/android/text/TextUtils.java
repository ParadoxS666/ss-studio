package android.text;

/** Desktop stand-in (tests only). */
public class TextUtils {
    public enum TruncateAt { END }

    public static CharSequence ellipsize(CharSequence s, TextPaint p, float avail, TruncateAt where) {
        String str = s.toString();
        if (p.measureText(str) <= avail) return str;
        for (int n = str.length() - 1; n > 0; n--) {
            String t = str.substring(0, n) + "…";
            if (p.measureText(t) <= avail) return t;
        }
        return "";
    }
}
