package com.jarvis.arthur.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import com.jarvis.arthur.voice.VoiceManager;

/**
 * Iron Man style Arc Reactor visualizer.
 * Rotates rings, pulses with RMS speech volume, and shifts colors based on state.
 */
public class ArcReactorView extends View {
    private Paint outerRingPaint;
    private Paint segmentPaint;
    private Paint innerRingPaint;
    private Paint coreGlowPaint;
    private Paint coreSolidPaint;

    private float rotationAngle = 0f;
    private float rmsLevel = 0f;
    private float smoothRms = 0f;
    private VoiceManager.State currentState = VoiceManager.State.IDLE;

    private ValueAnimator rotationAnimator;
    private ValueAnimator idlePulseAnimator;
    private float idlePulse = 1.0f;

    public ArcReactorView(Context context) {
        super(context);
        init();
    }

    public ArcReactorView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ArcReactorView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        outerRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        outerRingPaint.setStyle(Paint.Style.STROKE);
        outerRingPaint.setStrokeWidth(4f);
        outerRingPaint.setColor(Color.parseColor("#00E5FF"));

        segmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        segmentPaint.setStyle(Paint.Style.STROKE);
        segmentPaint.setStrokeWidth(8f);
        segmentPaint.setColor(Color.parseColor("#8000E5FF"));

        innerRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        innerRingPaint.setStyle(Paint.Style.STROKE);
        innerRingPaint.setStrokeWidth(3f);
        innerRingPaint.setColor(Color.parseColor("#2979FF"));

        coreGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        coreGlowPaint.setStyle(Paint.Style.FILL);
        coreGlowPaint.setColor(Color.parseColor("#3300E5FF"));

        coreSolidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        coreSolidPaint.setStyle(Paint.Style.FILL);
        coreSolidPaint.setColor(Color.parseColor("#00E5FF"));

        // Continuous rotation animator
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f);
        rotationAnimator.setDuration(10000);
        rotationAnimator.setRepeatCount(ValueAnimator.INFINITE);
        rotationAnimator.setInterpolator(new LinearInterpolator());
        rotationAnimator.addUpdateListener(animation -> {
            rotationAngle = (float) animation.getAnimatedValue();
            invalidate();
        });
        rotationAnimator.start();

        // Idle gentle breathing pulse
        idlePulseAnimator = ValueAnimator.ofFloat(0.9f, 1.15f);
        idlePulseAnimator.setDuration(1800);
        idlePulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        idlePulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        idlePulseAnimator.addUpdateListener(animation -> {
            idlePulse = (float) animation.getAnimatedValue();
            if (currentState == VoiceManager.State.IDLE || currentState == VoiceManager.State.SPEAKING) {
                invalidate();
            }
        });
        idlePulseAnimator.start();
    }

    public void setRmsLevel(float rmsdB) {
        // Map rmsdB (-2 to 10 typical) to 0.0 to 1.0 range
        float normalized = Math.max(0f, Math.min(1.0f, (rmsdB + 2f) / 12f));
        // Exponential smoothing
        smoothRms = smoothRms * 0.6f + normalized * 0.4f;
        invalidate();
    }

    public void setState(VoiceManager.State state) {
        this.currentState = state;
        switch (state) {
            case LISTENING:
                outerRingPaint.setColor(Color.parseColor("#00E5FF")); // Bright cyan
                coreSolidPaint.setColor(Color.parseColor("#00E5FF"));
                segmentPaint.setColor(Color.parseColor("#A000E5FF"));
                rotationAnimator.setDuration(4000);
                break;
            case PROCESSING:
                outerRingPaint.setColor(Color.parseColor("#FFD54F")); // Cyber Gold
                coreSolidPaint.setColor(Color.parseColor("#FFD54F"));
                segmentPaint.setColor(Color.parseColor("#A0FFD54F"));
                rotationAnimator.setDuration(1500);
                break;
            case SPEAKING:
                outerRingPaint.setColor(Color.parseColor("#00E676")); // Hologram Green
                coreSolidPaint.setColor(Color.parseColor("#00E676"));
                segmentPaint.setColor(Color.parseColor("#A000E676"));
                rotationAnimator.setDuration(6000);
                break;
            case ERROR:
                outerRingPaint.setColor(Color.parseColor("#FF5252"));
                coreSolidPaint.setColor(Color.parseColor("#FF5252"));
                break;
            case IDLE:
            default:
                outerRingPaint.setColor(Color.parseColor("#2979FF"));
                coreSolidPaint.setColor(Color.parseColor("#00E5FF"));
                segmentPaint.setColor(Color.parseColor("#6000E5FF"));
                rotationAnimator.setDuration(12000);
                break;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) * 0.82f;

        if (radius <= 0) return;

        canvas.save();
        canvas.rotate(rotationAngle, cx, cy);

        // 1. Outer Ring
        canvas.drawCircle(cx, cy, radius, outerRingPaint);

        // 2. Segmented Tech Arcs
        RectF arcRect = new RectF(cx - radius * 0.88f, cy - radius * 0.88f,
                cx + radius * 0.88f, cy + radius * 0.88f);
        int segments = 8;
        float sweep = 26f;
        for (int i = 0; i < segments; i++) {
            float start = (360f / segments) * i;
            canvas.drawArc(arcRect, start, sweep, false, segmentPaint);
        }

        // 3. Inner Counter-Rotating Ring
        canvas.restore();
        canvas.save();
        canvas.rotate(-rotationAngle * 1.5f, cx, cy);
        canvas.drawCircle(cx, cy, radius * 0.62f, innerRingPaint);

        // Radiating energy lines
        for (int i = 0; i < 4; i++) {
            canvas.save();
            canvas.rotate(90f * i, cx, cy);
            canvas.drawLine(cx, cy - radius * 0.62f, cx, cy - radius * 0.38f, innerRingPaint);
            canvas.restore();
        }
        canvas.restore();

        // 4. Center Core with dynamic RMS or Idle Pulse
        float dynamicScale = (currentState == VoiceManager.State.LISTENING) ?
                (1.0f + smoothRms * 0.6f) : idlePulse;

        float coreRadius = radius * 0.32f * dynamicScale;
        float glowRadius = radius * 0.52f * dynamicScale;

        // Soft Outer Glow
        canvas.drawCircle(cx, cy, glowRadius, coreGlowPaint);
        // Bright Solid Core
        canvas.drawCircle(cx, cy, coreRadius, coreSolidPaint);

        // Center white pinpoint
        Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        whitePaint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, coreRadius * 0.35f, whitePaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (rotationAnimator != null) rotationAnimator.cancel();
        if (idlePulseAnimator != null) idlePulseAnimator.cancel();
    }
}
