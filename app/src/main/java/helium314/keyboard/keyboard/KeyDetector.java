/*
 * Copyright (C) 2010 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard;

import android.util.SparseArray;

/**
 * This class handles key detection.
 */
public class KeyDetector {
    /**
     * How far, as a fraction of a key's width, a highly probable neighbour may pull the hit
     * point towards itself. Deliberately small: this changes which character is committed,
     * so an over-eager bias types the wrong letter outright instead of merely mis-suggesting.
     */
    private static final float DYNAMIC_ZONE_MAX_SHIFT_RATIO = 0.22f;
    /** Minimum probability advantage before a neighbour is allowed to win at all. */
    private static final float DYNAMIC_ZONE_MIN_ADVANTAGE = 0.15f;

    private final int mKeyHysteresisDistanceSquared;
    private final int mKeyHysteresisDistanceForSlidingModifierSquared;
    /** code point -> probability in 0..1 of being the next letter, or null when disabled. */
    private SparseArray<Float> mNextLetterBias;

    private Keyboard mKeyboard;
    private int mCorrectionX;
    private int mCorrectionY;

    public KeyDetector() {
        this(0.0f /* keyHysteresisDistance */, 0.0f /* keyHysteresisDistanceForSlidingModifier */);
    }

    /**
     * Key detection object constructor with key hysteresis distances.
     *
     * @param keyHysteresisDistance if the pointer movement distance is smaller than this, the
     * movement will not be handled as meaningful movement. The unit is pixel.
     * @param keyHysteresisDistanceForSlidingModifier the same parameter for sliding input that
     * starts from a modifier key such as shift and symbols key.
     */
    public KeyDetector(final float keyHysteresisDistance,
            final float keyHysteresisDistanceForSlidingModifier) {
        mKeyHysteresisDistanceSquared = (int)(keyHysteresisDistance * keyHysteresisDistance);
        mKeyHysteresisDistanceForSlidingModifierSquared = (int)(
                keyHysteresisDistanceForSlidingModifier * keyHysteresisDistanceForSlidingModifier);
    }

    public void setKeyboard(final Keyboard keyboard, final float correctionX,
            final float correctionY) {
        if (keyboard == null) {
            throw new NullPointerException();
        }
        mCorrectionX = (int)correctionX;
        mCorrectionY = (int)correctionY;
        mKeyboard = keyboard;
    }

    public int getKeyHysteresisDistanceSquared(final boolean isSlidingFromModifier) {
        return isSlidingFromModifier
                ? mKeyHysteresisDistanceForSlidingModifierSquared : mKeyHysteresisDistanceSquared;
    }

    public int getTouchX(final int x) {
        return x + mCorrectionX;
    }

    // TODO: Remove vertical correction.
    public int getTouchY(final int y) {
        return y + mCorrectionY;
    }

    public Keyboard getKeyboard() {
        return mKeyboard;
    }

    public boolean alwaysAllowsKeySelectionByDraggingFinger() {
        return false;
    }

    /**
     * Detect the key whose hitbox the touch point is in.
     *
     * @param x The x-coordinate of a touch point
     * @param y The y-coordinate of a touch point
     * @return the key that the touch point hits.
     */
    /**
     * Sets the predicted next-letter distribution used to enlarge likely keys.
     * Pass null to disable.
     */
    public void setNextLetterBias(final SparseArray<Float> bias) {
        mNextLetterBias = bias;
    }

    private static float distanceToCenter(final Key key, final int x, final int y) {
        final float cx = key.getX() + key.getWidth() / 2f;
        final float cy = key.getY() + key.getHeight() / 2f;
        return (float) Math.hypot(x - cx, y - cy);
    }

    /**
     * Lets a much more probable neighbour win when the touch landed near the boundary.
     *
     * Only letter keys of comparable size take part, and only when the normally detected key
     * is itself a letter, so this can never steal a press from space, shift or enter. The
     * effective distance is the distance to the key centre minus the probability advantage
     * expressed in pixels, which means a neighbour can only win while the finger is already
     * closer to the edge than DYNAMIC_ZONE_MAX_SHIFT_RATIO of a key width.
     */
    private Key applyNextLetterBias(final Key detected, final int touchX, final int touchY) {
        final SparseArray<Float> bias = mNextLetterBias;
        if (bias == null || bias.size() == 0 || mKeyboard == null) return detected;
        if (detected == null || !Character.isLetter(detected.getCode())) return detected;

        final float maxShift = mKeyboard.mMostCommonKeyWidth * DYNAMIC_ZONE_MAX_SHIFT_RATIO;
        if (maxShift <= 0) return detected;
        final float detectedBias = bias.get(detected.getCode(), 0f);
        float bestEffective = distanceToCenter(detected, touchX, touchY) - detectedBias * maxShift;
        Key best = detected;

        for (final Key key : mKeyboard.getNearestKeys(touchX, touchY)) {
            if (key == detected || !Character.isLetter(key.getCode())) continue;
            if (key.getWidth() != detected.getWidth()) continue; // skip odd-sized keys
            final float keyBias = bias.get(key.getCode(), 0f);
            if (keyBias - detectedBias < DYNAMIC_ZONE_MIN_ADVANTAGE) continue;
            final float effective = distanceToCenter(key, touchX, touchY) - keyBias * maxShift;
            if (effective < bestEffective) {
                bestEffective = effective;
                best = key;
            }
        }
        return best;
    }

    public Key detectHitKey(final int x, final int y) {
        if (mKeyboard == null) {
            return null;
        }
        final int touchX = getTouchX(x);
        final int touchY = getTouchY(y);

        int minDistance = Integer.MAX_VALUE;
        Key primaryKey = null;
        for (final Key key: mKeyboard.getNearestKeys(touchX, touchY)) {
            // An edge key always has its enlarged hitbox to respond to an event that occurred in
            // the empty area around the key. (@see Key#markAsLeftEdge(KeyboardParams)} etc.)
            if (!key.isOnKey(touchX, touchY)) {
                continue;
            }
            final int distance = key.squaredDistanceToEdge(touchX, touchY);
            if (distance > minDistance) {
                continue;
            }
            // To take care of hitbox overlaps, we compare key's code here too.
            if (primaryKey == null || distance < minDistance
                    || key.getCode() > primaryKey.getCode()) {
                minDistance = distance;
                primaryKey = key;
            }
        }
        return applyNextLetterBias(primaryKey, touchX, touchY);
    }
}
