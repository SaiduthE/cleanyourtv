package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * The rows fade out as they scroll away under the top edge, and gently at the bottom edge while more rows follow,
 * instead of being cut off at a hard line (fade_rows, on by default). It erases the edges of the grid's own picture,
 * so whatever is behind Home (a wallpaper, the sky) shows through; the grid draws into a hardware layer for that
 * ({@link #attach}), or the erasing would punch through the whole window. Android's own requiresFadingEdge draws its
 * fade at the padding line, not the edge, with this grid's clipToPadding=false, so it is not used. Each fade grows in
 * over the first pixels of scrolling, so the dock at rest is untouched, and shrinks out again as the end comes near.
 * The gradient is one shader for the whole life of the grid, scaled to the strength each frame: nothing is allocated
 * while scrolling.
 */
final class TopFade extends RecyclerView.ItemDecoration {

    /** Erased fraction at 0, 20, 40, 60, 80 and 100 percent of the way in from the edge: a smoothstep, so no visible band. */
    private static final float[] ERASE = {1f, 0.896f, 0.648f, 0.352f, 0.104f, 0f};
    private static final float[] AT = {0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f};

    /** The fades' full heights in dp: a soft, long top edge and a gentler bottom one (the grid's bottom padding is 40 dp). */
    static final int TOP_DP = 80, BOTTOM_DP = 36;

    private final float topLength, bottomLength;
    private final Paint paint = new Paint();

    private TopFade(float topLength, float bottomLength) {
        this.topLength = topLength;
        this.bottomLength = bottomLength;
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        int[] colors = new int[ERASE.length];
        for (int i = 0; i < colors.length; i++) colors[i] = Math.round(ERASE[i] * 255f) << 24;
        // One unit tall: drawn through a canvas scale of the fade's real height.
        paint.setShader(new LinearGradient(0f, 0f, 0f, 1f, colors, AT, Shader.TileMode.CLAMP));
    }

    /** Fades the grid's top edge by up to {@code top} pixels and its bottom edge by up to {@code bottom}; the grid draws into a hardware layer. */
    static void attach(RecyclerView grid, float top, float bottom) {
        grid.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        grid.addItemDecoration(new TopFade(top, bottom));
    }

    /** How tall the top fade is after scrolling {@code scrolled} pixels: nothing at rest, then up to {@code length}. */
    static float height(int scrolled, float length) {
        return Math.max(0f, Math.min(length, scrolled));
    }

    /** How tall the bottom fade is with {@code remaining} pixels of rows still below the screen: none at the end. */
    static float bottomHeight(int remaining, float length) {
        return height(remaining, length);
    }

    @Override
    public void onDrawOver(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int offset = parent.computeVerticalScrollOffset();
        float top = height(offset, topLength);
        float bottom = bottomHeight(parent.computeVerticalScrollRange() - offset - parent.computeVerticalScrollExtent(),
                bottomLength);
        if (top > 0f) {
            c.save();
            c.scale(1f, top);
            c.drawRect(0f, 0f, parent.getWidth(), 1f, paint);
            c.restore();
        }
        if (bottom > 0f) {
            c.save();
            c.translate(0f, parent.getHeight());
            c.scale(1f, -bottom);   // the same gradient, flipped: erased at the very bottom
            c.drawRect(0f, 0f, parent.getWidth(), 1f, paint);
            c.restore();
        }
    }
}
