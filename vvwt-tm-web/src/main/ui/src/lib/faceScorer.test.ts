// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import {
    scoreFace,
    scoreCandidate,
    rankCandidates,
    type FaceSignals,
    type BoundingBox,
} from './faceScorer.js';

// ---------------------------------------------------------------------------
// scoreFace
// ---------------------------------------------------------------------------

describe('scoreFace', () => {
    it('returns high score for ideal face (eyes open, smiling, looking forward)', () => {
        const signals: FaceSignals = {
            eyeBlinkLeft: 0,
            eyeBlinkRight: 0,
            mouthSmileLeft: 0.8,
            mouthSmileRight: 0.8,
            headYaw: 0,
            headPitch: 0,
        };
        const score = scoreFace(signals);
        // eyeOpen = 1.0, smile = 0.8, headPose = 1.0
        // expected ≈ 0.4 * 1.0 + 0.35 * 0.8 + 0.25 * 1.0 = 0.4 + 0.28 + 0.25 = 0.93
        expect(score).toBeGreaterThan(0.85);
        expect(score).toBeLessThanOrEqual(1.0);
    });

    it('returns reduced score when eyes are closed', () => {
        const signals: FaceSignals = {
            eyeBlinkLeft: 1.0,
            eyeBlinkRight: 1.0,
            mouthSmileLeft: 0.8,
            mouthSmileRight: 0.8,
            headYaw: 0,
            headPitch: 0,
        };
        const openScore = scoreFace({
            eyeBlinkLeft: 0,
            eyeBlinkRight: 0,
            mouthSmileLeft: 0.8,
            mouthSmileRight: 0.8,
            headYaw: 0,
            headPitch: 0,
        });
        const closedScore = scoreFace(signals);
        // Closed eyes: eyeOpen = 0 → 0.4 * 0 = 0 vs open: 0.4 * 1.0 = 0.4
        expect(closedScore).toBeLessThan(openScore);
        // eyeOpen = 0, smile = 0.8, headPose = 1.0 → 0 + 0.28 + 0.25 = 0.53
        expect(closedScore).toBeGreaterThan(0.45);
        expect(closedScore).toBeLessThan(0.65);
    });

    it('returns reduced score when head is turned (high yaw)', () => {
        const forward: FaceSignals = {
            eyeBlinkLeft: 0,
            eyeBlinkRight: 0,
            mouthSmileLeft: 0.5,
            mouthSmileRight: 0.5,
            headYaw: 0,
            headPitch: 0,
        };
        const turned: FaceSignals = {
            ...forward,
            headYaw: 45,
        };
        expect(scoreFace(turned)).toBeLessThan(scoreFace(forward));
    });

    it('clamps negative blendshape values to 0', () => {
        const signals: FaceSignals = {
            eyeBlinkLeft: -0.5,
            eyeBlinkRight: -0.3,
            mouthSmileLeft: -0.1,
            mouthSmileRight: -0.2,
            headYaw: 0,
            headPitch: 0,
        };
        const score = scoreFace(signals);
        // Clamped to 0 → same as all-zero signals for blink/smile
        // eyeOpen = 1.0 (blink clamped to 0), smile = 0 (clamped), headPose = 1.0
        // → 0.4 + 0 + 0.25 = 0.65
        expect(score).toBeGreaterThanOrEqual(0.6);
        expect(score).toBeLessThanOrEqual(1.0);
    });

    it('clamps blendshape values above 1 to 1', () => {
        const signals: FaceSignals = {
            eyeBlinkLeft: 2.0,  // blink clamped to 1
            eyeBlinkRight: 2.0,
            mouthSmileLeft: 0.5,
            mouthSmileRight: 0.5,
            headYaw: 0,
            headPitch: 0,
        };
        const score = scoreFace(signals);
        // eyeOpen = 0 (both blinks=1), smile=0.5, headPose=1 → 0 + 0.175 + 0.25 = 0.425
        expect(score).toBeGreaterThan(0.35);
        expect(score).toBeLessThan(0.6);
    });

    it('returns 0 when all signals at worst values', () => {
        const signals: FaceSignals = {
            eyeBlinkLeft: 1.0,
            eyeBlinkRight: 1.0,
            mouthSmileLeft: 0,
            mouthSmileRight: 0,
            headYaw: 45,
            headPitch: 30,
        };
        // eyeOpen = 0, smile = 0, headPose = 1 - (1+1)/2 = 0
        // → 0 + 0 + 0 = 0
        expect(scoreFace(signals)).toBe(0);
    });
});

