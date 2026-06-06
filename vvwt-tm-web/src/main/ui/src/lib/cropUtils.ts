// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
export interface AspectRatio {
    width: number;
    height: number;
}

/**
 * A crop rectangle within an image's pixel coordinate space.
 * x, y are the top-left corner; all values are non-negative integers.
 */
export interface CropRect {
    x: number;
    y: number;
    width: number;
    height: number;
}

/** Output dimensions after downscale. */
export interface Dimensions {
    width: number;
    height: number;
}

// ---------------------------------------------------------------------------
// parseAspectRatio
// ---------------------------------------------------------------------------

/**
 * Parses a "W:H" aspect-ratio string into an AspectRatio object.
 *
 * @param value  A string of the form "W:H" where W and H are positive integers.
 * @returns AspectRatio with integer width and height.
 * @throws Error if the string is not a valid "W:H" form or either component is ≤ 0.
 */
export function parseAspectRatio(value: string): AspectRatio {
    const parts = value.split(':');
    if (parts.length !== 2) {
        throw new Error(`Invalid aspect ratio "${value}": expected "W:H" format`);
    }
    const width = parseInt(parts[0], 10);
    const height = parseInt(parts[1], 10);
    if (!Number.isFinite(width) || width <= 0) {
        throw new Error(`Invalid aspect ratio width "${parts[0]}": must be a positive integer`);
    }
    if (!Number.isFinite(height) || height <= 0) {
        throw new Error(`Invalid aspect ratio height "${parts[1]}": must be a positive integer`);
    }
    return { width, height };
}

// ---------------------------------------------------------------------------
// centredCropRect
// ---------------------------------------------------------------------------

/**
 * Computes a centred crop rectangle for an image at the given aspect ratio.
 *
 * The rectangle is as large as possible while fitting entirely within the image dimensions and
 * having the given aspect ratio. It is centred over the image (face/subject-aware positioning
 * is applied separately in the UI — this function provides the initial geometric suggestion).
 *
 * All returned values are non-negative integers (floor).
 *
 * @param imageWidth   The natural pixel width of the source image.
 * @param imageHeight  The natural pixel height of the source image.
 * @param ratio        The target aspect ratio to match.
 * @returns A CropRect that fits within the image at the given ratio, centred.
 */
export function centredCropRect(imageWidth: number, imageHeight: number, ratio: AspectRatio): CropRect {
    // Determine the largest rect fitting inside the image at the desired ratio.
    // Try fitting by width first; if the resulting height exceeds the image, fit by height.
    const ratioValue = ratio.width / ratio.height;

    let cropWidth: number;
    let cropHeight: number;

    const heightIfFitByWidth = imageWidth / ratioValue;
    if (heightIfFitByWidth <= imageHeight) {
        cropWidth = imageWidth;
        cropHeight = heightIfFitByWidth;
    } else {
        cropHeight = imageHeight;
        cropWidth = imageHeight * ratioValue;
    }

    // Floor to integer pixels.
    cropWidth = Math.floor(cropWidth);
    cropHeight = Math.floor(cropHeight);

    // Centre the crop rect over the image.
    const x = Math.floor((imageWidth - cropWidth) / 2);
    const y = Math.floor((imageHeight - cropHeight) / 2);

    return { x, y, width: cropWidth, height: cropHeight };
}

// ---------------------------------------------------------------------------
// clampCropRect
// ---------------------------------------------------------------------------

/**
 * Clamps a crop rectangle so it stays entirely within the image bounds.
 *
 * Guarantees that the returned rect has at least 1×1 dimensions (AC5 — no crash on tiny image).
 *
 * @param rect         The crop rectangle to clamp.
 * @param imageWidth   The natural pixel width of the image (may be 0 — handled defensively).
 * @param imageHeight  The natural pixel height of the image (may be 0 — handled defensively).
 * @returns A new CropRect clamped to the image bounds.
 */
export function clampCropRect(rect: CropRect, imageWidth: number, imageHeight: number): CropRect {
    const safeImageWidth = Math.max(imageWidth, 1);
    const safeImageHeight = Math.max(imageHeight, 1);

    const x = Math.max(0, Math.min(rect.x, safeImageWidth - 1));
    const y = Math.max(0, Math.min(rect.y, safeImageHeight - 1));
    const width = Math.max(1, Math.min(rect.width, safeImageWidth - x));
    const height = Math.max(1, Math.min(rect.height, safeImageHeight - y));

    return { x, y, width, height };
}

// ---------------------------------------------------------------------------
// downscaleDimensions
// ---------------------------------------------------------------------------

/**
 * Computes the output dimensions after a long-edge downscale.
 *
 * If the image's longest edge is already ≤ maxLongEdge, the original dimensions are returned
 * unchanged (no upscaling). Otherwise both dimensions are scaled proportionally so the longest
 * edge equals maxLongEdge. All returned values are non-negative integers (floor).
 *
 * @param width       Source image width in pixels.
 * @param height      Source image height in pixels.
 * @param maxLongEdge Maximum allowed long-edge pixel length.
 * @returns Dimensions after downscale (or original if no downscale needed).
 */
export function downscaleDimensions(width: number, height: number, maxLongEdge: number): Dimensions {
    const longEdge = Math.max(width, height);
    if (longEdge <= maxLongEdge) {
        return { width, height };
    }
    const scale = maxLongEdge / longEdge;
    return {
        width: Math.floor(width * scale),
        height: Math.floor(height * scale),
    };
}
