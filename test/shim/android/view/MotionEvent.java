package android.view;

/** Desktop stand-in (tests only). */
public class MotionEvent {
    public static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MOVE = 2, ACTION_CANCEL = 3;
    private final int action;
    private final float x, y;

    public MotionEvent(int action, float x, float y) { this.action = action; this.x = x; this.y = y; }
    public static MotionEvent obtain(int action, float x, float y) { return new MotionEvent(action, x, y); }
    public int getActionMasked() { return action; }
    public int getAction() { return action; }
    public float getX() { return x; }
    public float getY() { return y; }
    public void recycle() { }
}
