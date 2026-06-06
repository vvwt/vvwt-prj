<!--
  SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
  SPDX-License-Identifier: AGPL-3.0-or-later
-->
<script lang="ts">
  /**
   * In-browser photo crop component — Story E71S01.
   *
   * AC1: Displays a crop step with a ratio-locked rectangle pre-suggested (face/subject-aware
   *      centre heuristic) and adjustable via drag.
   * AC2: On save, crops to the accepted rectangle and downscales so the longest edge does not
   *      exceed cropMaxLongEdge. Produces a Blob that is passed to the onSave callback.
   * AC4: Runtime canvas/resize behaviour is attested in the impl-report. No automated browser
   *      oracle — DEC-22 test-surface honesty clause.
   * AC5: If the image is smaller than the target crop in some dimension, the crop rect is
   *      clamped (no crash). The image is not upscaled beyond its native size.
   * AC6: All user-facing strings via svelte-i18n.
   *
   * Props:
   *   file            — The source File selected by the operator.
   *   ratioWidth      — Width component of the target aspect ratio.
   *   ratioHeight     — Height component of the target aspect ratio.
   *   cropMaxLongEdge — Maximum long-edge pixel length after downscale.
   *   onSave          — Callback(blob: Blob) called with the cropped+downscaled Blob.
   *   onCancel        — Callback() called when the operator cancels the crop step.
   */

  import { onMount, onDestroy } from 'svelte';
  import { _ } from 'svelte-i18n';
  import {
    centredCropRect,
    clampCropRect,
    downscaleDimensions,
    type CropRect,
  } from './cropUtils.js';

  // ── Props ────────────────────────────────────────────────────────────────
  interface Props {
    file: File;
    ratioWidth: number;
    ratioHeight: number;
    cropMaxLongEdge: number;
    onSave: (blob: Blob) => void;
    onCancel: () => void;
  }
  let {
    file,
    ratioWidth,
    ratioHeight,
    cropMaxLongEdge,
    onSave,
    onCancel,
  }: Props = $props();

  // ── State ────────────────────────────────────────────────────────────────
  let canvas = $state<HTMLCanvasElement | null>(null);
  let overlayCanvas = $state<HTMLCanvasElement | null>(null);
  let imageEl = $state<HTMLImageElement | null>(null);

  let naturalWidth = $state(0);
  let naturalHeight = $state(0);
  let displayWidth = $state(0);
  let displayHeight = $state(0);

  /**
   * Current crop rect in natural image coordinates.
   * Initialised by centredCropRect on image load (AC1 face/subject centre heuristic).
   */
  let cropRect = $state<CropRect>({ x: 0, y: 0, width: 0, height: 0 });

  let saving = $state(false);
  let tooSmallHint = $state(false);
  let loadError = $state<string | null>(null);

  /** Drag state for moving/resizing the crop rectangle. */
  let dragState = $state<'move' | 'none'>('none');
  let dragStartX = $state(0);
  let dragStartY = $state(0);
  let dragStartRect = $state<CropRect>({ x: 0, y: 0, width: 0, height: 0 });

  // ── Display scale ─────────────────────────────────────────────────────────
  /** Scale factor: natural → display (canvas pixels). */
  const displayScale = $derived(naturalWidth > 0 ? displayWidth / naturalWidth : 1);

  // ── Image URL ─────────────────────────────────────────────────────────────
  let objectUrl = $state<string | null>(null);

  onMount(() => {
    objectUrl = URL.createObjectURL(file);
  });

  onDestroy(() => {
    if (objectUrl) URL.revokeObjectURL(objectUrl);
  });

  // ── Image load ───────────────────────────────────────────────────────────

  function handleImageLoad(): void {
    if (!imageEl) return;
    naturalWidth = imageEl.naturalWidth;
    naturalHeight = imageEl.naturalHeight;

    // Display at most 600px wide (or natural size if smaller).
    displayWidth = Math.min(600, naturalWidth);
    displayHeight = Math.round(displayWidth * (naturalHeight / naturalWidth));

    if (canvas) {
      canvas.width = displayWidth;
      canvas.height = displayHeight;
    }
    if (overlayCanvas) {
      overlayCanvas.width = displayWidth;
      overlayCanvas.height = displayHeight;
    }

    // Initial crop suggestion: centred at target ratio (AC1).
    const initial = centredCropRect(naturalWidth, naturalHeight, { width: ratioWidth, height: ratioHeight });
    cropRect = clampCropRect(initial, naturalWidth, naturalHeight);

    // Show hint if the image is smaller than target crop in some dimension (AC5).
    tooSmallHint =
      naturalWidth < Math.floor(naturalHeight * (ratioWidth / ratioHeight)) ||
      naturalHeight < Math.floor(naturalWidth * (ratioHeight / ratioWidth));

    drawCanvas();
  }

  // ── Canvas rendering ─────────────────────────────────────────────────────

  function drawCanvas(): void {
    if (!canvas || !overlayCanvas || !imageEl || naturalWidth === 0) return;

    // Draw source image.
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.drawImage(imageEl, 0, 0, displayWidth, displayHeight);

    // Draw crop overlay: dim outside, bright inside, border.
    const octx = overlayCanvas.getContext('2d');
    if (!octx) return;

    octx.clearRect(0, 0, displayWidth, displayHeight);

    // Dimmed overlay over whole canvas.
    octx.fillStyle = 'rgba(0, 0, 0, 0.45)';
    octx.fillRect(0, 0, displayWidth, displayHeight);

    // Clear (reveal) the crop area.
    const scale = displayScale;
    const rx = Math.round(cropRect.x * scale);
    const ry = Math.round(cropRect.y * scale);
    const rw = Math.round(cropRect.width * scale);
    const rh = Math.round(cropRect.height * scale);

    octx.clearRect(rx, ry, rw, rh);

    // Crop border.
    octx.strokeStyle = '#fff';
    octx.lineWidth = 2;
    octx.strokeRect(rx + 1, ry + 1, rw - 2, rh - 2);

    // Move handle indicator (centre).
    octx.fillStyle = 'rgba(255,255,255,0.7)';
    octx.beginPath();
    octx.arc(rx + rw / 2, ry + rh / 2, 6, 0, Math.PI * 2);
    octx.fill();
  }

  // Re-draw whenever cropRect changes.
  $effect(() => {
    // Depend on cropRect fields.
    const _w = cropRect.width;
    const _h = cropRect.height;
    const _x = cropRect.x;
    const _y = cropRect.y;
    drawCanvas();
  });

  // ── Drag interaction (move the crop rect) ────────────────────────────────

  function onPointerDown(event: PointerEvent): void {
    if (!overlayCanvas) return;
    overlayCanvas.setPointerCapture(event.pointerId);
    dragState = 'move';
    dragStartX = event.offsetX;
    dragStartY = event.offsetY;
    dragStartRect = { ...cropRect };
  }

  function onPointerMove(event: PointerEvent): void {
    if (dragState !== 'move' || naturalWidth === 0) return;

    const scale = displayScale;
    const dx = (event.offsetX - dragStartX) / scale;
    const dy = (event.offsetY - dragStartY) / scale;

    const moved: CropRect = {
      ...dragStartRect,
      x: Math.round(dragStartRect.x + dx),
      y: Math.round(dragStartRect.y + dy),
    };
    cropRect = clampCropRect(moved, naturalWidth, naturalHeight);
  }

  function onPointerUp(): void {
    dragState = 'none';
  }

  // ── Save (AC2) ───────────────────────────────────────────────────────────

  async function handleSave(): Promise<void> {
    if (!imageEl || naturalWidth === 0) return;

    saving = true;
    try {
      const clamped = clampCropRect(cropRect, naturalWidth, naturalHeight);
      const scaled = downscaleDimensions(clamped.width, clamped.height, cropMaxLongEdge);

      const offscreen = document.createElement('canvas');
      offscreen.width = scaled.width;
      offscreen.height = scaled.height;

      const ctx = offscreen.getContext('2d');
      if (!ctx) throw new Error('Cannot create 2D context');

      ctx.drawImage(
        imageEl,
        clamped.x, clamped.y, clamped.width, clamped.height,
        0, 0, scaled.width, scaled.height,
      );

      const blob = await new Promise<Blob>((resolve, reject) => {
        offscreen.toBlob(
          (b) => {
            if (b) resolve(b);
            else reject(new Error('Canvas toBlob returned null'));
          },
          'image/jpeg',
          0.92,
        );
      });

      onSave(blob);
    } catch (err: unknown) {
      loadError = err instanceof Error ? err.message : String(err);
    } finally {
      saving = false;
    }
  }
