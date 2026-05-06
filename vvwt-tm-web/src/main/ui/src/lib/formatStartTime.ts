/**
 * Formats a LocalDateTime string (or Date object) plus a minute offset as "HH:mm".
 *
 * The `base` argument is typically a LocalDateTime ISO string without TZ designator
 * (e.g. "2026-06-01T09:00:00"). The JS Date constructor parses it as LOCAL wall-clock
 * time, which is the correct semantics: tournament plannedStartTime is local time.
 *
 * Returns "" on any parse error (graceful degradation per AC-ERROR-HANDLING-INVALID-START-TIME).
 *
 * Distinct from the existing formatTime() helper at DraftConfig.svelte:309 which operates
 * on LocalTime "HH:mm:ss" strings only — NOT reusable here (Story E48S11 Context paragraph 3).
 *
 * AC-IMPL-FRONTEND-PHASE-START-TIME (E48S11)
 */
export function formatStartTime(base: Date | string, offsetMinutes: number): string {
  try {
    const d = base instanceof Date ? base : new Date(base as string);
    if (isNaN(d.getTime())) return '';
    const totalMs = d.getTime() + offsetMinutes * 60 * 1000;
    const result = new Date(totalMs);
    const hh = String(result.getHours()).padStart(2, '0');
    const mm = String(result.getMinutes()).padStart(2, '0');
    return `${hh}:${mm}`;
  } catch {
    return '';
  }
}
