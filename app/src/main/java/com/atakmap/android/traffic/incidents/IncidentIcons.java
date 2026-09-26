package com.atakmap.android.traffic.incidents;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * One map icon per kind of incident: a symbol on a dark disc with a white edge, the
 * size and shape of Atmosphere's weather-station disc so the two plugins read as one
 * family on the map.
 *
 * <p>The symbols follow Caltrans QuickMap's vocabulary, which is what California
 * crews already read (operator, 2026-09-26): a cone for road work, a barricade gate
 * for closed lanes, and here a red two-board barricade for a road closed outright.
 * They are our own drawings of those standard devices, not QuickMap's files.
 *
 * <p>An icon file rather than stacked point styles: a {@code CompositeStyle} of two
 * {@code BasicPointStyle}s draws only the first (s10-dev-2, 2026-09-26). Composed on
 * the worker that rewrites the store, never on main. The version and size are in the
 * file name, so a changed look is a new file rather than a stale one reused.
 */
final class IncidentIcons {

    private static final String TAG = "Traffic511";
    private static final int VERSION = 7;

    /**
     * The disc's radius in device-independent units, scaled by ATAK's own display
     * scaling: Atmosphere's weather-station disc exactly. 44 px unscaled read too big
     * beside it (operator, 2026-09-26).
     */
    private static final float DISC_R = 17f;
    private static final float SCALE = Math.max(1f,
            gov.tak.api.commons.graphics.DisplaySettings.getRelativeScaling());
    /** The bitmap's edge in pixels: drawn at the screen's density, so it stays sharp. */
    static final int SIZE = Math.round(2f * DISC_R * SCALE) + 2;
    /**
     * The size the style asks for, in device-independent units. ATAK's renderer
     * multiplies an icon style's width and height by the display density itself
     * (GLGeometryBatchBuilder), so passing SIZE drew every icon scaled twice -- bigger
     * than the 44 px it replaced. Atmosphere divides by its scale for the same reason.
     */
    static final int DP = Math.round(2f * DISC_R) + 2;
    /** How much of the disc the symbol spans; Atmosphere's glyphs fill theirs. */
    private static final float GLYPH_FILL = 0.80f;

    private static final int DISC = 0xFF202124;
    private static final int EDGE = 0xFFFFFFFF;
    private static final int LIGHT = 0xFFE8E8E8;
    private static final int RED = 0xFFFF3B30;
    private static final int ORANGE = 0xFFFF7A00;
    private static final int YELLOW = 0xFFFFD60A;
    private static final int AMBER = 0xFFFFB300;
    private static final int BLUE = 0xFF4FC3F7;

    private final File dir = FileSystemUtils.getItem("tools/traffic/511/icons");
    private final Map<String, String> cache = new HashMap<>();

    /** A {@code file://} uri for this kind's icon, or null when it could not be written. */
    String uri(String kind) {
        final String hit = cache.get(kind);
        if (hit != null)
            return hit;
        if (!dir.isDirectory() && !dir.mkdirs())
            return null;
        final File out = new File(dir, kind + "_" + SIZE + "_v" + VERSION + ".png");
        if (!out.isFile() && !write(out, kind, SIZE))
            return null;
        final String uri = "file://" + out.getAbsolutePath();
        cache.put(kind, uri);
        return uri;
    }

    /**
     * Every kind side by side at a readable size, for reviewing the set off the phone
     * ({@code adb pull}). Debug builds only; nothing reads it.
     */
    void writePreview() {
        final int cell = 128;
        final String[] kinds = IncidentStyles.KINDS;
        Bitmap sheet = null;
        try {
            sheet = Bitmap.createBitmap(cell * kinds.length, cell, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(sheet);
            c.drawColor(0xFF6B7B5A); // a basemap-ish olive, not the disc's own dark
            for (int i = 0; i < kinds.length; i++) {
                c.save();
                c.translate(i * cell, 0);
                draw(c, kinds[i], cell);
                c.restore();
            }
            if (!dir.isDirectory() && !dir.mkdirs())
                return;
            save(sheet, new File(dir, "preview_v" + VERSION + ".png"));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "preview not written", e);
        } finally {
            if (sheet != null)
                sheet.recycle();
        }
    }

