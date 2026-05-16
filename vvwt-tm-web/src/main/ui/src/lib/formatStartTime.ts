// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
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