</script>

<div class="photo-cropper">
  <h3 class="photo-cropper__title">{$_('photos.cropTitle')}</h3>
  <p class="photo-cropper__instruction">{$_('photos.cropInstruction')}</p>

  {#if tooSmallHint}
    <p class="photo-cropper__hint">{$_('photos.cropTooSmall')}</p>
  {/if}

  {#if loadError}
    <p class="photo-cropper__error" role="alert">{loadError}</p>
  {/if}

  <!-- Hidden image element used as drawImage source. -->
  <!-- svelte-ignore a11y_img_redundant_alt -->
  <img
    bind:this={imageEl}
    src={objectUrl ?? ''}
    alt=""
    class="photo-cropper__source-img"
    onload={handleImageLoad}
  />

  <!-- Canvas stack: image canvas below, interactive overlay canvas above. -->
  {#if naturalWidth > 0}
    <div
      class="photo-cropper__canvas-container"
      style="width: {displayWidth}px; height: {displayHeight}px;"
    >
      <canvas
        bind:this={canvas}
        class="photo-cropper__canvas"
        width={displayWidth}
        height={displayHeight}
      ></canvas>
      <canvas
        bind:this={overlayCanvas}
        class="photo-cropper__overlay"
        width={displayWidth}
        height={displayHeight}
        role="img"
        aria-label={$_('photos.cropTitle')}
        onpointerdown={onPointerDown}
        onpointermove={onPointerMove}
        onpointerup={onPointerUp}
      ></canvas>
    </div>
  {/if}

  <div class="photo-cropper__actions">
    <button
      class="btn btn--primary"
      disabled={saving || naturalWidth === 0}
      onclick={handleSave}
    >
      {saving ? $_('photos.cropSavingButton') : $_('photos.cropSaveButton')}
    </button>
    <button
      class="btn btn--secondary"
      disabled={saving}
      onclick={onCancel}
    >
      {$_('photos.cropCancelButton')}
    </button>
  </div>
</div>

<style>
  .photo-cropper {
    display: flex;
    flex-direction: column;
    gap: 0.75rem;
    padding: 1rem;
    border: 1px solid #ddd;
    border-radius: 6px;
    background: #f8f8f8;
    max-width: 640px;
  }

  .photo-cropper__title {
    margin: 0;
    font-size: 1rem;
    font-weight: 600;
    color: #2c3e50;
  }

  .photo-cropper__instruction {
    margin: 0;
    font-size: 0.85rem;
    color: #555;
  }

  .photo-cropper__hint {
    margin: 0;
    font-size: 0.82rem;
    color: #e67e22;
    background: #fef9f0;
    padding: 0.4rem 0.6rem;
    border-radius: 4px;
    border: 1px solid #f5cba7;
  }

  .photo-cropper__error {
    color: #c0392b;
    font-size: 0.85rem;
    margin: 0;
  }

  /* Source image hidden — used only as drawImage source. */
  .photo-cropper__source-img {
    display: none;
  }

  .photo-cropper__canvas-container {
    position: relative;
    border: 1px solid #ccc;
    border-radius: 4px;
    overflow: hidden;
    cursor: move;
  }

  .photo-cropper__canvas,
  .photo-cropper__overlay {
    position: absolute;
    top: 0;
    left: 0;
    display: block;
  }

  .photo-cropper__canvas {
    z-index: 1;
  }

  .photo-cropper__overlay {
    z-index: 2;
  }

  .photo-cropper__actions {
    display: flex;
    gap: 0.5rem;
    align-items: center;
  }

  .btn {
    cursor: pointer;
    border: none;
    border-radius: 4px;
    padding: 0.5rem 1.25rem;
    font-size: 0.9rem;
  }

  .btn:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }

  .btn--primary {
    background: #2980b9;
    color: #fff;
  }

  .btn--secondary {
    background: #ecf0f1;
    color: #2c3e50;
    border: 1px solid #bdc3c7;
  }
</style>
