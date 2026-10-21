package android.text;

import android.graphics.Canvas;
import java.util.ArrayList;
import java.util.List;

/** Desktop stand-in: greedy word wrap (tests only). */
public class StaticLayout {
    private final List<String> lines = new ArrayList<>();
    private final TextPaint paint;
    private final float lineH;
    private final int color;
    private final float size;

    private StaticLayout(String s, TextPaint p, int width, float extra) {
        paint = p;
        color = p.getColor();
        size = p.getTextSize();
        lineH = p.getTextSize() * 1.17f + extra;
        for (String para : s.split("\n", -1)) {
            String line = "";
            for (String w : para.split(" ")) {
                String cand = line.isEmpty() ? w : line + " " + w;
                if (p.measureText(cand) > width && !line.isEmpty()) { lines.add(line); line = w; }
                else line = cand;
            }
            lines.add(line);
        }
    }

    public int getHeight() { return Math.round(lines.size() * lineH); }

    public void draw(Canvas c) {
        TextPaint p = new TextPaint();
        p.setTextSize(size);
        p.setColor(color);
        for (int i = 0; i < lines.size(); i++) c.drawText(lines.get(i), 0, (i + 0.8f) * lineH, p);
    }

    public static final class Builder {
        private final String s;
        private final TextPaint p;
        private final int width;
        private float extra;

        private Builder(CharSequence s, TextPaint p, int width) { this.s = s.toString(); this.p = p; this.width = width; }

        public static Builder obtain(CharSequence s, int start, int end, TextPaint p, int width) {
            return new Builder(s.subSequence(start, end), p, width);
        }

        public Builder setLineSpacing(float add, float mult) { extra = add; return this; }
        public Builder setAlignment(Layout.Alignment a) { return this; }
        public StaticLayout build() { return new StaticLayout(s, p, width, extra); }
    }
}
