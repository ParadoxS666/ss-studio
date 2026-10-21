package android.graphics;

/** Desktop stand-in (tests only). */
public class RectF {
    public float left, top, right, bottom;
    public RectF() { }
    public RectF(float l, float t, float r, float b) { set(l, t, r, b); }
    public void set(float l, float t, float r, float b) { left = l; top = t; right = r; bottom = b; }
    public void set(RectF o) { set(o.left, o.top, o.right, o.bottom); }
    public float width() { return right - left; }
    public float height() { return bottom - top; }
    public float centerX() { return (left + right) / 2; }
    public float centerY() { return (top + bottom) / 2; }
    public boolean contains(float x, float y) { return left < right && top < bottom && x >= left && x < right && y >= top && y < bottom; }
    public boolean isEmpty() { return left >= right || top >= bottom; }
    public void setEmpty() { left = top = right = bottom = 0; }
    public String toString() { return "RectF(" + left + ", " + top + ", " + right + ", " + bottom + ")"; }
}
