<!--
  SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
  SPDX-License-Identifier: AGPL-3.0-or-later
-->
<script lang="ts">
  /**
   * Overflow-Menu component — Story E47S01 (AC8, AC9, Brief D-11).
   *
   * Renders a "More actions" icon trigger button (aria-haspopup="menu").
   * On click: toggles a dropdown menu with full-text action labels.
   * Used exclusively for destructive (btn--danger) header actions at narrow viewports.
   *
   * Keyboard contract (AC9):
   *   - Enter/Space: open/activate
   *   - Arrow Down/Up: navigate menu items
   *   - Escape: close menu
   *   - Tab / click-outside: close menu
   *
   * DEC-2: pure Svelte 5 + TypeScript, no new npm dependency, ≤50 LOC per Brief S-6.
   * DEC-22: TDD Iron Law — RED-first tests in Devices.test.ts (AC8).
   */

  interface MenuAction {
    label: string;
    handler: () => void;
  }

  interface Props {
    actions: MenuAction[];
    triggerLabel: string;  // aria-label for the trigger button (common.moreActions value)
  }

  let { actions, triggerLabel }: Props = $props();

  let open = $state(false);
  let triggerEl = $state<HTMLButtonElement | null>(null);
  let menuEl = $state<HTMLUListElement | null>(null);

  function toggle(): void {
    open = !open;
  }

  function close(): void {
    open = false;
  }

  function handleItemClick(handler: () => void): void {
    close();
    handler();
  }

  function handleTriggerKeydown(e: KeyboardEvent): void {
    if (e.key === 'ArrowDown' || e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      open = true;
      // Focus first menu item after open
      setTimeout(() => {
        const first = menuEl?.querySelector<HTMLButtonElement>('[role="menuitem"]');
        first?.focus();
      }, 0);
    }
    if (e.key === 'Escape') close();
  }

  function handleMenuKeydown(e: KeyboardEvent): void {
    const items = Array.from(menuEl?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]') ?? []);
    const idx = items.indexOf(document.activeElement as HTMLButtonElement);
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      items[(idx + 1) % items.length]?.focus();
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      items[(idx - 1 + items.length) % items.length]?.focus();
    } else if (e.key === 'Escape' || e.key === 'Tab') {
      close();
      triggerEl?.focus();
    }
  }

  function handleClickOutside(e: MouseEvent): void {
    if (triggerEl && !triggerEl.contains(e.target as Node) &&
        menuEl && !menuEl.contains(e.target as Node)) {
      close();
    }
  }
</script>

<svelte:window onclick={handleClickOutside} />

<div class="overflow-menu">
  <button
    bind:this={triggerEl}
    class="btn overflow-menu__trigger"
    aria-haspopup="menu"
    aria-expanded={open}
    aria-label={triggerLabel}
    onclick={toggle}
    onkeydown={handleTriggerKeydown}
    type="button"
  >
    <!-- Vertical ellipsis icon -->
    <span class="overflow-menu__icon" aria-hidden="true">⋮</span>
  </button>

  {#if open}
    <ul
      bind:this={menuEl}
      class="overflow-menu__list"
      role="menu"
      onkeydown={handleMenuKeydown}
    >
      {#each actions as action}
        <li role="none">
          <button
            role="menuitem"
            class="overflow-menu__item"
            type="button"
            onclick={() => handleItemClick(action.handler)}
          >
            {action.label}
          </button>
        </li>
      {/each}
    </ul>
  {/if}
</div>

<style>
  .overflow-menu {
    position: relative;
    display: inline-block;
  }

  .overflow-menu__trigger {
    background: #ecf0f1;
    border: 1px solid #bdc3c7;
    border-radius: 4px;
    padding: 0.4rem 0.6rem;
    font-size: 1.1rem;
    cursor: pointer;
    line-height: 1;
    color: #2c3e50;
  }

  .overflow-menu__trigger:hover {
    background: #dce0e1;
  }

  .overflow-menu__icon {
    display: inline-block;
    width: 1em;
    text-align: center;
  }

  .overflow-menu__list {
    position: absolute;
    right: 0;
    top: calc(100% + 4px);
    background: #fff;
    border: 1px solid #ccc;
    border-radius: 4px;
    box-shadow: 0 2px 8px rgba(0,0,0,0.15);
    list-style: none;
    margin: 0;
    padding: 0.25rem 0;
    min-width: 10rem;
    z-index: 200;
  }

  .overflow-menu__item {
    width: 100%;
    background: none;
    border: none;
    text-align: left;
    padding: 0.5rem 1rem;
    font-size: 0.9rem;
    cursor: pointer;
    color: #2c3e50;
  }

  .overflow-menu__item:hover {
    background: #f5f5f5;
  }
</style>
