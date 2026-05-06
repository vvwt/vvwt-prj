/**
 * Pure URL-builder function for the canonical Wave-2 timer page URL.
 *
 * Extracted from TimerLink.svelte (E11S08 AC5) to enable Vitest production-import
 * testing without Svelte component rendering. Canonical path per TimerViewController
 * (E26S03): /timer/tournaments/{tournamentId}
 *
 * Note: The function lives here (not inside TimerLink.svelte) because Svelte 5
 * module-level `export function` declarations inside a component's <script> block
 * are treated as component-instance exports (props/accessors), not as plain ESM
 * exports accessible via bare-import in Vitest. A separate .ts module is the
 * idiomatic solution for Vitest-importable production logic extracted from a
 * Svelte component.
 *
 * @param origin       The window origin (e.g. 'http://localhost:8080')
 * @param tournamentId The tournament UUID
 * @returns            The full canonical timer URL, or '' when tournamentId is empty
 */
export function buildTimerUrl(origin: string, tournamentId: string): string {
    if (!tournamentId) return '';
    return `${origin}/timer/tournaments/${tournamentId}`;
}
