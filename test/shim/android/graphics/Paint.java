package android.graphics;

import java.awt.Font;
import java.awt.font.FontRenderContext;

/** Desktop stand-in for android.graphics.Paint (tests only). */
public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1;
    public enum Style { FILL, STROKE, FILL_AND_STROKE }
    public enum Align { LEFT, CENTER, RIGHT }
    public enum Cap { BUTT, ROUND, SQUARE }
    public enum Join { MITER, ROUND, BEVEL }

    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    private int color = 0xFF000000;
    private Style style = Style.FILL;
    private float strokeWidth = 1, textSize = 12, letterSpacing;
    private Typeface typeface = Typeface.DEFAULT;
    private Align align = Align.LEFT;
    Cap cap = Cap.BUTT;

    public Paint() { }
    public Paint(int flags) { }

    public void setColor(int c) { color = c; }
    public int getColor() { return color; }
    public void setStyle(Style s) { style = s; }
    public Style getStyle() { return style; }
    public void setStrokeWidth(float w) { strokeWidth = w; }
    public float getStrokeWidth() { return strokeWidth; }
    public void setStrokeCap(Cap c) { cap = c; }
    public void setStrokeJoin(Join j) { }
    public void setTextSize(float s) { textSize = s; }
    public float getTextSize() { return textSize; }
    public void setTypeface(Typeface t) { typeface = t == null ? Typeface.DEFAULT : t; }
    public void setTextAlign(Align a) { align = a; }
    public Align getTextAlign() { return align; }
    public void setLetterSpacing(float s) { letterSpacing = s; }
    public Shader setShader(Shader s) { return s; }

    Font font() { return new Font(typeface.family, typeface.awtStyle, 1).deriveFont(textSize); }

    public float measureText(String s) {
        return (float) font().getStringBounds(s, FRC).getWidth() + letterSpacing * textSize * s.length();
    }

    public float measureText(CharSequence s, int start, int end) { return measureText(s.subSequence(start, end).toString()); }
}
