/**
 * Page header shell store — Story E47S01 (AC1).
 *
 * Provides a writable store that route components use to register their
 * page title, primary action buttons, and navigation context (back-arrow,
 * tournament name) with the persistent brand-header in App.svelte.
 *
 * Shell mechanism choice: writable store (option 2 per E47S01 Story Notes).
 * Rationale: matches existing project pattern (multiple *Store.ts files in
 * src/stores/); supports dynamic updates when tournament name resolves
 * asynchronously; most predictable and testable idiom in this codebase.
 *
 * DEC-2: Svelte store — no SvelteKit primitives, no new npm dependency.
 * DEC-22: TDD Iron Law — RED-first tests exist in App.test.ts, Teams.test.ts,
 *         Tournaments.test.ts, Devices.test.ts, and all sub-route test files.
 */

import { writable } from 'svelte/store';

/**
 * A single header action button.
 *
 * - `label`: visible text at desktop viewport
 * - `ariaLabel`: accessible name used as aria-label for icon-only buttons at
 *                narrow viewport (≤768px, AC7). Must be the i18n key VALUE
 *                (i.e., the translated German string), not the key itself.
 * - `handler`: onclick callback
 * - `variant`: 'primary' | 'secondary' | 'danger'
 *   - non-danger variants → icon-only button at narrow viewport (D-9)
 *   - 'danger' variant → Overflow-Menu item at narrow viewport (D-11)
 */
export interface HeaderAction {
  label: string;
  ariaLabel: string;
  handler: () => void;
  variant: 'primary' | 'secondary' | 'danger';
}

/**
 * The state registered by a route component into the persistent header.
 *
 * - `title`: page title string (already translated)
 * - `backTo`: resolved parent path for the back-arrow (null = no back-arrow, top-level route)
 * - `tournamentId`: UUID for tournament-name lookup; null for top-level routes
 * - `actions`: ordered list of header action buttons
 */
export interface PageHeaderState {
  title: string;
  backTo: string | null;
  tournamentId: string | null;
  actions: HeaderAction[];
}

const INITIAL_STATE: PageHeaderState = {
  title: '',
  backTo: null,
  tournamentId: null,
  actions: [],
};

/**
 * Writable store — route components set() this on mount and reset on destroy.
 * App.svelte subscribes and renders the registered values in the brand-header.
 */
export const pageHeader = writable<PageHeaderState>(INITIAL_STATE);

/** Convenience reset — routes call this in onDestroy to avoid stale state between navigations. */
export function resetPageHeader(): void {
  pageHeader.set(INITIAL_STATE);
}
