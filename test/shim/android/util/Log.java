package android.util;

/** Desktop stand-in (tests only). */
public class Log {
    public static int e(String tag, String msg, Throwable t) { System.err.println(tag + ": " + msg + " " + t); return 0; }
    public static int e(String tag, String msg) { System.err.println(tag + ": " + msg); return 0; }
    public static int w(String tag, String msg) { return 0; }
    public static int i(String tag, String msg) { return 0; }
}
