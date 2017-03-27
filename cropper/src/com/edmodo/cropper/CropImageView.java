/*
 * Copyright 2013, Edmodo, Inc. 
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this work except in compliance with the License.
 * You may obtain a copy of the License in the LICENSE file, or at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" 
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language 
 * governing permissions and limitations under the License. 
 */

package com.edmodo.cropper;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ImageView;

import com.edmodo.cropper.cropwindow.edge.Edge;
import com.edmodo.cropper.cropwindow.handle.Handle;
import com.edmodo.cropper.util.AspectRatioUtil;
import com.edmodo.cropper.util.HandleUtil;
import com.edmodo.cropper.util.PaintUtil;

/**
 * Custom view that provides cropping capabilities to an image.
 */
public class CropImageView extends ImageView {

    // Private Constants ///////////////////////////////////////////////////////////////////////////

    @SuppressWarnings("unused")
    private static final String TAG = CropImageView.class.getName();

    @SuppressWarnings("unused")
    public static final int GUIDELINES_OFF = 0;
    public static final int GUIDELINES_ON_TOUCH = 1;
    public static final int GUIDELINES_ON = 2;

    // Member Variables ////////////////////////////////////////////////////////////////////////////

    // The Paint used to draw the white rectangle around the crop area.
    private Paint mBorderPaint;

    // The Paint used to draw the guidelines within the crop area when pressed.
    private Paint mGuidelinePaint;

    // The Paint used to draw the corners of the Border
    private Paint mCornerPaint;

    // The Paint used to darken the surrounding areas outside the crop area.
    private Paint mSurroundingAreaOverlayPaint;

    // The radius (in pixels) of the touchable area around the handle.
    // We are basing this value off of the recommended 48dp touch target size.
    private float mHandleRadius;

    // An edge of the crop window will snap to the corresponding edge of a
    // specified bounding box when the crop window edge is less than or equal to
    // this distance (in pixels) away from the bounding box edge.
    private float mSnapRadius;

    // Thickness of the line (in pixels) used to draw the corner handle.
    private float mCornerThickness;

    // Thickness of the line (in pixels) used to draw the border of the crop window.
    private float mBorderThickness;

    // Length of one side of the corner handle.
    private float mCornerLength;

    // The bounding box around the Bitmap that we are cropping.
    @NonNull
    private RectF mBitmapRect = new RectF();

    // Holds the x and y offset between the exact touch location and the exact
    // handle location that is activated. There may be an offset because we
    // allow for some leeway (specified by 'mHandleRadius') in activating a
    // handle. However, we want to maintain these offset values while the handle
    // is being dragged so that the handle doesn't jump.
    @NonNull
    private PointF mTouchOffset = new PointF();

    // The Handle that is currently pressed; null if no Handle is pressed.
    private Handle mPressedHandle;

    // Flag indicating if the crop area should always be a certain aspect ratio (indicated by mTargetAspectRatio).
    private boolean mFixAspectRatio;

    // Current aspect ratio of the image.
    private int mAspectRatioX = 1;
    private int mAspectRatioY = 1;

    // Mode indicating how/whether to show the guidelines; must be one of GUIDELINES_OFF, GUIDELINES_ON_TOUCH, GUIDELINES_ON.
    private int mGuidelinesMode = 1;

    // Constructors ////////////////////////////////////////////////////////////////////////////////

    public CropImageView(Context context) {
        super(context);
        init(context, null);
    }

    public CropImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public CropImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(@NonNull Context context, @Nullable AttributeSet attrs) {

        final TypedArray typedArray = context.obtainStyledAttributes(attrs, R.styleable.CropImageView, 0, 0);
        mGuidelinesMode = typedArray.getInteger(R.styleable.CropImageView_guidelines, 1);
        mFixAspectRatio = typedArray.getBoolean(R.styleable.CropImageView_fixAspectRatio, false);
        mAspectRatioX = typedArray.getInteger(R.styleable.CropImageView_aspectRatioX, 1);
        mAspectRatioY = typedArray.getInteger(R.styleable.CropImageView_aspectRatioY, 1);
        typedArray.recycle();

        final Resources resources = context.getResources();

        mBorderPaint = PaintUtil.newBorderPaint(resources);
        mGuidelinePaint = PaintUtil.newGuidelinePaint(resources);
        mSurroundingAreaOverlayPaint = PaintUtil.newSurroundingAreaOverlayPaint(resources);
        mCornerPaint = PaintUtil.newCornerPaint(resources);

        mHandleRadius = resources.getDimension(R.dimen.target_radius);
        mSnapRadius = resources.getDimension(R.dimen.snap_radius);
        mBorderThickness = resources.getDimension(R.dimen.border_thickness);
        mCornerThickness = resources.getDimension(R.dimen.corner_thickness);
        mCornerLength = resources.getDimension(R.dimen.corner_length);
    }

