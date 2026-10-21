package android.view;

import android.content.Context;
import android.content.res.Resources;

/** Desktop stand-in (tests only): size is set by the harness. */
public class View {
    public static final int VISIBLE = 0, GONE = 8;
    private final Context ctx;
    private int w, h, vis = VISIBLE;
    public int invalidations, haptics;

    public View(Context ctx) { this.ctx = ctx; }
    public Context getContext() { return ctx; }
    public Resources getResources() { return ctx.getResources(); }
    public int getWidth() { return w; }
    public int getHeight() { return h; }
    public void setSize(int w, int h) { int ow = this.w, oh = this.h; this.w = w; this.h = h; onSizeChanged(w, h, ow, oh); }
    public void invalidate() { invalidations++; }
    public void postInvalidateOnAnimation() { invalidations++; }
    public boolean performHapticFeedback(int c) { haptics++; return true; }
    public void setHapticFeedbackEnabled(boolean on) { }
    public int getVisibility() { return vis; }
    public void setVisibility(int v) { vis = v; }
    protected void onConfigurationChanged(android.content.res.Configuration c) { }
    protected void onDraw(android.graphics.Canvas c) { }
    public boolean onTouchEvent(MotionEvent e) { return false; }
    public void draw(android.graphics.Canvas c) { onDraw(c); }
    public void setFocusable(boolean f) { }
    public ViewParent getParent() { return b -> { }; }
    public boolean post(Runnable r) { r.run(); return true; }
    public boolean postDelayed(Runnable r, long ms) { return true; }
    public boolean removeCallbacks(Runnable r) { return true; }
    protected void onSizeChanged(int w, int h, int ow, int oh) { }
    protected void onWindowVisibilityChanged(int v) { }
    protected void onDetachedFromWindow() { }
    protected void onAttachedToWindow() { }
    public void postInvalidateDelayed(long ms) { invalidations++; }
}
