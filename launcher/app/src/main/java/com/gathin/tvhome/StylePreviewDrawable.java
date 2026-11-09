package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * A small picture of a style's Home, drawn in code on a 64 x 36 dp canvas and scaled to its bounds: the style's
 * wallpaper gradient, a clock bar, the dock band when the style has one, and a row of tiles.
 */
final class StylePreviewDrawable extends Drawable {

    static final int WIDTH_DP = 64;
    static final int HEIGHT_DP = 36;

    private final int[] colors;
    private final float[] stops;
    private final float angle;
    private final int tiles;
    private final boolean band;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();

    /** The preview of a style by its id ("cinematic", "glass", "aurora", "minimal", "six-columns"); null is the built-in look. */
    StylePreviewDrawable(String id) {
        int[] c;
        float[] s = null;
        float a = 135f;
        int t = 6;
        boolean b = false;
        if ("cinematic".equals(id)) {
            c = new int[] {0xFF221F5E, 0xFF0E4C48};
            b = true;
        } else if ("glass".equals(id)) {
            c = new int[] {0xFF0B1635, 0xFF1D6A78};
            t = 5;
            b = true;
        } else if ("aurora".equals(id)) {
            c = new int[] {0xFF0A1024, 0xFF1F6A55, 0xFF3A2C66};
            s = new float[] {0.2f, 0.5f, 1f};
            a = 160f;
            t = 5;
            b = true;
        } else if ("minimal".equals(id)) {
            c = new int[] {0xFF2A2C31, 0xFF2A2C31};
            t = 5;
        } else if ("six-columns".equals(id)) {
            c = new int[] {0xFF000000, 0xFF000000};
        } else {
            c = new int[] {0xFF15181D, 0xFF15181D};
        }
        colors = c;
        stops = s;
        angle = a;
        tiles = t;
        band = b;
    }

    /** CSS-style linear-gradient(angle): the gradient line's end points across a w x h box. */
    static float[] gradientLine(float angleDeg, float w, float h) {
        double rad = Math.toRadians(angleDeg);
        double dx = Math.sin(rad), dy = -Math.cos(rad);
        double half = (Math.abs(w * dx) + Math.abs(h * dy)) / 2.0;
        double cx = w / 2.0, cy = h / 2.0;
        return new float[] {(float) (cx - dx * half), (float) (cy - dy * half),
                (float) (cx + dx * half), (float) (cy + dy * half)};
    }

    @Override
    public void draw(Canvas canvas) {
        android.graphics.Rect b = getBounds();
        if (b.isEmpty()) return;
        int save = canvas.save();
        canvas.translate(b.left, b.top);
        canvas.scale(b.width() / (float) WIDTH_DP, b.height() / (float) HEIGHT_DP);
        float w = WIDTH_DP, h = HEIGHT_DP;

        rect.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(rect, 6, 6, Path.Direction.CW);
        canvas.clipPath(clip);

        float[] line = gradientLine(angle, w, h);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(line[0], line[1], line[2], line[3], colors, stops, Shader.TileMode.CLAMP));
        canvas.drawRect(rect, paint);
        paint.setShader(null);

        paint.setColor(0xB3FFFFFF);
        rect.set(6, 5, 20, 8);
        canvas.drawRoundRect(rect, 1, 1, paint);

        if (band) {
            paint.setColor(0x24FFFFFF);
            rect.set(5, h - 6 - 10, w - 5, h - 6);
            canvas.drawRoundRect(rect, 4, 4, paint);
        }

        paint.setColor(0xBFFFFFFF);
        float gap = 2f;
        float tileW = (w - 16 - gap * (tiles - 1)) / tiles;
        for (int i = 0; i < tiles; i++) {
            float x = 8 + i * (tileW + gap);
            rect.set(x, h - 8 - 6, x + tileW, h - 8);
            canvas.drawRect(rect, paint);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(0x29FFFFFF);
        rect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f);
        canvas.drawRoundRect(rect, 5.5f, 5.5f, paint);
        canvas.restoreToCount(save);
    }

    @Override
    public int getIntrinsicWidth() {
        return -1;
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