    // View Methods ////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {

        super.onLayout(changed, left, top, right, bottom);

        mBitmapRect = getBitmapRect();
        initCropWindow(mBitmapRect);
    }

    @Override
    protected void onDraw(Canvas canvas) {

        super.onDraw(canvas);

        drawDarkenedSurroundingArea(canvas);
        drawGuidelines(canvas);
        drawBorder(canvas);
        drawCorners(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {

        // If this View is not enabled, don't allow for touch interactions.
        if (!isEnabled()) {
            return false;
        }

        switch (event.getAction()) {

            case MotionEvent.ACTION_DOWN:
                onActionDown(event.getX(), event.getY());
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                onActionUp();
                return true;

            case MotionEvent.ACTION_MOVE:
                onActionMove(event.getX(), event.getY());
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;

            default:
                return false;
        }
    }

    // Public Methods //////////////////////////////////////////////////////////////////////////////

    /**
     * Sets the guidelines for the CropOverlayView to be either on, off, or to show when resizing
     * the application.
     *
     * @param guidelinesMode Integer that signals whether the guidelines should be on, off, or only
     *                       showing when resizing.
     */
    public void setGuidelines(int guidelinesMode) {
        mGuidelinesMode = guidelinesMode;
        invalidate(); // Request onDraw() to get called again.
    }

    /**
     * Sets whether the aspect ratio is fixed or not; true fixes the aspect ratio, while false
     * allows it to be changed.
     *
     * @param fixAspectRatio Boolean that signals whether the aspect ratio should be maintained.
     *
     * @see {@link #setAspectRatio(int, int)}
     */
    public void setFixedAspectRatio(boolean fixAspectRatio) {
        mFixAspectRatio = fixAspectRatio;
        requestLayout(); // Request measure/layout to be run again.
    }

    /**
     * Sets the both the X and Y values of the aspectRatio. These only apply iff fixed aspect ratio
     * is set.
     *
     * @param aspectRatioX new X value of the aspect ratio; must be greater than 0
     * @param aspectRatioY new Y value of the aspect ratio; must be greater than 0
     *
     * @see {@link #setFixedAspectRatio(boolean)}
     */
    public void setAspectRatio(int aspectRatioX, int aspectRatioY) {

        if (aspectRatioX <= 0 || aspectRatioY <= 0) {
            throw new IllegalArgumentException("Cannot set aspect ratio value to a number less than or equal to 0.");
        }
        mAspectRatioX = aspectRatioX;
        mAspectRatioY = aspectRatioY;

        if (mFixAspectRatio) {
            requestLayout(); // Request measure/layout to be run again.
        }
    }

    /**
     * Gets the cropped image based on the current crop window.
     *
     * @return a new Bitmap representing the cropped image
     */
    public Bitmap getCroppedImage() {

        // Implementation reference: http://stackoverflow.com/a/26930938/1068656

        final Drawable drawable = getDrawable();
        if (drawable == null || !(drawable instanceof BitmapDrawable)) {
            return null;
        }

        // Get image matrix values and place them in an array.
        final float[] matrixValues = new float[9];
        getImageMatrix().getValues(matrixValues);

        // Extract the scale and translation values. Note, we currently do not handle any other transformations (e.g. skew).
        final float scaleX = matrixValues[Matrix.MSCALE_X];
        final float scaleY = matrixValues[Matrix.MSCALE_Y];
        final float transX = matrixValues[Matrix.MTRANS_X];
        final float transY = matrixValues[Matrix.MTRANS_Y];

        // Ensure that the left and top edges are not outside of the ImageView bounds.
        final float bitmapLeft = -transX;
        final float bitmapTop = -transY;

        // Get the original bitmap object.
        final Bitmap originalBitmap = ((BitmapDrawable) drawable).getBitmap();

        // Calculate the top-left corner of the crop window relative to the ~original~ bitmap size.
        final float cropX = (bitmapLeft + Edge.LEFT.getCoordinate()) / scaleX;
        final float cropY = (bitmapTop + Edge.TOP.getCoordinate()) / scaleY;

        // Calculate the crop window size relative to the ~original~ bitmap size.
        // Make sure the right and bottom edges are not outside the ImageView bounds (this is just to address rounding discrepancies).
        final float cropWidth = Math.min(Edge.getWidth() / scaleX, originalBitmap.getWidth() - cropX);
        final float cropHeight = Math.min(Edge.getHeight() / scaleY, originalBitmap.getHeight() - cropY);

        // Crop the subset from the original Bitmap.
        return Bitmap.createBitmap(originalBitmap,
                                   (int) cropX,
                                   (int) cropY,
                                   (int) cropWidth,
                                   (int) cropHeight);
    }

    // Private Methods /////////////////////////////////////////////////////////////////////////////

    /**
     * Gets the bounding rectangle of the bitmap within the ImageView.
     */
    private RectF getBitmapRect() {

        final Drawable drawable = getDrawable();
        if (drawable == null) {
            return new RectF();
        }

        // Get image matrix values and place them in an array.
        final float[] matrixValues = new float[9];
        getImageMatrix().getValues(matrixValues);

        // Extract the scale and translation values from the matrix.
        final float scaleX = matrixValues[Matrix.MSCALE_X];
        final float scaleY = matrixValues[Matrix.MSCALE_Y];
        final float transX = matrixValues[Matrix.MTRANS_X];
        final float transY = matrixValues[Matrix.MTRANS_Y];

        // Get the width and height of the original bitmap.
        final int drawableIntrinsicWidth = drawable.getIntrinsicWidth();
        final int drawableIntrinsicHeight = drawable.getIntrinsicHeight();

        // Calculate the dimensions as seen on screen.
        final int drawableDisplayWidth = Math.round(drawableIntrinsicWidth * scaleX);
        final int drawableDisplayHeight = Math.round(drawableIntrinsicHeight * scaleY);

        // Get the Rect of the displayed image within the ImageView.
        final float left = Math.max(transX, 0);
        final float top = Math.max(transY, 0);
        final float right = Math.min(left + drawableDisplayWidth, getWidth());
        final float bottom = Math.min(top + drawableDisplayHeight, getHeight());

        return new RectF(left, top, right, bottom);
    }

    /**
     * Initialize the crop window by setting the proper {@link Edge} values.
     * <p/>
     * If fixed aspect ratio is turned off, the initial crop window will be set to the displayed
     * image with 10% margin. If fixed aspect ratio is turned on, the initial crop window will
     * conform to the aspect ratio with at least one dimension maximized.
     */
    private void initCropWindow(@NonNull RectF bitmapRect) {

        if (mFixAspectRatio) {

            // Initialize the crop window with the proper aspect ratio.
            initCropWindowWithFixedAspectRatio(bitmapRect);

        } else {

            // Initialize crop window to have 10% padding w/ respect to Drawable's bounds.
            final float horizontalPadding = 0.1f * bitmapRect.width();
            final float verticalPadding = 0.1f * bitmapRect.height();

            Edge.LEFT.setCoordinate(bitmapRect.left + horizontalPadding);
            Edge.TOP.setCoordinate(bitmapRect.top + verticalPadding);
            Edge.RIGHT.setCoordinate(bitmapRect.right - horizontalPadding);
            Edge.BOTTOM.setCoordinate(bitmapRect.bottom - verticalPadding);
        }
    }

    private void initCropWindowWithFixedAspectRatio(@NonNull RectF bitmapRect) {

        // If the image aspect ratio is wider than the crop aspect ratio,
        // then the image height is the determining initial length. Else, vice-versa.
        if (AspectRatioUtil.calculateAspectRatio(bitmapRect) > getTargetAspectRatio()) {

            final float cropWidth = AspectRatioUtil.calculateWidth(bitmapRect.height(), getTargetAspectRatio());

            Edge.LEFT.setCoordinate(bitmapRect.centerX() - cropWidth / 2f);
            Edge.TOP.setCoordinate(bitmapRect.top);
            Edge.RIGHT.setCoordinate(bitmapRect.centerX() + cropWidth / 2f);
            Edge.BOTTOM.setCoordinate(bitmapRect.bottom);

        } else {

            final float cropHeight = AspectRatioUtil.calculateHeight(bitmapRect.width(), getTargetAspectRatio());

            Edge.LEFT.setCoordinate(bitmapRect.left);
            Edge.TOP.setCoordinate(bitmapRect.centerY() - cropHeight / 2f);
            Edge.RIGHT.setCoordinate(bitmapRect.right);
            Edge.BOTTOM.setCoordinate(bitmapRect.centerY() + cropHeight / 2f);
        }
    }

    private void drawDarkenedSurroundingArea(@NonNull Canvas canvas) {

        final RectF bitmapRect = mBitmapRect;

        final float left = Edge.LEFT.getCoordinate();
        final float top = Edge.TOP.getCoordinate();
        final float right = Edge.RIGHT.getCoordinate();
        final float bottom = Edge.BOTTOM.getCoordinate();

        /*-
          -------------------------------------
          |                top                |
          -------------------------------------
          |      |                    |       |
          |      |                    |       |
          | left |                    | right |
          |      |                    |       |
          |      |                    |       |
          -------------------------------------
          |              bottom               |
          -------------------------------------
         */

        // Draw "top", "bottom", "left", then "right" quadrants according to diagram above.
        canvas.drawRect(bitmapRect.left, bitmapRect.top, bitmapRect.right, top, mSurroundingAreaOverlayPaint);
        canvas.drawRect(bitmapRect.left, bottom, bitmapRect.right, bitmapRect.bottom, mSurroundingAreaOverlayPaint);
        canvas.drawRect(bitmapRect.left, top, left, bottom, mSurroundingAreaOverlayPaint);
        canvas.drawRect(right, top, bitmapRect.right, bottom, mSurroundingAreaOverlayPaint);
    }

    private void drawGuidelines(@NonNull Canvas canvas) {

        if (!shouldGuidelinesBeShown()) {
            return;
        }

        final float left = Edge.LEFT.getCoordinate();
        final float top = Edge.TOP.getCoordinate();
        final float right = Edge.RIGHT.getCoordinate();
        final float bottom = Edge.BOTTOM.getCoordinate();

        // Draw vertical guidelines.
        final float oneThirdCropWidth = Edge.getWidth() / 3;

        final float x1 = left + oneThirdCropWidth;
        canvas.drawLine(x1, top, x1, bottom, mGuidelinePaint);
        final float x2 = right - oneThirdCropWidth;
        canvas.drawLine(x2, top, x2, bottom, mGuidelinePaint);

        // Draw horizontal guidelines.
        final float oneThirdCropHeight = Edge.getHeight() / 3;

        final float y1 = top + oneThirdCropHeight;
        canvas.drawLine(left, y1, right, y1, mGuidelinePaint);
        final float y2 = bottom - oneThirdCropHeight;
        canvas.drawLine(left, y2, right, y2, mGuidelinePaint);
    }

    private void drawBorder(@NonNull Canvas canvas) {

        canvas.drawRect(Edge.LEFT.getCoordinate(),
                        Edge.TOP.getCoordinate(),
                        Edge.RIGHT.getCoordinate(),
                        Edge.BOTTOM.getCoordinate(),
                        mBorderPaint);
    }

    private void drawCorners(@NonNull Canvas canvas) {

        final float left = Edge.LEFT.getCoordinate();
        final float top = Edge.TOP.getCoordinate();
        final float right = Edge.RIGHT.getCoordinate();
        final float bottom = Edge.BOTTOM.getCoordinate();

        // Absolute value of the offset by which to draw the corner line such that its inner edge is flush with the border's inner edge.
        final float lateralOffset = (mCornerThickness - mBorderThickness) / 2f;
        // Absolute value of the offset by which to start the corner line such that the line is drawn all the way to form a corner edge with the adjacent side.
        final float startOffset = mCornerThickness - (mBorderThickness / 2f);

        // Top-left corner: left side
        canvas.drawLine(left - lateralOffset, top - startOffset, left - lateralOffset, top + mCornerLength, mCornerPaint);
        // Top-left corner: top side
        canvas.drawLine(left - startOffset, top - lateralOffset, left + mCornerLength, top - lateralOffset, mCornerPaint);

        // Top-right corner: right side
        canvas.drawLine(right + lateralOffset, top - startOffset, right + lateralOffset, top + mCornerLength, mCornerPaint);
        // Top-right corner: top side
        canvas.drawLine(right + startOffset, top - lateralOffset, right - mCornerLength, top - lateralOffset, mCornerPaint);

        // Bottom-left corner: left side
        canvas.drawLine(left - lateralOffset, bottom + startOffset, left - lateralOffset, bottom - mCornerLength, mCornerPaint);
        // Bottom-left corner: bottom side
        canvas.drawLine(left - startOffset, bottom + lateralOffset, left + mCornerLength, bottom + lateralOffset, mCornerPaint);

        // Bottom-right corner: right side
        canvas.drawLine(right + lateralOffset, bottom + startOffset, right + lateralOffset, bottom - mCornerLength, mCornerPaint);
        // Bottom-right corner: bottom side
        canvas.drawLine(right + startOffset, bottom + lateralOffset, right - mCornerLength, bottom + lateralOffset, mCornerPaint);
    }

    private boolean shouldGuidelinesBeShown() {
        return ((mGuidelinesMode == GUIDELINES_ON)
                || ((mGuidelinesMode == GUIDELINES_ON_TOUCH) && (mPressedHandle != null)));
    }

    private float getTargetAspectRatio() {
        return mAspectRatioX / (float) mAspectRatioY;
    }

    /**
     * Handles a {@link MotionEvent#ACTION_DOWN} event.
     *
     * @param x the x-coordinate of the down action
     * @param y the y-coordinate of the down action
     */
    private void onActionDown(float x, float y) {

        final float left = Edge.LEFT.getCoordinate();
        final float top = Edge.TOP.getCoordinate();
        final float right = Edge.RIGHT.getCoordinate();
        final float bottom = Edge.BOTTOM.getCoordinate();

        mPressedHandle = HandleUtil.getPressedHandle(x, y, left, top, right, bottom, mHandleRadius);

        // Calculate the offset of the touch point from the precise location of the handle.
        // Save these values in member variable 'mTouchOffset' so that we can maintain this offset as we drag the handle.
        if (mPressedHandle != null) {
            HandleUtil.getOffset(mPressedHandle, x, y, left, top, right, bottom, mTouchOffset);
            invalidate();
        }
    }

    /**
     * Handles a {@link MotionEvent#ACTION_UP} or {@link MotionEvent#ACTION_CANCEL} event.
     */
    private void onActionUp() {
        if (mPressedHandle != null) {
            mPressedHandle = null;
            invalidate();
        }
    }

    /**
     * Handles a {@link MotionEvent#ACTION_MOVE} event.
     *
     * @param x the x-coordinate of the move event
     * @param y the y-coordinate of the move event
     */
    private void onActionMove(float x, float y) {

        if (mPressedHandle == null) {
            return;
        }

        // Adjust the coordinates for the finger position's offset (i.e. the distance from the initial touch to the precise handle location).
        // We want to maintain the initial touch's distance to the pressed handle so that the crop window size does not "jump".
        x += mTouchOffset.x;
        y += mTouchOffset.y;

        // Calculate the new crop window size/position.
        if (mFixAspectRatio) {
            mPressedHandle.updateCropWindow(x, y, getTargetAspectRatio(), mBitmapRect, mSnapRadius);
        } else {
            mPressedHandle.updateCropWindow(x, y, mBitmapRect, mSnapRadius);
        }
        invalidate();
    }

}
