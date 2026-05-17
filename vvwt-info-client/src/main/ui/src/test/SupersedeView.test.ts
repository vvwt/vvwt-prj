// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/svelte';
import SupersedeView from '../lib/components/SupersedeView.svelte';

afterEach(() => cleanup());

describe('SupersedeView component (AC5)', () => {
  it('shows DE message "Dieses Turnier ist beendet"', () => {
    const { getByText } = render(SupersedeView);
    expect(getByText(/Dieses Turnier ist beendet/)).toBeTruthy();
  });

  it('does not show auto-update spinner (AC5)', () => {
    const { container } = render(SupersedeView);
    const spinner = container.querySelector('[data-testid="live-update-indicator"]');
    expect(spinner).toBeNull();
  });
});