// ---------------------------------------------------------------------------
// scoreCandidate
// ---------------------------------------------------------------------------

describe('scoreCandidate', () => {
    const goodFace: FaceSignals = {
        eyeBlinkLeft: 0,
        eyeBlinkRight: 0,
        mouthSmileLeft: 0.8,
        mouthSmileRight: 0.8,
        headYaw: 0,
        headPitch: 0,
    };

    // For 1920×1080: marginX = 96px, marginY = 54px
    // inFrameBbox must have x≥96, y≥54, x+w≤1824, y+h≤1026
    const inFrameBbox: BoundingBox = { x: 200, y: 100, width: 400, height: 300 };

    it('returns score 0 when no faces detected', () => {
        const result = scoreCandidate([], 1920, 1080, null);
        expect(result.faceCount).toBe(0);
        expect(result.aggregateScore).toBe(0);
    });

    it('returns positive aggregate score for one good face in frame', () => {
        const result = scoreCandidate([goodFace], 1920, 1080, inFrameBbox);
        expect(result.faceCount).toBe(1);
        expect(result.aggregateScore).toBeGreaterThan(0.7);
    });

    it('aggregates multiple faces (averages their individual scores)', () => {
        const poorFace: FaceSignals = {
            eyeBlinkLeft: 1.0,
            eyeBlinkRight: 1.0,
            mouthSmileLeft: 0,
            mouthSmileRight: 0,
            headYaw: 45,
            headPitch: 30,
        };
        // one good + one poor → average should be between their individual scores
        const resultSingle = scoreCandidate([goodFace], 1920, 1080, inFrameBbox);
        const resultMixed = scoreCandidate([goodFace, poorFace], 1920, 1080, inFrameBbox);
        expect(resultMixed.aggregateScore).toBeLessThan(resultSingle.aggregateScore);
        expect(resultMixed.aggregateScore).toBeGreaterThan(0);
    });

    it('applies completeness penalty for out-of-frame faces', () => {
        // bbox whose left edge is within the 5% left margin of a 1920×1080 image
        // margin = 1920*0.05 = 96px → a bbox starting at x=0 violates the margin
        const edgeBbox: BoundingBox = { x: 0, y: 0, width: 100, height: 100 };
        const inFrameResult = scoreCandidate([goodFace], 1920, 1080, inFrameBbox);
        const edgeResult = scoreCandidate([goodFace], 1920, 1080, edgeBbox);
        // Out-of-frame → 0.85 multiplier vs in-frame → 1.0 multiplier
        expect(edgeResult.aggregateScore).toBeLessThan(inFrameResult.aggregateScore);
    });

    it('handles null bbox gracefully (treated as out-of-frame)', () => {
        const result = scoreCandidate([goodFace], 1920, 1080, null);
        // Should not throw; score is reduced by completeness penalty
        expect(result.faceCount).toBe(1);
        expect(result.aggregateScore).toBeGreaterThan(0);
    });
});

// ---------------------------------------------------------------------------
// rankCandidates
// ---------------------------------------------------------------------------

describe('rankCandidates', () => {
    it('returns indices sorted highest score first', () => {
        const scores = [
            { faceCount: 1, aggregateScore: 0.3 },
            { faceCount: 2, aggregateScore: 0.8 },
            { faceCount: 1, aggregateScore: 0.5 },
        ];
        const ranked = rankCandidates(scores);
        expect(ranked).toEqual([1, 2, 0]);
    });

    it('returns empty array for empty input', () => {
        expect(rankCandidates([])).toEqual([]);
    });

    it('returns single index for single candidate', () => {
        const scores = [{ faceCount: 1, aggregateScore: 0.7 }];
        expect(rankCandidates(scores)).toEqual([0]);
    });

    it('places zero-score candidates at the end', () => {
        const scores = [
            { faceCount: 0, aggregateScore: 0 },
            { faceCount: 1, aggregateScore: 0.6 },
            { faceCount: 0, aggregateScore: 0 },
        ];
        const ranked = rankCandidates(scores);
        expect(ranked[0]).toBe(1); // highest score first
    });

    it('stable sort: equal scores preserve original order', () => {
        const scores = [
            { faceCount: 1, aggregateScore: 0.5 },
            { faceCount: 1, aggregateScore: 0.5 },
        ];
        const ranked = rankCandidates(scores);
        expect(ranked).toEqual([0, 1]); // original order preserved when equal
    });
});
