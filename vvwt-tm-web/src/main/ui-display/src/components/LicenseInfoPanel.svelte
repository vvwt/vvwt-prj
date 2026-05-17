<!--
  SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
  SPDX-License-Identifier: AGPL-3.0-or-later
-->
<script lang="ts">
  /**
   * AGPL §13 source-code offer panel for the Display SPA (E59S03 AC3/AC5).
   *
   * Rendered inside a modal-style overlay when the operator opens the info panel
   * from the persistently-visible entry point on the live view (SidebarHeader area).
   * The panel does not consume live-view layout space on its own — it overlays.
   *
   * AC5: label text makes clear this links to the application source code and that
   * the application is licensed under AGPL-3.0-or-later; text is in English.
   * AC8: DEC-76 SPDX header is carried by this file.
   */

  interface Props {
    /** Handler called when the user closes the panel. */
    onClose: () => void;
  }

  const { onClose }: Props = $props();
</script>

<!--
  E59S03 AC3: Info panel / overlay for the §13 source-code offer.
  Positioned as a fixed overlay so the live overview view loses no layout space.
  The entry point (button in SidebarHeader) is a persistently-rendered control
  on the live view; clicking it opens this panel.
-->
<div
  class="license-info-overlay"
  role="dialog"
  aria-modal="true"
  aria-label="Source code information"
>
  <div class="license-info-panel">
    <button
      class="license-info-panel__close"
      type="button"
      aria-label="Close source code information"
      onclick={onClose}
    >✕</button>

    <h2 class="license-info-panel__title">Source Code</h2>

    <p class="license-info-panel__body">
      This application is licensed under
      <strong>AGPL-3.0-or-later</strong>
      (GNU Affero General Public License, version 3.0 or later).
      The source code is available at:
    </p>

    <a
      href="https://github.com/vvwt/vvwt-prj"
      target="_blank"
      rel="noopener noreferrer"
      class="license-info-panel__link"
    >https://github.com/vvwt/vvwt-prj</a>
  </div>
</div>

<style>
  /* Fixed overlay — covers the screen without disturbing the live view layout */
  .license-info-overlay {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.55);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 200;
  }

  .license-info-panel {
    background: #fff;
    border-radius: 6px;
    padding: 1.5rem 2rem;
    max-width: 420px;
    width: 90%;
    position: relative;
    box-shadow: 0 4px 24px rgba(0, 0, 0, 0.25);
  }

  .license-info-panel__close {
    position: absolute;
    top: 0.75rem;
    right: 0.75rem;
    background: none;
    border: none;
    font-size: 1.1rem;
    cursor: pointer;
    color: #555;
    padding: 0.2rem 0.4rem;
    line-height: 1;
  }

  .license-info-panel__close:hover {
    color: #111;
  }

  .license-info-panel__title {
    margin: 0 0 0.75rem;
    font-size: 1.1rem;
    color: #2c3e50;
  }

  .license-info-panel__body {
    font-size: 0.9rem;
    color: #444;
    line-height: 1.5;
    margin: 0 0 0.75rem;
  }

  .license-info-panel__link {
    display: block;
    font-size: 0.9rem;
    color: #2980b9;
    word-break: break-all;
    text-decoration: none;
  }

  .license-info-panel__link:hover {
    text-decoration: underline;
  }
</style>
