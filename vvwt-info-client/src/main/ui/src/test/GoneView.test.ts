// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/svelte';
import GoneView from '../lib/components/GoneView.svelte';

afterEach(() => cleanup());

describe('GoneView component (AC6)', () => {
  it('shows DE message about expired link (AC6)', () => {
    const { getByText } = render(GoneView);
    expect(getByText(/Dieser Link ist nicht mehr gültig/)).toBeTruthy();
  });

  it('mentions asking the organizer', () => {
    const { getByText } = render(GoneView);
    expect(getByText(/Veranstalter/)).toBeTruthy();
  });

  it('does not leak any schedule data (AC6 — no prior state visible)', () => {
    const { container } = render(GoneView);
    // The GoneView renders no schedule entries
    const scheduleItems = container.querySelectorAll('[data-testid="schedule-entry"]');
    expect(scheduleItems).toHaveLength(0);
  });
});
