package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Home's on-screen cue while apps are being reordered or the dock is being edited: a glass pill at the top centre
 * ("Moving Plex" and the keys), and, once an app is picked up, small arrows beside it for the directions it can go.
 * MainActivity reports each step; the carried tile's lift, rim and the dimming of the others are drawn there. Nothing
 * here moves while it is shown, and it must never take focus: whatever it shows is not focusable, so the remote
 * keeps working on the tiles.
 */
final class ReorderIndicator {

    private final Activity host;
    private boolean animations, reducedMotion;
    private LinearLayout pill;
    private View dot;
    private TextView title, hint;
    private Arrows arrows;
    private boolean dockEdit, slotChosen, active;
    private String carried;

    ReorderIndicator(Activity host) {
        this.host = host;
    }

    /** Builds the pill and the arrows over Home. Called once, after the layout exists. */
    void attach(FrameLayout root, boolean animations, boolean reducedMotion) {
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        float d = host.getResources().getDisplayMetrics().density;
        pill = new LinearLayout(host);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(Math.round(14 * d), 0, Math.round(18 * d), 0);
        pill.setElevation(8 * d);
        pill.setVisibility(View.GONE);
        pill.setFocusable(false);
        pill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Fading draws the pill with the alpha instead of rendering it into a buffer first.
        pill.forceHasOverlappingRendering(false);
        dot = new View(host);
        title = label(15, R.string.font_medium, 0xFFFFFFFF);
        hint = label(14, R.string.font_regular, 0x99FFFFFF);
        pill.addView(dot, new LinearLayout.LayoutParams(Math.round(8 * d), Math.round(8 * d)));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-2, -2);
        titleLp.setMarginStart(Math.round(10 * d));
        pill.addView(title, titleLp);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-2, -2);
        hintLp.setMarginStart(Math.round(10 * d));
        pill.addView(hint, hintLp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, Math.round(34 * d), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = Math.round(14 * d);
        root.addView(pill, lp);

        arrows = new Arrows(host);
        arrows.setVisibility(View.GONE);
        root.addView(arrows, new FrameLayout.LayoutParams(-1, -1));
    }

    private TextView label(int sp, int font, int color) {
        TextView t = new TextView(host);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(UiFont.get(host, font));
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Reorder mode started; dockEdit is true for "Edit dock" (choose a place, then an app). */
    void onEnter(boolean dockEdit) {
        if (pill == null) return;
        this.dockEdit = dockEdit;
        slotChosen = false;
        carried = null;
        active = true;
        int accent = PanelTheme.get(host).accent;
        float density = host.getResources().getDisplayMetrics().density;
        // Frosted dark glass with the accent as its outline, like the VPN pill.
        pill.setBackground(GlassLook.outlined(new GlassSurface.Spec(0xF52A2A2E, 0xF5161618, 0, 0f, density * 17),
                density * 17, Math.round(density * 1.5f), accent));
        GradientDrawable dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dotShape.setColor(accent);
        dot.setBackground(dotShape);
        arrows.setAccent(accent);
        refresh(false);
        pill.animate().cancel();
        pill.setVisibility(View.VISIBLE);
        int ms = SettingsModel.duration(animations, reducedMotion, 200);
        if (ms == 0) {
            pill.setAlpha(1f);
            pill.setTranslationY(0f);
        } else {
            pill.setAlpha(0f);
            pill.setTranslationY(-12 * host.getResources().getDisplayMetrics().density);
            pill.animate().alpha(1f).translationY(0f).setDuration(ms).start();
        }
    }

    /** The mode ended, by Back, a finished edit, or implicitly (Home paused, apps changed). */
    void onExit() {
        if (pill == null || !active) return;
        active = false;
        carried = null;
        arrows.hideNow();
        pill.animate().cancel();
        int ms = SettingsModel.duration(animations, reducedMotion, 160);
        if (ms == 0) {
            pill.setVisibility(View.GONE);
            return;
        }
        pill.animate().alpha(0f).setDuration(ms).withEndAction(() -> {
            if (!active) pill.setVisibility(View.GONE);
        }).start();
    }

    /** The tile for the app named label was picked up. */
    void onCarry(String label) {
        carried = label;
        refresh(true);
    }

    /** The carried tile was put down; the mode stays on. */
    void onDrop() {
        carried = null;
        arrows.hideNow();
        refresh(true);
    }

    /** The carried tile moved: its arrows go until it has settled and showHints places them again. */
    void onMoved() {
        arrows.hideNow();
    }

    /** Edit dock: the dock place slot was chosen. */
    void onDockSlotChosen() {
        slotChosen = true;
        refresh(true);
    }

    /** Edit dock: back from choosing the app to choosing the place. */
    void onDockSlotCleared() {
        slotChosen = false;
        refresh(true);
    }

    /**
     * Arrows beside the carried tile, for the directions in mask (bits of {@link MoveTarget}). The tile is centred at
     * (cx, cy) in Home's coordinates and halfW by halfH big as drawn, lifted.
     */
    void showHints(float cx, float cy, float halfW, float halfH, int mask) {
        if (arrows == null || !active || carried == null) return;
        arrows.show(cx, cy, halfW, halfH, mask, SettingsModel.duration(animations, reducedMotion, 120));
    }

    private void refresh(boolean fade) {
        ReorderPill.Stage stage = ReorderPill.stage(dockEdit, carried != null, slotChosen);
        switch (stage) {
            case CARRY:
                title.setText(ReorderPill.carryTitle(host.getString(R.string.pill_moving), carried));
                hint.setText(R.string.pill_hint_carry);
                break;
            case DOCK_PLACE:
                title.setText(R.string.pill_dock_place);
                hint.setText(R.string.pill_hint_dock_place);
                break;
            case DOCK_APP:
                title.setText(R.string.pill_dock_app);
                hint.setText(R.string.pill_hint_dock_app);
                break;
            default:
                title.setText(R.string.pill_browse);
                hint.setText(R.string.pill_hint_browse);
                break;
        }
        int ms = fade ? SettingsModel.duration(animations, reducedMotion, 120) : 0;
        if (ms > 0) {
            title.setAlpha(0f);
            hint.setAlpha(0f);
            title.animate().alpha(1f).setDuration(ms).start();
            hint.animate().alpha(1f).setDuration(ms).start();
        } else {
            title.setAlpha(1f);
            hint.setAlpha(1f);
        }
    }

    /**
     * The arrows, in one view over Home so they cost one draw. Only drawn while shown, with no allocation: the paths
     * and paints are made once, and a draw moves them with the canvas.
     */
    private static final class Arrows extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chevron = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path chevronPath = new Path();
        private final float radius, gap;
        private float cx, cy, halfW, halfH;
        private int mask;

        Arrows(Context context) {
            super(context);
            float d = context.getResources().getDisplayMetrics().density;
            radius = 11 * d;
            gap = 9 * d;
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(1.5f * d);
            chevron.setStyle(Paint.Style.STROKE);
            chevron.setStrokeWidth(2.2f * d);
            chevron.setStrokeCap(Paint.Cap.ROUND);
            chevron.setStrokeJoin(Paint.Join.ROUND);
            chevron.setColor(0xFFE8F6F3);
            // A chevron pointing right, centred on the origin.
            float s = 3.2f * d;
            chevronPath.moveTo(-s * 0.5f, -s);
            chevronPath.lineTo(s * 0.5f, 0f);
            chevronPath.lineTo(-s * 0.5f, s);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            // One bitmap-free draw: fading it does not need an offscreen buffer the size of the screen.
            forceHasOverlappingRendering(false);
        }

        void setAccent(int accent) {
            fill.setColor((accent & 0xFFFFFF) | 0x38000000);
            rim.setColor(accent | 0xFF000000);
        }

        void show(float cx, float cy, float halfW, float halfH, int mask, int fadeMs) {
            this.cx = cx;
            this.cy = cy;
            this.halfW = halfW;
            this.halfH = halfH;
            this.mask = mask;
            animate().cancel();
            setVisibility(VISIBLE);
            invalidate();
            if (fadeMs == 0) {
                setAlpha(1f);
                return;
            }
            setAlpha(0f);
            // A beat after the lift, so the arrows read as part of it settling.
            animate().alpha(1f).setStartDelay(80).setDuration(fadeMs).start();
        }

        void hideNow() {
            animate().cancel();
            animate().setStartDelay(0);
            setVisibility(GONE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            draw(canvas, MoveTarget.LEFT, cx - halfW - gap - radius, cy, 180f);
            draw(canvas, MoveTarget.RIGHT, cx + halfW + gap + radius, cy, 0f);
            draw(canvas, MoveTarget.UP, cx, cy - halfH - gap - radius, 270f);
            draw(canvas, MoveTarget.DOWN, cx, cy + halfH + gap + radius, 90f);
        }

        private void draw(Canvas canvas, int dir, float x, float y, float degrees) {
            if (!MoveTarget.has(mask, dir)) return;
            canvas.drawCircle(x, y, radius, fill);
            canvas.drawCircle(x, y, radius, rim);
            canvas.save();
            canvas.translate(x, y);
            canvas.rotate(degrees);
            canvas.drawPath(chevronPath, chevron);
            canvas.restore();
        }
    }
}
