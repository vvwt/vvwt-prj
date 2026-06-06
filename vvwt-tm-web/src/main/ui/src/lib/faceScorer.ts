// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
export interface FaceSignals {
    /** eyeBlink_L blendshape value [0,1] — higher = more closed. */
    eyeBlinkLeft: number;
    /** eyeBlink_R blendshape value [0,1] — higher = more closed. */
    eyeBlinkRight: number;
    /** mouthSmile_L blendshape value [0,1] — higher = more smile. */
    mouthSmileLeft: number;
    /** mouthSmile_R blendshape value [0,1] — higher = more smile. */
    mouthSmileRight: number;
    /** Head yaw in degrees (left/right rotation). Positive = turned right. */
    headYaw: number;
    /** Head pitch in degrees (up/down tilt). Positive = tilted down. */
    headPitch: number;
}

/** Aggregate bounding box covering all detected faces (MediaPipe image-space coordinates). */
export interface BoundingBox {
    x: number;
    y: number;
    width: number;
    height: number;
}

/** Score for a single candidate image. */
export interface CandidateScore {
    /** Number of faces detected in this candidate. */
    faceCount: number;
    /** Aggregate quality score [0,1]. Higher is better. 0 when faceCount === 0. */
    aggregateScore: number;
}

// ---------------------------------------------------------------------------
// scoreFace
// ---------------------------------------------------------------------------

/**
 * Computes a quality score [0,1] for a single detected face.
 *
 * All input values are clamped to [0,1] before use — values outside this range
 * (which can occasionally appear in MediaPipe blendshape output) are handled safely.
 *
 * Head yaw is normalised to a [0,45°] range (beyond ±45° = fully looking away).
 * Head pitch is normalised to a [0,30°] range (beyond ±30° = fully tilted away).
 */
export function scoreFace(signals: FaceSignals): number {
    const clamp = (v: number): number => Math.max(0, Math.min(1, v));

    const eyeBlinkLeft = clamp(signals.eyeBlinkLeft);
    const eyeBlinkRight = clamp(signals.eyeBlinkRight);
    const mouthSmileLeft = clamp(signals.mouthSmileLeft);
    const mouthSmileRight = clamp(signals.mouthSmileRight);

    const eyeOpenScore = 1 - (eyeBlinkLeft + eyeBlinkRight) / 2;
    const smileScore = (mouthSmileLeft + mouthSmileRight) / 2;

    // Head pose: normalise |yaw| by 45° and |pitch| by 30°, average, clamp.
    const yawNorm = Math.abs(signals.headYaw) / 45;
    const pitchNorm = Math.abs(signals.headPitch) / 30;
    const headPoseScore = clamp(1 - (yawNorm + pitchNorm) / 2);

    return 0.4 * eyeOpenScore + 0.35 * smileScore + 0.25 * headPoseScore;
}

// ---------------------------------------------------------------------------
// scoreCandidate
// ---------------------------------------------------------------------------

/**
 * Computes an aggregate quality score for a candidate image.
 *
 * When no faces are detected (faces.length === 0), returns score 0.
 * The completeness bonus penalises candidates where faces appear at the edges of the frame
 * (indicating the group is not well-framed).
 *
 * @param faces       Per-face signals for all faces detected in this candidate.
 * @param imageWidth  Pixel width of the candidate image (as scored — may be the downscaled copy).
 * @param imageHeight Pixel height of the candidate image.
 * @param detectedBbox Bounding box covering all detected faces, or null if unavailable.
 */
export function scoreCandidate(
    faces: FaceSignals[],
    imageWidth: number,
    imageHeight: number,
    detectedBbox: BoundingBox | null,
): CandidateScore {
    if (faces.length === 0) {
        return { faceCount: 0, aggregateScore: 0 };
    }

    const faceAvg = faces.reduce((sum, f) => sum + scoreFace(f), 0) / faces.length;
    const completenessBonus = computeCompletenessBonus(detectedBbox, imageWidth, imageHeight);

    return {
        faceCount: faces.length,
        aggregateScore: faceAvg * completenessBonus,
    };
}

// ---------------------------------------------------------------------------
// rankCandidates
// ---------------------------------------------------------------------------

/**
 * Ranks candidate images by their aggregate score (highest first).
 *
 * Returns an array of indices into the original `scores` array, sorted so that
 * the best candidate is at index 0. Equal scores preserve original (stable) order.
 *
 * @param scores  Array of CandidateScore results (one per candidate).
 * @returns       Sorted indices — `ranked[0]` is the index of the best candidate.
 */
export function rankCandidates(scores: CandidateScore[]): number[] {
    return scores
        .map((score, index) => ({ score: score.aggregateScore, index }))
        .sort((a, b) => {
            const diff = b.score - a.score;
            return diff !== 0 ? diff : a.index - b.index; // stable: preserve original order on tie
        })
        .map(entry => entry.index);
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

/**
 * Returns 1.0 if all detected faces are within the central 90% of the image width/height,
 * 0.85 otherwise (faces at the edges suggest the group is partially out of frame).
 */
function computeCompletenessBonus(
    bbox: BoundingBox | null,
    imageWidth: number,
    imageHeight: number,
): number {
    if (bbox === null || imageWidth <= 0 || imageHeight <= 0) {
        return 0.85; // no bbox information → apply conservative penalty
    }

    const marginX = imageWidth * 0.05;
    const marginY = imageHeight * 0.05;
    const withinX = bbox.x >= marginX && (bbox.x + bbox.width) <= (imageWidth - marginX);
    const withinY = bbox.y >= marginY && (bbox.y + bbox.height) <= (imageHeight - marginY);

    return withinX && withinY ? 1.0 : 0.85;
}
