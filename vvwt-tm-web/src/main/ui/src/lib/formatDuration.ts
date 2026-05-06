/**
 * Formats integer minutes as H:MM (variable-digit hours, always 2-digit minutes with leading zero).
 *
 * Examples per Brief Q-4 (Story E48S11):
 *   0   → "0:00"
 *   45  → "0:45"
 *   60  → "1:00"
 *   90  → "1:30"
 *   125 → "2:05"
 *   600 → "10:00"
 *
 * AC-IMPL-FRONTEND-FORMAT-DURATION (E48S11)
 */
export function formatDuration(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return `${h}:${String(m).padStart(2, '0')}`;
}
