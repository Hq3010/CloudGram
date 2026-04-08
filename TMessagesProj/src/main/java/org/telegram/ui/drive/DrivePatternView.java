/*
 * TeleDrive — file storage app built on top of Telegram infrastructure.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.ui.drive;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * A 3×3 pattern lock grid. Returns the selected pattern as a string of
 * node indices (0-8, left-to-right, top-to-bottom).
 */
public class DrivePatternView extends View {

    private static final int GRID = 3;
    private static final int NODE_COUNT = GRID * GRID;

    private static final int COLOR_INACTIVE = 0xFFDADCE0;
    private static final int COLOR_ACTIVE   = 0xFF1A73E8;
    private static final int COLOR_ERROR    = 0xFFEA4335;

    private final Paint nodePaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float[][] nodePositions = new float[NODE_COUNT][2]; // x,y centers
    private final List<Integer> selectedNodes = new ArrayList<>();
    private float touchX, touchY;
    private boolean drawing = false;
    private boolean errorState = false;

    private float nodeRadius;
    private float centerRadius;

    private OnPatternListener listener;

    public interface OnPatternListener {
        void onPatternComplete(String pattern);
    }

    public DrivePatternView(Context context) {
        super(context);
        init();
    }

    public DrivePatternView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        nodePaint.setStyle(Paint.Style.STROKE);
        nodePaint.setStrokeWidth(dp(2.5f));
        nodePaint.setColor(COLOR_INACTIVE);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(4));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setColor(COLOR_ACTIVE);

        centerPaint.setStyle(Paint.Style.FILL);
        centerPaint.setColor(COLOR_INACTIVE);
    }

    public void setOnPatternListener(OnPatternListener l) {
        this.listener = l;
    }

    public void showError() {
        errorState = true;
        nodePaint.setColor(COLOR_ERROR);
        linePaint.setColor(COLOR_ERROR);
        centerPaint.setColor(COLOR_ERROR);
        invalidate();
        postDelayed(() -> {
            reset();
        }, 800);
    }

    public void reset() {
        selectedNodes.clear();
        drawing = false;
        errorState = false;
        nodePaint.setColor(COLOR_INACTIVE);
        linePaint.setColor(COLOR_ACTIVE);
        centerPaint.setColor(COLOR_INACTIVE);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int size = Math.min(w, h);
        float cellSize = size / (float) GRID;
        nodeRadius = cellSize * 0.28f;
        centerRadius = cellSize * 0.08f;

        float offsetX = (w - size) / 2f;
        float offsetY = (h - size) / 2f;

        for (int row = 0; row < GRID; row++) {
            for (int col = 0; col < GRID; col++) {
                int idx = row * GRID + col;
                nodePositions[idx][0] = offsetX + col * cellSize + cellSize / 2f;
                nodePositions[idx][1] = offsetY + row * cellSize + cellSize / 2f;
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Draw lines between selected nodes
        for (int i = 1; i < selectedNodes.size(); i++) {
            int from = selectedNodes.get(i - 1);
            int to = selectedNodes.get(i);
            canvas.drawLine(nodePositions[from][0], nodePositions[from][1],
                    nodePositions[to][0], nodePositions[to][1], linePaint);
        }

        // Draw line from last node to touch point
        if (drawing && !selectedNodes.isEmpty()) {
            int last = selectedNodes.get(selectedNodes.size() - 1);
            canvas.drawLine(nodePositions[last][0], nodePositions[last][1],
                    touchX, touchY, linePaint);
        }

        // Draw nodes
        for (int i = 0; i < NODE_COUNT; i++) {
            boolean selected = selectedNodes.contains(i);
            float cx = nodePositions[i][0];
            float cy = nodePositions[i][1];

            if (selected || errorState) {
                nodePaint.setColor(errorState ? COLOR_ERROR : COLOR_ACTIVE);
                centerPaint.setColor(errorState ? COLOR_ERROR : COLOR_ACTIVE);
            } else {
                nodePaint.setColor(COLOR_INACTIVE);
                centerPaint.setColor(COLOR_INACTIVE);
            }

            canvas.drawCircle(cx, cy, nodeRadius, nodePaint);
            canvas.drawCircle(cx, cy, centerRadius, centerPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (errorState) return true;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                reset();
                drawing = true;
                touchX = event.getX();
                touchY = event.getY();
                checkHitNode(touchX, touchY);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                touchX = event.getX();
                touchY = event.getY();
                checkHitNode(touchX, touchY);
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                drawing = false;
                if (selectedNodes.size() >= 4) {
                    if (listener != null) {
                        listener.onPatternComplete(patternToString());
                    }
                } else if (!selectedNodes.isEmpty()) {
                    showError();
                }
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void checkHitNode(float x, float y) {
        for (int i = 0; i < NODE_COUNT; i++) {
            if (selectedNodes.contains(i)) continue;
            float dx = x - nodePositions[i][0];
            float dy = y - nodePositions[i][1];
            if (dx * dx + dy * dy <= nodeRadius * nodeRadius * 2.5f) {
                selectedNodes.add(i);
                break;
            }
        }
    }

    public String patternToString() {
        StringBuilder sb = new StringBuilder();
        for (int i : selectedNodes) sb.append(i);
        return sb.toString();
    }

    private float dp(float value) {
        return value * getContext().getResources().getDisplayMetrics().density;
    }
}
