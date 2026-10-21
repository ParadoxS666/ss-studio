package android.os;

/** Desktop stand-in (tests only): post runs at once, delayed posts are dropped. */
public class Handler {
    public Handler() { }
    public Handler(Looper l) { }
    public boolean post(Runnable r) { r.run(); return true; }
    public boolean postDelayed(Runnable r, long ms) { return true; }
    public void removeCallbacks(Runnable r) { }
}
