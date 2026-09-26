package com.atakmap.android.traffic.incidents;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A changeable message sign drawn the way the sign itself looks: amber dots on a black
 * board, each character a 5 x 7 grid, scaled to fill the details pane's width.
 *
 * <p>QuickMap's KML showed signs in an LED font across the whole half-screen popup, and
 * a small text box read as a step down from that (operator, 2026-09-26). Each page of
 * the message is its own board, stacked, so both can be read at once rather than
 * waiting for the sign's own flip.
 *
 * <p>The glyphs are the classic 5 x 7 character-LCD set, uppercase only, which is also
 * all a sign can show.
 */
public class LedSignView extends View {

    private static final int COLS = 5, ROWS = 7;
    private static final int LIT = 0xFFFFB300;
    private static final int UNLIT = 0xFF2A1C00;
    private static final int BOARD = 0xFF050505;
    private static final int FRAME = 0xFF5A5A5A;

    /** Pages of up to three lines each. */
    private final List<List<String>> pages = new ArrayList<>();
    private int columns = 1;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public LedSignView(Context context) {
        super(context);
    }

    /** The sign's text as the server sends it: lines, with a blank line between pages. */
    public void setMessage(String text) {
        pages.clear();
        List<String> page = new ArrayList<>();
        for (String line : (text == null ? "" : text).split("\n", -1)) {
            final String l = line.trim().toUpperCase(Locale.US);
            if (l.isEmpty()) {
                if (!page.isEmpty()) {
                    pages.add(page);
                    page = new ArrayList<>();
                }
                continue;
            }
            page.add(l);
        }
        if (!page.isEmpty())
            pages.add(page);
        columns = 1;
        for (List<String> p : pages)
            for (String l : p)
                columns = Math.max(columns, l.length());
        // A board narrower than a real sign's line reads as a toy; most are 16 to 18.
        columns = Math.max(columns, 12);
        requestLayout();
        invalidate();
    }

    /** Dot pitch in pixels for this width: one dot and a gap per column, a blank column between characters. */
    private float pitch(int width) {
        final int dots = columns * (COLS + 1) + 3; // a dot and a half of border each side
        return width / (float) dots;
    }

    private float boardHeight(float pitch) {
        return (3 * (ROWS + 1) + 3) * pitch; // three lines always, as the sign has them
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        final int w = MeasureSpec.getSize(widthSpec);
        final float p = pitch(w);
        final float gap = p * 3;
        final int n = Math.max(1, pages.size());
        final int h = Math.round(n * boardHeight(p) + (n - 1) * gap);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        final float p = pitch(getWidth());
        final float gap = p * 3;
        float top = 0f;
        for (List<String> page : pages) {
            drawBoard(c, page, top, p);
            top += boardHeight(p) + gap;
        }
    }

    private void drawBoard(Canvas c, List<String> lines, float top, float p) {
        final float h = boardHeight(p);
        final RectF board = new RectF(0f, top, getWidth(), top + h);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(BOARD);
        c.drawRoundRect(board, p, p, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, p * 0.4f));
        paint.setColor(FRAME);
        c.drawRoundRect(board, p, p, paint);
        paint.setStyle(Paint.Style.FILL);

