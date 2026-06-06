// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import {
    parseAspectRatio,
    centredCropRect,
    clampCropRect,
    downscaleDimensions,
    type AspectRatio,
    type CropRect,
} from './cropUtils.js';

// ---------------------------------------------------------------------------
// parseAspectRatio
// ---------------------------------------------------------------------------

describe('parseAspectRatio', () => {
    it('parses "11:5" correctly', () => {
        const result: AspectRatio = parseAspectRatio('11:5');
        expect(result.width).toBe(11);
        expect(result.height).toBe(5);
    });

    it('parses "4:3" correctly', () => {
        const result: AspectRatio = parseAspectRatio('4:3');
        expect(result.width).toBe(4);
        expect(result.height).toBe(3);
    });

    it('parses "1:1" correctly', () => {
        const result: AspectRatio = parseAspectRatio('1:1');
        expect(result.width).toBe(1);
        expect(result.height).toBe(1);
    });

    it('throws for invalid input (missing colon)', () => {
        expect(() => parseAspectRatio('169')).toThrow();
    });

    it('throws for zero width', () => {
        expect(() => parseAspectRatio('0:5')).toThrow();
    });

    it('throws for zero height', () => {
        expect(() => parseAspectRatio('11:0')).toThrow();
    });
});

// ---------------------------------------------------------------------------
// centredCropRect
// ---------------------------------------------------------------------------

describe('centredCropRect', () => {
    it('produces a rect centred on a wider image (landscape source, 11:5 target)', () => {
        // Image 2200x1000 — exactly 11:5 → full image
        const rect: CropRect = centredCropRect(2200, 1000, { width: 11, height: 5 });
        expect(rect.x).toBe(0);
        expect(rect.y).toBe(0);
        expect(rect.width).toBe(2200);
        expect(rect.height).toBe(1000);
    });

    it('crops height when source aspect is wider than target', () => {
        // Image 3300x1000, target 11:5 → needs width=2200, height=1000
        // At 3300px wide with 11:5: height = 3300*(5/11) = 1500 > 1000 → constrain by height
        // height=1000, width = 1000*(11/5) = 2200, centre x = (3300-2200)/2 = 550
        const rect: CropRect = centredCropRect(3300, 1000, { width: 11, height: 5 });
        expect(rect.width).toBe(2200);
        expect(rect.height).toBe(1000);
        expect(rect.x).toBe(550);
        expect(rect.y).toBe(0);
    });

    it('crops width when source aspect is taller than target', () => {
        // Image 1100x2000, target 11:5
        // width=1100, height=1100*(5/11)=500, centre y = (2000-500)/2 = 750
        const rect: CropRect = centredCropRect(1100, 2000, { width: 11, height: 5 });
        expect(rect.width).toBe(1100);
        expect(rect.height).toBe(500);
        expect(rect.x).toBe(0);
        expect(rect.y).toBe(750);
    });

    it('all dimensions are integers (floor)', () => {
        // 1000x700, 4:3 → height-constrained: height=700, width=700*(4/3)=933.33 → 933
        const rect: CropRect = centredCropRect(1000, 700, { width: 4, height: 3 });
        expect(Number.isInteger(rect.x)).toBe(true);
        expect(Number.isInteger(rect.y)).toBe(true);
        expect(Number.isInteger(rect.width)).toBe(true);
        expect(Number.isInteger(rect.height)).toBe(true);
    });
});

// ---------------------------------------------------------------------------
// clampCropRect
// ---------------------------------------------------------------------------

describe('clampCropRect', () => {
    it('leaves a valid rect unchanged', () => {
        const rect: CropRect = { x: 0, y: 0, width: 100, height: 50 };
        const clamped = clampCropRect(rect, 200, 100);
        expect(clamped).toEqual(rect);
    });

    it('clamps x + width to image width', () => {
        const rect: CropRect = { x: 150, y: 0, width: 100, height: 50 };
        const clamped = clampCropRect(rect, 200, 100);
        expect(clamped.x + clamped.width).toBeLessThanOrEqual(200);
    });

    it('clamps y + height to image height', () => {
        const rect: CropRect = { x: 0, y: 80, width: 100, height: 50 };
        const clamped = clampCropRect(rect, 200, 100);
        expect(clamped.y + clamped.height).toBeLessThanOrEqual(100);
    });

    it('returns a rect with at least 1x1 dimensions (AC5: no crash on tiny image)', () => {
        // Image 5x5, rect demanding 100x50 — clamp produces minimum
        const rect: CropRect = { x: 0, y: 0, width: 100, height: 50 };
        const clamped = clampCropRect(rect, 5, 5);
        expect(clamped.width).toBeGreaterThanOrEqual(1);
        expect(clamped.height).toBeGreaterThanOrEqual(1);
    });

    it('handles zero-dimension image without throwing (AC5)', () => {
        const rect: CropRect = { x: 0, y: 0, width: 100, height: 50 };
        // Should not throw; returns 1x1 minimum
        const clamped = clampCropRect(rect, 0, 0);
        expect(clamped.width).toBeGreaterThanOrEqual(1);
        expect(clamped.height).toBeGreaterThanOrEqual(1);
    });
});

// ---------------------------------------------------------------------------
// downscaleDimensions
// ---------------------------------------------------------------------------

describe('downscaleDimensions', () => {
    it('does not upscale an image already smaller than maxLongEdge', () => {
        const result = downscaleDimensions(1000, 500, 2200);
        expect(result.width).toBe(1000);
        expect(result.height).toBe(500);
    });

    it('scales down landscape image so long edge equals maxLongEdge', () => {
        // 4400x2000, maxLongEdge=2200 → scale 0.5 → 2200x1000
        const result = downscaleDimensions(4400, 2000, 2200);
        expect(result.width).toBe(2200);
        expect(result.height).toBe(1000);
    });

    it('scales down portrait image so long edge equals maxLongEdge', () => {
        // 1000x4400, maxLongEdge=2200 → scale 0.5 → 500x2200
        const result = downscaleDimensions(1000, 4400, 2200);
        expect(result.width).toBe(500);
        expect(result.height).toBe(2200);
    });

    it('does not upscale a square image exactly at maxLongEdge', () => {
        const result = downscaleDimensions(2200, 2200, 2200);
        expect(result.width).toBe(2200);
        expect(result.height).toBe(2200);
    });

    it('returns integer dimensions', () => {
        // Odd dimensions — verify floor
        const result = downscaleDimensions(3301, 1501, 2200);
        expect(Number.isInteger(result.width)).toBe(true);
        expect(Number.isInteger(result.height)).toBe(true);
    });
});
