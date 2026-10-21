package android.graphics;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayDeque;

/** Desktop stand-in for android.graphics.Canvas, drawing with Java2D (tests only). */
public class Canvas {
    private final Graphics2D g;
    private final ArrayDeque<Object[]> stack = new ArrayDeque<>();

    public Canvas(Graphics2D g) {
        this.g = g;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    public int save() { stack.push(new Object[]{g.getTransform(), g.getClip()}); return stack.size(); }

    public void restore() {
        Object[] s = stack.pop();
        g.setTransform((java.awt.geom.AffineTransform) s[0]);
        g.setClip((Shape) s[1]);
    }

    public boolean clipRect(float l, float t, float r, float b) { g.clip(new Rectangle2D.Float(l, t, r - l, b - t)); return true; }
    public boolean clipRect(RectF r) { return clipRect(r.left, r.top, r.right, r.bottom); }
    public void translate(float x, float y) { g.translate(x, y); }

    private void paint(Paint p, Shape s) {
        g.setColor(new Color(p.getColor(), true));
        if (p.getStyle() == Paint.Style.STROKE) {
            int cap = p.cap == Paint.Cap.ROUND ? BasicStroke.CAP_ROUND : p.cap == Paint.Cap.SQUARE ? BasicStroke.CAP_SQUARE : BasicStroke.CAP_BUTT;
            g.setStroke(new BasicStroke(Math.max(0.5f, p.getStrokeWidth()), cap, BasicStroke.JOIN_ROUND));
            g.draw(s);
        } else {
            g.fill(s);
        }
    }

    public void drawRect(float l, float t, float r, float b, Paint p) { paint(p, new Rectangle2D.Float(l, t, r - l, b - t)); }
    public void drawRect(RectF r, Paint p) { drawRect(r.left, r.top, r.right, r.bottom, p); }
    public void drawRoundRect(RectF r, float rx, float ry, Paint p) { drawRoundRect(r.left, r.top, r.right, r.bottom, rx, ry, p); }
    public void drawRoundRect(float l, float t, float r, float b, float rx, float ry, Paint p) {
        paint(p, new RoundRectangle2D.Float(l, t, r - l, b - t, 2 * rx, 2 * ry));
    }
    public void drawOval(RectF r, Paint p) { paint(p, new Ellipse2D.Float(r.left, r.top, r.width(), r.height())); }
    public void drawCircle(float cx, float cy, float rad, Paint p) { paint(p, new Ellipse2D.Float(cx - rad, cy - rad, 2 * rad, 2 * rad)); }
    public void drawLine(float x0, float y0, float x1, float y1, Paint p) {
        Paint.Style st = p.getStyle();
        p.setStyle(Paint.Style.STROKE);
        paint(p, new Line2D.Float(x0, y0, x1, y1));
        p.setStyle(st);
    }
    public void drawArc(RectF r, float start, float sweep, boolean useCenter, Paint p) {
        paint(p, new Arc2D.Float(r.left, r.top, r.width(), r.height(), -start, -sweep, useCenter ? Arc2D.PIE : Arc2D.OPEN));
    }
    public void drawPath(Path path, Paint p) { paint(p, path.shape()); }
    public void drawText(String s, float x, float y, Paint p) { drawText(s, 0, s.length(), x, y, p); }
    public void drawText(CharSequence s, int start, int end, float x, float y, Paint p) {
        String str = s.subSequence(start, end).toString();
        g.setFont(p.font());
        g.setColor(new Color(p.getColor(), true));
        float w = p.measureText(str);
        if (p.getTextAlign() == Paint.Align.CENTER) x -= w / 2;
        else if (p.getTextAlign() == Paint.Align.RIGHT) x -= w;
        g.drawString(str, x, y);
    }
}