        final float dot = p * 0.38f;
        // Three text rows, centered vertically as a sign centers a short message.
        final int used = Math.min(3, lines.size());
        final int firstRow = (3 - used) / 2;
        for (int row = 0; row < 3; row++) {
            final String line = (row >= firstRow && row - firstRow < used)
                    ? lines.get(row - firstRow) : "";
            final int lead = (columns - line.length()) / 2; // centered, like the sign
            final float y0 = top + 1.5f * p + row * (ROWS + 1) * p;
            for (int col = 0; col < columns; col++) {
                final int ci = col - lead;
                final int[] glyph = (ci >= 0 && ci < line.length()) ? glyph(line.charAt(ci)) : BLANK;
                final float x0 = 1.5f * p + col * (COLS + 1) * p;
                for (int gy = 0; gy < ROWS; gy++)
                    for (int gx = 0; gx < COLS; gx++) {
                        final boolean on = (glyph[gy] & (1 << (COLS - 1 - gx))) != 0;
                        paint.setColor(on ? LIT : UNLIT);
                        c.drawCircle(x0 + (gx + 0.5f) * p, y0 + (gy + 0.5f) * p, dot, paint);
                    }
            }
        }
    }

    private static final int[] BLANK = new int[ROWS];
    private static final Map<Character, int[]> FONT = new HashMap<>();

    private static int[] glyph(char ch) {
        final int[] g = FONT.get(ch);
        return g != null ? g : (ch == ' ' ? BLANK : FONT.get('?'));
    }

    private static void def(char ch, String... rows) {
        final int[] g = new int[ROWS];
        for (int i = 0; i < ROWS; i++)
            g[i] = Integer.parseInt(rows[i], 2);
        FONT.put(ch, g);
    }

    static {
        def('0', "01110", "10001", "10011", "10101", "11001", "10001", "01110");
        def('1', "00100", "01100", "00100", "00100", "00100", "00100", "01110");
        def('2', "01110", "10001", "00001", "00010", "00100", "01000", "11111");
        def('3', "11111", "00010", "00100", "00010", "00001", "10001", "01110");
        def('4', "00010", "00110", "01010", "10010", "11111", "00010", "00010");
        def('5', "11111", "10000", "11110", "00001", "00001", "10001", "01110");
        def('6', "00110", "01000", "10000", "11110", "10001", "10001", "01110");
        def('7', "11111", "00001", "00010", "00100", "01000", "01000", "01000");
        def('8', "01110", "10001", "10001", "01110", "10001", "10001", "01110");
        def('9', "01110", "10001", "10001", "01111", "00001", "00010", "01100");
        def('A', "01110", "10001", "10001", "10001", "11111", "10001", "10001");
        def('B', "11110", "10001", "10001", "11110", "10001", "10001", "11110");
        def('C', "01110", "10001", "10000", "10000", "10000", "10001", "01110");
        def('D', "11100", "10010", "10001", "10001", "10001", "10010", "11100");
        def('E', "11111", "10000", "10000", "11110", "10000", "10000", "11111");
        def('F', "11111", "10000", "10000", "11110", "10000", "10000", "10000");
        def('G', "01110", "10001", "10000", "10111", "10001", "10001", "01111");
        def('H', "10001", "10001", "10001", "11111", "10001", "10001", "10001");
        def('I', "01110", "00100", "00100", "00100", "00100", "00100", "01110");
        def('J', "00111", "00010", "00010", "00010", "00010", "10010", "01100");
        def('K', "10001", "10010", "10100", "11000", "10100", "10010", "10001");
        def('L', "10000", "10000", "10000", "10000", "10000", "10000", "11111");
        def('M', "10001", "11011", "10101", "10101", "10001", "10001", "10001");
        def('N', "10001", "10001", "11001", "10101", "10011", "10001", "10001");
        def('O', "01110", "10001", "10001", "10001", "10001", "10001", "01110");
        def('P', "11110", "10001", "10001", "11110", "10000", "10000", "10000");
        def('Q', "01110", "10001", "10001", "10001", "10101", "10010", "01101");
        def('R', "11110", "10001", "10001", "11110", "10100", "10010", "10001");
        def('S', "01111", "10000", "10000", "01110", "00001", "00001", "11110");
        def('T', "11111", "00100", "00100", "00100", "00100", "00100", "00100");
        def('U', "10001", "10001", "10001", "10001", "10001", "10001", "01110");
        def('V', "10001", "10001", "10001", "10001", "10001", "01010", "00100");
        def('W', "10001", "10001", "10001", "10101", "10101", "10101", "01010");
        def('X', "10001", "10001", "01010", "00100", "01010", "10001", "10001");
        def('Y', "10001", "10001", "10001", "01010", "00100", "00100", "00100");
        def('Z', "11111", "00001", "00010", "00100", "01000", "10000", "11111");
        def(':', "00000", "01100", "01100", "00000", "01100", "01100", "00000");
        def('-', "00000", "00000", "00000", "11111", "00000", "00000", "00000");
        def('.', "00000", "00000", "00000", "00000", "00000", "01100", "01100");
        def(',', "00000", "00000", "00000", "00000", "01100", "00100", "01000");
        def('/', "00000", "00001", "00010", "00100", "01000", "10000", "00000");
        def('\'', "01100", "00100", "01000", "00000", "00000", "00000", "00000");
        def('"', "01010", "01010", "01010", "00000", "00000", "00000", "00000");
        def('!', "00100", "00100", "00100", "00100", "00100", "00000", "00100");
        def('?', "01110", "10001", "00001", "00010", "00100", "00000", "00100");
        def('&', "01100", "10010", "10100", "01000", "10101", "10010", "01101");
        def('(', "00010", "00100", "01000", "01000", "01000", "00100", "00010");
        def(')', "01000", "00100", "00010", "00010", "00010", "00100", "01000");
        def('+', "00000", "00100", "00100", "11111", "00100", "00100", "00000");
        def('=', "00000", "00000", "11111", "00000", "11111", "00000", "00000");
        def('@', "01110", "10001", "00001", "01101", "10101", "10101", "01110");
        def('#', "01010", "01010", "11111", "01010", "11111", "01010", "01010");
        def('%', "11000", "11001", "00010", "00100", "01000", "10011", "00011");
        def('*', "00000", "00100", "10101", "01110", "10101", "00100", "00000");
        def('<', "00010", "00100", "01000", "10000", "01000", "00100", "00010");
        def('>', "01000", "00100", "00010", "00001", "00010", "00100", "01000");
    }
}