    /** This kind's icon as a bitmap of this many pixels, for a control in the pane. */
    static Bitmap bitmap(String kind, int px) {
        final Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        draw(new Canvas(bmp), kind, px);
        return bmp;
    }

    private static boolean write(File out, String kind, int size) {
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            draw(new Canvas(bmp), kind, size);
            save(bmp, out);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "icon " + kind + " not written", e);
            return false;
        } finally {
            if (bmp != null)
                bmp.recycle();
        }
    }

    private static void save(Bitmap bmp, File out) throws IOException {
        final File tmp = new File(out.getPath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        if (!tmp.renameTo(out))
            throw new IOException("rename failed");
    }

    /** The disc and its symbol, filling a square of this many pixels. */
    private static void draw(Canvas c, String kind, int size) {
        final float cx = size / 2f, cy = size / 2f;
        final float edge = Math.max(1.5f, size / (2f * DISC_R + 2f) * 2f);
        final float r = size / 2f - 1f;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(DISC);
        c.drawCircle(cx, cy, r, p);

        // The symbol never spills over the ring.
        c.save();
        final Path inside = new Path();
        inside.addCircle(cx, cy, r - edge, Path.Direction.CW);
        c.clipPath(inside);
        c.translate(cx, cy);
        symbol(c, kind, r * GLYPH_FILL);
        c.restore();

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(edge);
        p.setColor(EDGE);
        c.drawCircle(cx, cy, r - edge / 2f, p);
    }

    /** Drawn about the origin; g is the symbol's half extent. */
    private static void symbol(Canvas c, String kind, float g) {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        switch (kind) {
            case Incident.CRASH:
                crash(c, p, g);
                break;
            case Incident.CLOSURE:
                barricade(c, p, g, RED, true);
                break;
            case Incident.LANES:
                laneClosed(c, p, g);
                break;
            case Incident.ROADWORK:
                cone(c, p, g);
                break;
            case Incident.HAZARD:
                hazard(c, p, g);
                break;
            case Incident.FIRE:
                carFire(c, p, g);
                break;
            case Incident.CHAINS:
                tire(c, p, g);
                break;
            case Incident.WEATHER:
                cloud(c, p, g);
                break;
            case Incident.SIGN:
                sign(c, p, g, AMBER);
                break;
            default:
                info(c, p, g);
                break;
        }
    }

    /** Two cars nose to nose, tipped up where they meet. */
    private static void crash(Canvas c, Paint p, float g) {
        final float len = g * 1.12f;
        c.save();
        c.translate(-0.02f * g, 0.50f * g);
        c.rotate(-11f);
        car(c, p, len, LIGHT);
        c.restore();
        c.save();
        c.translate(0.02f * g, 0.50f * g);
        c.scale(-1f, 1f);
        c.rotate(-11f);
        car(c, p, len, RED);
        c.restore();
        star(c, p, 0f, -0.50f * g, 0.36f * g, YELLOW);
    }

    /** A car facing right, its front bumper's foot at the origin, extending left. */
    private static void car(Canvas c, Paint p, float l, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawRoundRect(new RectF(-l, -0.40f * l, 0f, -0.10f * l), 0.09f * l, 0.09f * l, p);
        final Path cabin = new Path();
        cabin.moveTo(-0.80f * l, -0.38f * l);
        cabin.lineTo(-0.63f * l, -0.66f * l);
        cabin.lineTo(-0.30f * l, -0.66f * l);
        cabin.lineTo(-0.14f * l, -0.38f * l);
        cabin.close();
        c.drawPath(cabin, p);
        // A window cut through to the disc, which is what makes it read as a car.
        final Path glass = new Path();
        glass.moveTo(-0.70f * l, -0.42f * l);
        glass.lineTo(-0.59f * l, -0.59f * l);
        glass.lineTo(-0.34f * l, -0.59f * l);
        glass.lineTo(-0.23f * l, -0.42f * l);
        glass.close();
        p.setColor(DISC);
        c.drawPath(glass, p);
        for (float x : new float[] { -0.76f * l, -0.24f * l }) {
            p.setColor(DISC);
            c.drawCircle(x, -0.10f * l, 0.15f * l, p);
            p.setColor(color);
            c.drawCircle(x, -0.10f * l, 0.07f * l, p);
        }
    }

    private static void star(Canvas c, Paint p, float x, float y, float r, int color) {
        final Path s = new Path();
        final int points = 8;
        for (int i = 0; i < points * 2; i++) {
            final double a = Math.PI * i / points - Math.PI / 2;
            final float rr = (i % 2 == 0) ? r : r * 0.45f;
            final float px = x + (float) (Math.cos(a) * rr);
            final float py = y + (float) (Math.sin(a) * rr);
            if (i == 0)
                s.moveTo(px, py);
            else
                s.lineTo(px, py);
        }
        s.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(s, p);
    }

    /**
     * A barricade gate: two posts and striped boards. Road closed gets two boards in
     * red, lanes closed one board in orange, so the difference survives a glance and a
     * color-blind reader both.
     */
    private static void barricade(Canvas c, Paint p, float g, int stripe, boolean closed) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(LIGHT);
        for (float x : new float[] { -0.60f * g, 0.60f * g }) {
            c.drawRect(x - 0.08f * g, -0.70f * g, x + 0.08f * g, 0.70f * g, p);
            c.drawRoundRect(new RectF(x - 0.24f * g, 0.62f * g, x + 0.24f * g, 0.78f * g),
                    0.05f * g, 0.05f * g, p);
        }
        if (closed) {
            board(c, p, g, -0.56f * g, -0.14f * g, stripe);
            board(c, p, g, 0.04f * g, 0.46f * g, stripe);
        } else {
            board(c, p, g, -0.30f * g, 0.16f * g, stripe);
        }
    }

    private static void board(Canvas c, Paint p, float g, float top, float bottom, int stripe) {
        final RectF b = new RectF(-0.95f * g, top, 0.95f * g, bottom);
        p.setColor(0xFFFFFFFF);
        c.drawRect(b, p);
        c.save();
        c.clipRect(b);
        p.setColor(stripe);
        final float h = bottom - top;
        final float w = 0.22f * g;
        for (float x = -1.6f * g; x < 1.6f * g; x += 2f * w) {
            final Path band = new Path();
            band.moveTo(x, bottom);
            band.lineTo(x + w, bottom);
            band.lineTo(x + w + h, top);
            band.lineTo(x + h, top);
            band.close();
            c.drawPath(band, p);
        }
        c.restore();
    }

    private static void cone(Canvas c, Paint p, float g) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(ORANGE);
        c.drawRoundRect(new RectF(-0.80f * g, 0.60f * g, 0.80f * g, 0.80f * g),
                0.06f * g, 0.06f * g, p);
        final Path body = new Path();
        body.moveTo(-0.13f * g, -0.88f * g);
        body.lineTo(0.13f * g, -0.88f * g);
        body.lineTo(0.54f * g, 0.62f * g);
        body.lineTo(-0.54f * g, 0.62f * g);
        body.close();
        c.drawPath(body, p);
        c.save();
        c.clipPath(body);
        p.setColor(0xFFFFFFFF);
        c.drawRect(-g, -0.42f * g, g, -0.18f * g, p);
        c.drawRect(-g, 0.08f * g, g, 0.34f * g, p);
        c.restore();
    }

    private static void hazard(Canvas c, Paint p, float g) {
        final Path tri = new Path();
        tri.moveTo(0f, -0.86f * g);
        tri.lineTo(0.96f * g, 0.74f * g);
        tri.lineTo(-0.96f * g, 0.74f * g);
        tri.close();
        p.setColor(YELLOW);
        p.setStyle(Paint.Style.FILL_AND_STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(0.14f * g);
        c.drawPath(tri, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF111111);
        c.drawRoundRect(new RectF(-0.09f * g, -0.38f * g, 0.09f * g, 0.28f * g),
                0.06f * g, 0.06f * g, p);
        c.drawCircle(0f, 0.50f * g, 0.10f * g, p);
    }

    /**
     * A road seen from the driver's seat, two lanes, the right one closed with the red
     * X of a lane-control signal and the left one open. A one-board gate beside the
     * two-board road-closed barricade was too alike at map size (operator, 2026-09-26:
     * "something with a lane in it").
     */
    private static void laneClosed(Canvas c, Paint p, float g) {
        final float top = -1.05f * g, bottom = 1.05f * g;
        final Path road = new Path();
        road.moveTo(-0.34f * g, top);
        road.lineTo(0.34f * g, top);
        road.lineTo(1.00f * g, bottom);
        road.lineTo(-1.00f * g, bottom);
        road.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF4A4A4A);
        c.drawPath(road, p);
        // Edge lines, and the dashed line between the lanes.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setColor(0xFFFFFFFF);
        p.setStrokeWidth(0.07f * g);
        c.drawLine(-0.30f * g, top, -0.90f * g, bottom, p);
        c.drawLine(0.30f * g, top, 0.90f * g, bottom, p);
        p.setStrokeWidth(0.06f * g);
        for (float y = -0.95f * g; y < bottom; y += 0.42f * g)
            c.drawLine(0f, y, 0f, y + 0.20f * g, p);
        // The open lane: an arrow straight on.
        p.setStyle(Paint.Style.FILL);
        final Path arrow = new Path();
        final float ax = -0.36f * g;
        arrow.moveTo(ax, -0.28f * g);
        arrow.lineTo(ax + 0.20f * g, 0.02f * g);
        arrow.lineTo(ax + 0.07f * g, 0.02f * g);
        arrow.lineTo(ax + 0.07f * g, 0.52f * g);
        arrow.lineTo(ax - 0.07f * g, 0.52f * g);
        arrow.lineTo(ax - 0.07f * g, 0.02f * g);
        arrow.lineTo(ax - 0.20f * g, 0.02f * g);
        arrow.close();
        c.drawPath(arrow, p);
        // The closed lane: the red X, on a dark square so it reads on the grey.
        final float xx = 0.40f * g, xy = 0.14f * g, xr = 0.26f * g;
        p.setColor(0xFF111111);
        c.drawRoundRect(new RectF(xx - xr - 0.07f * g, xy - xr - 0.07f * g,
                xx + xr + 0.07f * g, xy + xr + 0.07f * g), 0.06f * g, 0.06f * g, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(0.13f * g);
        p.setColor(RED);
        c.drawLine(xx - xr, xy - xr, xx + xr, xy + xr, p);
        c.drawLine(xx - xr, xy + xr, xx + xr, xy - xr, p);
    }

    /** A car with flames coming off it. */
    private static void carFire(Canvas c, Paint p, float g) {
        final float len = 1.56f * g;
        c.save();
        c.translate(0.78f * g, 0.82f * g);
        car(c, p, len, LIGHT);
        c.restore();
        // Three tongues rising off the cabin and hood, tallest in the middle.
        drop(c, p, -0.44f * g, 0.02f * g, 0.28f * g, ORANGE);
        drop(c, p, 0.36f * g, 0.06f * g, 0.30f * g, ORANGE);
        drop(c, p, -0.04f * g, 0.04f * g, 0.46f * g, ORANGE);
        drop(c, p, -0.04f * g, 0.04f * g, 0.24f * g, YELLOW);
    }

    /** One flame tongue, base centered on (cx, baseY), about 1.8 s tall. */
    private static void drop(Canvas c, Paint p, float cx, float baseY, float s, int color) {
        final Path d = new Path();
        d.moveTo(cx, baseY - 1.82f * s);
        d.cubicTo(cx + 0.55f * s, baseY - 1.25f * s, cx + 0.80f * s, baseY - 0.85f * s,
                cx + 0.66f * s, baseY - 0.48f * s);
        d.cubicTo(cx + 0.56f * s, baseY - 0.16f * s, cx + 0.30f * s, baseY, cx, baseY);
        d.cubicTo(cx - 0.30f * s, baseY, cx - 0.56f * s, baseY - 0.16f * s,
                cx - 0.66f * s, baseY - 0.48f * s);
        d.cubicTo(cx - 0.80f * s, baseY - 0.85f * s, cx - 0.55f * s, baseY - 1.25f * s,
                cx, baseY - 1.82f * s);
        d.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(d, p);
    }

    /**
     * A tire seen at an angle, chains across its tread: QuickMap's chain-control mark.
     * Face-on, any chain pattern round the tread read as a fan or a star
     * (s10-dev-2, 2026-09-26); at an angle the tread is a band the chain can cross.
     */
    private static void tire(Canvas c, Paint p, float g) {
        final float rx = 0.50f * g, ry = 0.86f * g;
        final float back = -0.30f * g, front = 0.26f * g;
        // The tread: the far sidewall's ellipse, joined to the near one by a band.
        final Path tread = new Path();
        tread.addOval(new RectF(back - rx, -ry, back + rx, ry), Path.Direction.CW);
        tread.addRect(back, -ry, front, ry, Path.Direction.CW);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF6E6E6E);
        c.drawPath(tread, p);
        c.save();
        c.clipPath(tread);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(LIGHT);
        p.setStrokeWidth(0.09f * g);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (float y = -1.2f * g; y < 1.2f * g; y += 0.36f * g) {
            c.drawLine(back - rx, y, front, y + 0.36f * g, p);
            c.drawLine(back - rx, y + 0.36f * g, front, y, p);
        }
        c.restore();
        // The near sidewall and wheel, over the tread.
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF9A9A9A);
        c.drawOval(new RectF(front - rx, -ry, front + rx, ry), p);
        p.setColor(LIGHT);
        c.drawOval(new RectF(front - 0.26f * g, -0.44f * g, front + 0.26f * g, 0.44f * g), p);
        p.setColor(DISC);
        c.drawOval(new RectF(front - 0.09f * g, -0.15f * g, front + 0.09f * g, 0.15f * g), p);
    }

    private static void cloud(Canvas c, Paint p, float g) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(LIGHT);
        c.drawCircle(-0.40f * g, -0.10f * g, 0.34f * g, p);
        c.drawCircle(0.04f * g, -0.34f * g, 0.44f * g, p);
        c.drawCircle(0.46f * g, -0.08f * g, 0.32f * g, p);
        c.drawRoundRect(new RectF(-0.74f * g, -0.10f * g, 0.78f * g, 0.24f * g),
                0.17f * g, 0.17f * g, p);
        p.setColor(BLUE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(0.13f * g);
        for (float x : new float[] { -0.38f * g, 0.02f * g, 0.42f * g })
            c.drawLine(x + 0.08f * g, 0.44f * g, x - 0.06f * g, 0.78f * g, p);
    }

    /** A dark board of amber dots on two legs: a changeable message sign. */
    private static void sign(Canvas c, Paint p, float g, int dots) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(LIGHT);
        c.drawRect(-0.42f * g, 0.30f * g, -0.30f * g, 0.90f * g, p);
        c.drawRect(0.30f * g, 0.30f * g, 0.42f * g, 0.90f * g, p);
        final RectF board = new RectF(-0.96f * g, -0.68f * g, 0.96f * g, 0.36f * g);
        p.setColor(0xFF050505);
        c.drawRoundRect(board, 0.08f * g, 0.08f * g, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(0.08f * g);
        p.setColor(0xFFB0B0B0);
        c.drawRoundRect(board, 0.08f * g, 0.08f * g, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(dots);
        // Three rows of lit dots with gaps, so it reads as words rather than a grid.
        final boolean[][] lit = {
                { true, true, true, false, true, true, true },
                { true, true, false, true, true, true, true },
                { true, true, true, true, false, true, false },
        };
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 7; col++)
                if (lit[row][col])
                    c.drawCircle((-0.66f + col * 0.22f) * g, (-0.42f + row * 0.25f) * g,
                            0.075f * g, p);
    }

    private static void info(Canvas c, Paint p, float g) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(BLUE);
        c.drawCircle(0f, -0.56f * g, 0.15f * g, p);
        c.drawRoundRect(new RectF(-0.13f * g, -0.28f * g, 0.13f * g, 0.70f * g),
                0.06f * g, 0.06f * g, p);
        c.drawRoundRect(new RectF(-0.30f * g, 0.58f * g, 0.30f * g, 0.74f * g),
                0.05f * g, 0.05f * g, p);
    }
}
