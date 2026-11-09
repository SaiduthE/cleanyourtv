package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * A live sample of the dock band for the opacity slider: the band over a made-up wallpaper, with a few tiles on it.
 * It colours the band with the same functions DockDecoration uses, so it shows what Home will draw in the active
 * style, and the tiles stay solid whatever the percent.
 */
final class DockBandSwatch extends View {

    private final boolean translucent;
    private final boolean lightGlass;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int percent;

    DockBandSwatch(Context ctx, boolean translucent, boolean lightGlass, int percent) {
        super(ctx);
        this.translucent = translucent;
        this.lightGlass = lightGlass;
        this.percent = percent;
    }

    void setPercent(int percent) {
        this.percent = percent;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth();
        float h = getHeight();
        // The sample wallpaper: a diagonal sweep of blue to warm, so a thin band is easy to see against it.
        paint.setShader(new LinearGradient(0, 0, w, h, 0xFF2F6FB5, 0xFFE0875A, Shader.TileMode.CLAMP));
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, 10 * d, 10 * d, paint);
        paint.setShader(null);

        float bandPad = 8 * d;
        rect.set(bandPad, h * 0.22f, w - bandPad, h - bandPad);
        float radius = 12 * d;
        if (lightGlass) {
            paint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, DockDecoration.glassTop(percent),
                    DockDecoration.glassBottom(percent), Shader.TileMode.CLAMP));
        } else {
            paint.setColor(DockDecoration.bandColor(translucent, percent));
        }
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(d);
        paint.setColor(DockDecoration.edgeColor(translucent, lightGlass, percent));
        canvas.drawRoundRect(rect, radius, radius, paint);

        // Tiles: opaque, so the picture shows the band changing and the apps not.
        paint.setStyle(Paint.Style.FILL);
        int tiles = 5;
        float gap = 8 * d;
        float inner = 8 * d;
        float tileH = rect.height() - 2 * inner;
        float tileW = (rect.width() - 2 * inner - gap * (tiles - 1)) / tiles;
        int[] colours = {0xFFE5484D, 0xFF3E63DD, 0xFF30A46C, 0xFFF5D90A, 0xFF8E4EC6};
        for (int i = 0; i < tiles; i++) {
            float l = rect.left + inner + i * (tileW + gap);
            paint.setColor(colours[i]);
            canvas.drawRoundRect(l, rect.top + inner, l + tileW, rect.top + inner + tileH, 6 * d, 6 * d, paint);
        }
    }
}
