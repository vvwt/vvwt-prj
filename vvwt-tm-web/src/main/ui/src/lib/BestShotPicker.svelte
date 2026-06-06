<!--
  SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
  SPDX-License-Identifier: AGPL-3.0-or-later
-->
<script lang="ts">
  /**
   * Multi-candidate best-shot picker — Story E71S03.
   *
   * AC1 (multi-candidate gallery): Shows all selected candidate images as a gallery.
   *         Candidates are displayed as thumbnails with their current rank/score indication.
   * AC2 (automatic best-shot suggestion): Scores candidates via offline MediaPipe Face Landmarker
   *         and pre-selects the top-ranked candidate. Operator can override.
   * AC3 (hand-off to crop): Calls onConfirm(file) with the chosen candidate for crop step.
   * AC4 (fully offline): FilesetResolver and model loaded from same-origin /admin/ paths.
   *         No external CDN requests are made.
   * AC5 (error-handling): Model failure or no-face → graceful manual selection, localized message.
   * AC7 (i18n): All labels from svelte-i18n, German-first per TM convention.
   *
   * Props:
   *   candidates      — Array of File objects (the operator-selected candidate photos).
   *   onConfirm       — Callback(file: File) when operator confirms selection.
   *   onCancel        — Callback() when operator cancels.
   *
   * Note: BestShotPicker appears only when ≥2 candidates are selected. Single-file
   *       uploads bypass this component entirely and go directly to PhotoCropper.
   *
   * Honest limitation (Brief O-8): MediaPipe is optimized for selfie/near-range faces.
   * Group photos with small or distant faces may yield reduced accuracy. The suggestion
   * is always displayed as a proposal — the operator decides which photo to use.
   */
  import { onMount, onDestroy } from 'svelte';
  import { _ } from 'svelte-i18n';
  import {
    scoreCandidate,
    rankCandidates,
    type FaceSignals,
    type BoundingBox,
    type CandidateScore,
  } from './faceScorer.js';

  // ── Props ────────────────────────────────────────────────────────────────
  interface Props {
    candidates: File[];
    onConfirm: (file: File) => void;
    onCancel: () => void;
  }
  let { candidates, onConfirm, onCancel }: Props = $props();

  // ── State ────────────────────────────────────────────────────────────────

  /** Object URLs for gallery thumbnail display. Revoked on destroy. */
  let thumbnailUrls = $state<string[]>([]);

  /** Scoring results per candidate. Empty until scoring completes. */
  let scores = $state<CandidateScore[]>([]);

  /**
   * Ranked indices (best first). Empty until scoring completes.
   * Used to show the best-suggestion badge on the top-ranked candidate.
   */
  let rankedIndices = $state<number[]>([]);

  /**
   * Currently selected candidate index. Initialized to 0, updated to best
   * after scoring completes. Operator can change this at any time.
   */
  let selectedIndex = $state(0);

  type ScoringState = 'loading' | 'scoring' | 'done' | 'error';
  let scoringState = $state<ScoringState>('loading');
  let scoringError = $state<string | null>(null);

  // ── Derived ──────────────────────────────────────────────────────────────

  /** True when scoring is in progress (model loading or inference running). */
  const isScoring = $derived(scoringState === 'loading' || scoringState === 'scoring');

  /** Index of the best candidate (first in ranked order), or 0 as fallback. */
  const bestIndex = $derived(rankedIndices.length > 0 ? rankedIndices[0] : 0);

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  onMount(() => {
    // Create object URLs for all candidates.
    thumbnailUrls = candidates.map(f => URL.createObjectURL(f));

    // Start async scoring pipeline.
    void runScoringPipeline();
  });

  onDestroy(() => {
    // Revoke all object URLs to free memory (AC5 — no resource leaks).
    for (const url of thumbnailUrls) {
      URL.revokeObjectURL(url);
    }
  });

  // ── Scoring pipeline ──────────────────────────────────────────────────────

  async function runScoringPipeline(): Promise<void> {
    scoringState = 'loading';
    scoringError = null;

    let FaceLandmarkerModule: typeof import('@mediapipe/tasks-vision').FaceLandmarker | undefined;
    let FilesetResolverModule: typeof import('@mediapipe/tasks-vision').FilesetResolver | undefined;
    let faceLandmarker: import('@mediapipe/tasks-vision').FaceLandmarker | undefined;

    try {
      // AC4: Load WASM and model from same-origin bundled assets (no CDN).
      // The WASM files are in /admin/mediapipe-wasm/ and the model is at /admin/face_landmarker.task
      // (bundled via Vite public/ — base '/admin/' prefix applied).
      const vision = await import('@mediapipe/tasks-vision');
      FaceLandmarkerModule = vision.FaceLandmarker;
      FilesetResolverModule = vision.FilesetResolver;

      const filesetResolver = await FilesetResolverModule.forVisionTasks('/admin/mediapipe-wasm');

      faceLandmarker = await FaceLandmarkerModule.createFromOptions(filesetResolver, {
        baseOptions: {
          modelAssetPath: '/admin/face_landmarker.task',
          delegate: 'CPU',
        },
        outputFaceBlendshapes: true,
        outputFacialTransformationMatrixes: true,
        runningMode: 'IMAGE',
        numFaces: 10,
      });
    } catch (err: unknown) {
      // AC5: Model load failure → graceful degradation. Operator can still manually select.
      scoringState = 'error';
      scoringError = $_('photos.bestShot.scoringError');
      console.warn('[BestShotPicker] MediaPipe model load failed:', err);
      return;
    }

    scoringState = 'scoring';

    try {
      const candidateScores: CandidateScore[] = [];

      for (const file of candidates) {
        const score = await scoreSingleCandidate(file, faceLandmarker);
        candidateScores.push(score);
      }

      scores = candidateScores;
      rankedIndices = rankCandidates(candidateScores);

      // AC2: Pre-select the top-ranked candidate.
      if (rankedIndices.length > 0) {
        selectedIndex = rankedIndices[0];
      }

      scoringState = 'done';
    } catch (err: unknown) {
      scoringState = 'error';
      scoringError = $_('photos.bestShot.scoringError');
      console.warn('[BestShotPicker] Scoring failed:', err);
    } finally {
      // Release MediaPipe resources.
      faceLandmarker?.close();
    }
  }

  /**
   * Scores a single candidate by running MediaPipe Face Landmarker on a downscaled copy.
   * Downscaling to ≤480px long edge keeps inference fast (~50–150ms per candidate).
   */
  async function scoreSingleCandidate(
    file: File,
    landmarker: import('@mediapipe/tasks-vision').FaceLandmarker,
  ): Promise<CandidateScore> {
    return new Promise<CandidateScore>((resolve) => {
      const img = new Image();
      img.onload = () => {
        const MAX_LONG_EDGE = 480;
        const longEdge = Math.max(img.naturalWidth, img.naturalHeight);
        const scale = longEdge > MAX_LONG_EDGE ? MAX_LONG_EDGE / longEdge : 1;
        const w = Math.floor(img.naturalWidth * scale);
        const h = Math.floor(img.naturalHeight * scale);

        const canvas = document.createElement('canvas');
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext('2d');
        if (!ctx) {
          URL.revokeObjectURL(img.src);
          resolve({ faceCount: 0, aggregateScore: 0 });
          return;
        }
        ctx.drawImage(img, 0, 0, w, h);
        URL.revokeObjectURL(img.src);

        try {
          const result = landmarker.detect(canvas);
          const faceSignals = extractFaceSignals(result);
          const bbox = extractAggregatedBbox(result, w, h);
          resolve(scoreCandidate(faceSignals, w, h, bbox));
        } catch {
          // AC5: Inference failure on individual candidate → score 0, not a crash.
          resolve({ faceCount: 0, aggregateScore: 0 });
        }
      };
      img.onerror = () => {
        resolve({ faceCount: 0, aggregateScore: 0 });
      };
      img.src = URL.createObjectURL(file);
    });
  }

  /**
   * Extracts per-face FaceSignals from the MediaPipe FaceLandmarkerResult.
   * Maps blendshape categories to the FaceSignals interface fields.
   */
  function extractFaceSignals(
    result: import('@mediapipe/tasks-vision').FaceLandmarkerResult,
  ): FaceSignals[] {
    if (!result.faceBlendshapes || result.faceBlendshapes.length === 0) {
      return [];
    }

    return result.faceBlendshapes.map((blendshapes, faceIdx) => {
      const getScore = (name: string): number => {
        const cat = blendshapes.categories.find(c => c.categoryName === name);
        return cat?.score ?? 0;
      };

      // Head pose from facial transformation matrix (if available).
      let headYaw = 0;
      let headPitch = 0;
      if (result.facialTransformationMatrixes?.[faceIdx]) {
        const matrix = result.facialTransformationMatrixes[faceIdx].data;
        // Extract Euler angles from the 4×4 column-major rotation matrix.
        // Pitch (X-axis rotation), Yaw (Y-axis rotation).
        if (matrix && matrix.length >= 16) {
          headPitch = Math.atan2(-matrix[9], matrix[10]) * (180 / Math.PI);
          headYaw = Math.atan2(matrix[8], matrix[0]) * (180 / Math.PI);
        }
      }

      return {
        eyeBlinkLeft: getScore('eyeBlink_L'),
        eyeBlinkRight: getScore('eyeBlink_R'),
        mouthSmileLeft: getScore('mouthSmile_L'),
        mouthSmileRight: getScore('mouthSmile_R'),
        headYaw,
        headPitch,
      };
    });
  }

  /**
   * Computes the bounding box enclosing all detected faces, in canvas pixel coordinates.
   * Returns null when no face detections are available.
   */
  function extractAggregatedBbox(
    result: import('@mediapipe/tasks-vision').FaceLandmarkerResult,
    canvasWidth: number,
    canvasHeight: number,
  ): BoundingBox | null {
    // Use face landmarks to compute bounding box (landmarks are normalized [0,1]).
    if (!result.faceLandmarks || result.faceLandmarks.length === 0) {
      return null;
    }

    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (const landmarks of result.faceLandmarks) {
      for (const pt of landmarks) {
        if (pt.x < minX) minX = pt.x;
        if (pt.y < minY) minY = pt.y;
        if (pt.x > maxX) maxX = pt.x;
        if (pt.y > maxY) maxY = pt.y;
      }
    }

    if (!isFinite(minX)) return null;

    return {
      x: minX * canvasWidth,
      y: minY * canvasHeight,
      width: (maxX - minX) * canvasWidth,
      height: (maxY - minY) * canvasHeight,
    };
  }

  // ── Actions ───────────────────────────────────────────────────────────────

  function handleConfirm(): void {
    onConfirm(candidates[selectedIndex]);
  }
