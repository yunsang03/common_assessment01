package com.example.photoviewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Shared zoomable photo canvas. All annotations use normalized image coordinates. */
public final class PhotoMarkupView extends View {
    public static final class Pin {
        public final float x, y;
        public String text;
        public Pin(float x, float y, String text) { this.x = x; this.y = y; this.text = text; }
    }
    public static final class MaskRegion {
        public final RectF bounds;
        public boolean selected;
        public MaskRegion(RectF bounds, boolean selected) {
            this.bounds = bounds;
            this.selected = selected;
        }
    }
    public interface TapListener { void onTap(float x, float y); }
    public TapListener tapListener;
    public Runnable masksChanged;
    public final List<Pin> pins = new ArrayList<>();
    public final List<MaskRegion> masks = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private Bitmap bitmap;
    private float zoom = 1, panX, panY;
    private boolean drawMode, multiTouch;
    private PointF anchor, end;

    public PhotoMarkupView(Context context, Bitmap bitmap) {
        super(context);
        this.bitmap = bitmap;
        setContentDescription("사진 확대 및 위치 선택 영역");
        setBackgroundColor(Color.rgb(240, 243, 247));
        scaler = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        scaleAround(zoom * detector.getScaleFactor(),
                                detector.getFocusX(), detector.getFocusY());
                        return true;
                    }
                });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onScroll(MotionEvent first, MotionEvent current,
                                              float distanceX, float distanceY) {
                if (!drawMode && !scaler.isInProgress()) {
                    panX -= distanceX;
                    panY -= distanceY;
                    clampPan();
                    invalidate();
                }
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                if (!drawMode) scaleAround(zoom > 1.1f ? 1 : 2.5f, event.getX(), event.getY());
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                if (drawMode || multiTouch) return true;
                PointF point = imagePoint(event.getX(), event.getY(), false);
                if (point == null) return true;
                if (tapListener != null) tapListener.onTap(point.x, point.y);
                else {
                    for (int i = masks.size() - 1; i >= 0; i--) {
                        MaskRegion region = masks.get(i);
                        if (region.bounds.contains(point.x, point.y)) {
                            region.selected = !region.selected;
                            invalidate();
                            if (masksChanged != null) masksChanged.run();
                            break;
                        }
                    }
                }
                return true;
            }
        });
    }

    public void setDrawMode(boolean enabled) {
        drawMode = enabled;
        anchor = end = null;
        invalidate();
    }
    public boolean isDrawMode() { return drawMode; }
    public int selectedCount() {
        int count = 0;
        for (MaskRegion region : masks) if (region.selected) count++;
        return count;
    }
    private RectF imageBounds() {
        float fit = Math.min((float) getWidth() / bitmap.getWidth(),
                (float) getHeight() / bitmap.getHeight());
        float width = bitmap.getWidth() * fit * zoom;
        float height = bitmap.getHeight() * fit * zoom;
        float left = (getWidth() - width) / 2 + panX;
        float top = (getHeight() - height) / 2 + panY;
        return new RectF(left, top, left + width, top + height);
    }
    private PointF imagePoint(float x, float y, boolean clamp) {
        RectF rect = imageBounds();
        if (rect.width() <= 0 || rect.height() <= 0) return null;
        if (!clamp && !rect.contains(x, y)) return null;
        return new PointF(Math.max(0, Math.min(1, (x - rect.left) / rect.width())),
                Math.max(0, Math.min(1, (y - rect.top) / rect.height())));
    }
    private RectF screenRect(RectF normalized) {
        RectF image = imageBounds();
        return new RectF(image.left + normalized.left * image.width(),
                image.top + normalized.top * image.height(),
                image.left + normalized.right * image.width(),
                image.top + normalized.bottom * image.height());
    }
    private void scaleAround(float nextZoom, float focusX, float focusY) {
        PointF point = imagePoint(focusX, focusY, true);
        if (point == null) return;
        zoom = Math.max(1, Math.min(6, nextZoom));
        float fit = Math.min((float) getWidth() / bitmap.getWidth(),
                (float) getHeight() / bitmap.getHeight());
        float width = bitmap.getWidth() * fit * zoom;
        float height = bitmap.getHeight() * fit * zoom;
        panX = focusX - point.x * width - (getWidth() - width) / 2;
        panY = focusY - point.y * height - (getHeight() - height) / 2;
        clampPan();
        invalidate();
    }
    private void clampPan() {
        RectF rect = imageBounds();
        float maxX = Math.max(0, (rect.width() - getWidth()) / 2);
        float maxY = Math.max(0, (rect.height() - getHeight()) / 2);
        panX = Math.max(-maxX, Math.min(maxX, panX));
        panY = Math.max(-maxY, Math.min(maxY, panY));
    }
    public int hitPin(float x, float y) {
        RectF rect = imageBounds();
        float radius = 28 * getResources().getDisplayMetrics().density;
        for (int i = 0; i < pins.size(); i++) {
            Pin pin = pins.get(i);
            if (Math.hypot((pin.x - x) * rect.width(), (pin.y - y) * rect.height()) <= radius)
                return i;
        }
        return -1;
    }
    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        clampPan();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        RectF rect = imageBounds();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawBitmap(bitmap, null, rect, paint);
        for (MaskRegion region : masks) {
            paint.setColor(region.selected ? Color.BLACK : Color.rgb(220, 60, 60));
            paint.setStyle(region.selected ? Paint.Style.FILL : Paint.Style.STROKE);
            paint.setStrokeWidth(3 * getResources().getDisplayMetrics().density);
            canvas.drawRect(screenRect(region.bounds), paint);
        }
        if (anchor != null && end != null) {
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(screenRect(new RectF(Math.min(anchor.x, end.x),
                    Math.min(anchor.y, end.y), Math.max(anchor.x, end.x),
                    Math.max(anchor.y, end.y))), paint);
        }
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < pins.size(); i++) {
            Pin pin = pins.get(i);
            float x = rect.left + pin.x * rect.width();
            float y = rect.top + pin.y * rect.height();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(22, 88, 200));
            canvas.drawCircle(x, y, 15 * density, paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(15 * density);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(String.valueOf(i + 1), x,
                    y - (paint.ascent() + paint.descent()) / 2, paint);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            multiTouch = false;
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (event.getPointerCount() > 1) {
            multiTouch = true;
            anchor = end = null;
        }
        scaler.onTouchEvent(event);
        if (drawMode) {
            if (!multiTouch) {
                if (action == MotionEvent.ACTION_DOWN)
                    anchor = end = imagePoint(event.getX(), event.getY(), false);
                else if (action == MotionEvent.ACTION_MOVE && anchor != null)
                    end = imagePoint(event.getX(), event.getY(), true);
                else if (action == MotionEvent.ACTION_UP) {
                    end = imagePoint(event.getX(), event.getY(), true);
                    if (anchor != null && end != null) {
                        RectF selected = new RectF(Math.min(anchor.x, end.x),
                                Math.min(anchor.y, end.y), Math.max(anchor.x, end.x),
                                Math.max(anchor.y, end.y));
                        if (selected.width() * bitmap.getWidth() >= 3
                                && selected.height() * bitmap.getHeight() >= 3) {
                            masks.add(new MaskRegion(selected, true));
                            if (masksChanged != null) masksChanged.run();
                        }
                    }
                    anchor = end = null;
                    performClick();
                }
            }
            if (action == MotionEvent.ACTION_CANCEL) anchor = end = null;
        } else gestures.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
            getParent().requestDisallowInterceptTouchEvent(false);
        invalidate();
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
