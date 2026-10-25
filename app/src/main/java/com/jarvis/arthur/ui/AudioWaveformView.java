package com.jarvis.arthur.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.jarvis.arthur.voice.VoiceManager;

/**
 * Animated waveform bars reacting to microphone audio levels.
 */
public class AudioWaveformView extends View {
    private static final int BAR_COUNT = 9;
    private final float[] barHeights = new float[BAR_COUNT];
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float rms = 0f;
    private VoiceManager.State state = VoiceManager.State.IDLE;
    private ValueAnimator idleAnimator;

    public AudioWaveformView(Context context) {
        super(context);
        init();
    }

    public AudioWaveformView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AudioWaveformView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        barPaint.setStyle(Paint.Style.FILL);
        barPaint.setColor(Color.parseColor("#00E5FF"));

        for (int i = 0; i < BAR_COUNT; i++) {
            barHeights[i] = 0.2f;
        }

        idleAnimator = ValueAnimator.ofFloat(0.15f, 0.45f);
        idleAnimator.setDuration(800);
        idleAnimator.setRepeatMode(ValueAnimator.REVERSE);
        idleAnimator.setRepeatCount(ValueAnimator.INFINITE);
        idleAnimator.addUpdateListener(anim -> {
            if (state == VoiceManager.State.IDLE || state == VoiceManager.State.SPEAKING) {
                float val = (float) anim.getAnimatedValue();
                for (int i = 0; i < BAR_COUNT; i++) {
                    float factor = 1.0f - Math.abs((i - BAR_COUNT / 2f) / (BAR_COUNT / 2f)) * 0.5f;
                    barHeights[i] = val * factor;
                }
                invalidate();
            }
        });
        idleAnimator.start();
    }

    public void setRms(float rmsdB) {
        float norm = Math.max(0.1f, Math.min(1.0f, (rmsdB + 2f) / 10f));
        this.rms = norm;
        for (int i = 0; i < BAR_COUNT; i++) {
            float distFromCenter = Math.abs(i - (BAR_COUNT - 1) / 2f);
            float factor = 1.0f - (distFromCenter / (BAR_COUNT / 2f)) * 0.45f;
            float target = norm * factor * (0.8f + (float) Math.random() * 0.4f);
            barHeights[i] = barHeights[i] * 0.4f + target * 0.6f;
        }
        invalidate();
    }

    public void setState(VoiceManager.State state) {
        this.state = state;
        switch (state) {
            case LISTENING:
                barPaint.setColor(Color.parseColor("#00E5FF"));
                break;
            case PROCESSING:
                barPaint.setColor(Color.parseColor("#FFD54F"));
                break;
            case SPEAKING:
                barPaint.setColor(Color.parseColor("#00E676"));
                break;
            case ERROR:
                barPaint.setColor(Color.parseColor("#FF5252"));
                break;
            case IDLE:
            default:
                barPaint.setColor(Color.parseColor("#2979FF"));
                break;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float totalBarWidth = w / (BAR_COUNT * 1.8f);
        float spacing = (w - (totalBarWidth * BAR_COUNT)) / (BAR_COUNT + 1);

        for (int i = 0; i < BAR_COUNT; i++) {
            float barH = Math.max(8f, h * barHeights[i]);
            float left = spacing + i * (totalBarWidth + spacing);
            float top = (h - barH) / 2f;
            float right = left + totalBarWidth;
            float bottom = top + barH;

            RectF rect = new RectF(left, top, right, bottom);
            canvas.drawRoundRect(rect, totalBarWidth / 2f, totalBarWidth / 2f, barPaint);
        }
    }
}