</script>

<div class="best-shot-picker">
  <h2 class="best-shot-picker__title">{$_('photos.bestShot.title')}</h2>

  <!-- AC2: scoring status message -->
  {#if scoringState === 'loading'}
    <p class="best-shot-picker__status best-shot-picker__status--loading">
      {$_('photos.bestShot.modelLoading')}
    </p>
  {:else if scoringState === 'scoring'}
    <p class="best-shot-picker__status best-shot-picker__status--loading">
      {$_('photos.bestShot.scoring')}
    </p>
  {:else if scoringState === 'error'}
    <p class="best-shot-picker__status best-shot-picker__status--error" role="alert">
      {scoringError ?? $_('photos.bestShot.scoringError')}
    </p>
  {:else}
    <p class="best-shot-picker__status best-shot-picker__status--done">
      {$_('photos.bestShot.instruction')}
    </p>
  {/if}

  <!-- AC1: candidate gallery -->
  <div class="best-shot-picker__gallery">
    {#each thumbnailUrls as url, i (i)}
      {@const isSelected = selectedIndex === i}
      {@const isBest = scoringState === 'done' && i === bestIndex}
      {@const score = scores[i]}
      {@const hasNoFace = score !== undefined && score.faceCount === 0}

      <button
        class="best-shot-picker__candidate"
        class:best-shot-picker__candidate--selected={isSelected}
        class:best-shot-picker__candidate--best={isBest && !isSelected}
        onclick={() => { selectedIndex = i; }}
        aria-pressed={isSelected}
        aria-label={isSelected
          ? $_('photos.bestShot.selectedLabel', { values: { n: i + 1 } })
          : $_('photos.bestShot.candidateLabel', { values: { n: i + 1 } })}
        type="button"
      >
        <img
          class="best-shot-picker__thumb"
          src={url}
          alt={$_('photos.bestShot.candidateAlt', { values: { n: i + 1 } })}
          draggable="false"
        />

        <!-- Badge row: best-suggestion label + no-face warning -->
        <div class="best-shot-picker__badges">
          {#if isBest}
            <span class="best-shot-picker__badge best-shot-picker__badge--best">
              {$_('photos.bestShot.bestLabel')}
            </span>
          {/if}
          {#if hasNoFace}
            <span class="best-shot-picker__badge best-shot-picker__badge--noface" role="img"
                  aria-label={$_('photos.bestShot.noFace')}>
              {$_('photos.bestShot.noFace')}
            </span>
          {/if}
        </div>

        <!-- Selection ring indicator -->
        {#if isSelected}
          <div class="best-shot-picker__selected-ring" aria-hidden="true"></div>
        {/if}
      </button>
    {/each}
  </div>

  <!-- AC2/AC3: action buttons -->
  <div class="best-shot-picker__actions">
    <button
      class="btn btn--primary"
      onclick={handleConfirm}
      disabled={isScoring}
      type="button"
    >
      {$_('photos.bestShot.confirm')}
    </button>
    <button
      class="btn btn--secondary"
      onclick={onCancel}
      type="button"
    >
      {$_('photos.bestShot.cancel')}
    </button>
  </div>
</div>

<style>
  .best-shot-picker {
    display: flex;
    flex-direction: column;
    gap: 1rem;
  }

  .best-shot-picker__title {
    font-size: 1.1rem;
    font-weight: 600;
    margin: 0;
    color: #2c3e50;
  }

  .best-shot-picker__status {
    font-size: 0.9rem;
    margin: 0;
  }

  .best-shot-picker__status--loading {
    color: #7f8c8d;
    font-style: italic;
  }

  .best-shot-picker__status--error {
    color: #c0392b;
  }

  .best-shot-picker__status--done {
    color: #555;
  }

  /* ── Gallery ────────────────────────────────────────────────────────────── */

  .best-shot-picker__gallery {
    display: flex;
    flex-wrap: wrap;
    gap: 0.75rem;
  }

  .best-shot-picker__candidate {
    position: relative;
    border: 3px solid #ddd;
    border-radius: 6px;
    background: #fff;
    cursor: pointer;
    padding: 0;
    overflow: hidden;
    width: 140px;
    flex-shrink: 0;
    transition: border-color 0.15s;
  }

  .best-shot-picker__candidate:hover {
    border-color: #2980b9;
  }

  .best-shot-picker__candidate--selected {
    border-color: #2980b9;
    box-shadow: 0 0 0 2px rgba(41, 128, 185, 0.35);
  }

  .best-shot-picker__candidate--best {
    border-color: #27ae60;
  }

  .best-shot-picker__thumb {
    display: block;
    width: 140px;
    height: 100px;
    object-fit: cover;
  }

  /* ── Badges ─────────────────────────────────────────────────────────────── */

  .best-shot-picker__badges {
    display: flex;
    flex-wrap: wrap;
    gap: 0.25rem;
    padding: 0.3rem 0.4rem;
    min-height: 1.6rem;
    background: rgba(255, 255, 255, 0.92);
  }

  .best-shot-picker__badge {
    font-size: 0.7rem;
    font-weight: 600;
    padding: 0.1rem 0.35rem;
    border-radius: 3px;
    white-space: nowrap;
    line-height: 1.4;
  }

  .best-shot-picker__badge--best {
    background: #27ae60;
    color: #fff;
  }

  .best-shot-picker__badge--noface {
    background: #f39c12;
    color: #fff;
  }

  /* Selected ring overlay */
  .best-shot-picker__selected-ring {
    position: absolute;
    inset: 0;
    border: 3px solid #2980b9;
    border-radius: 3px;
    pointer-events: none;
  }

  /* ── Actions ────────────────────────────────────────────────────────────── */

  .best-shot-picker__actions {
    display: flex;
    gap: 0.75rem;
    align-items: center;
  }

  .btn {
    cursor: pointer;
    border: none;
    border-radius: 4px;
    padding: 0.5rem 1.25rem;
    font-size: 0.9rem;
    white-space: nowrap;
  }

  .btn:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }

  .btn--primary {
    background: #2980b9;
    color: #fff;
  }

  .btn--primary:hover:not(:disabled) {
    background: #2471a3;
  }

  .btn--secondary {
    background: #ecf0f1;
    color: #2c3e50;
    border: 1px solid #bdc3c7;
  }

  .btn--secondary:hover {
    background: #d5dbdb;
  }
</style>
