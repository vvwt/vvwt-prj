/**
 * AC1 (testing), AC5 (supersede UX rendering).
 * DEC-22 Iron Law: written BEFORE SupersedeView.svelte exists (RED state).
 * Story: E38S08.
 */

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
