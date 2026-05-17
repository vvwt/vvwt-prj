<!--
  SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
  SPDX-License-Identifier: AGPL-3.0-or-later
-->
<script lang="ts">
  /**
   * GoneView component — 410 Gone UX (AC6).
   * Shows DE message about expired link; renders NO prior schedule data (AC6 no-state-leakage).
   * AC16: all strings from de.ts; no inline literals in template.
   * Story: E38S08.
   */
  import de from '../i18n/de.js';
</script>

<div class="gone-view">
  <p class="expired-message">{de['link.expired']}</p>
</div>

<style>
  .gone-view {
    font-family: sans-serif;
    padding: 2rem;
    text-align: center;
    max-width: 480px;
    margin: 4rem auto;
  }

  .expired-message {
    color: #6b7280;
    line-height: 1.6;
    font-size: 1rem;
  }
</style>
