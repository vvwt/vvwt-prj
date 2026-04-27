/**
 * AC1 (testing), AC14 (network-error + backoff indicator), AC15 (stale-data indicator, distinct).
 * DEC-22 Iron Law: written BEFORE ConnectionStatus.svelte exists (RED state).
 * Story: E38S08.
 */

import { describe, it, expect, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/svelte';
import ConnectionStatus from '../lib/components/ConnectionStatus.svelte';

afterEach(() => cleanup());

describe('ConnectionStatus component (AC14, AC15)', () => {
  it('shows connection-lost indicator with DE message when connectionLost=true (AC14)', () => {
    const { getByTestId, getByText } = render(ConnectionStatus, {
      props: { connectionLost: true, dataStale: false },
    });
    const indicator = getByTestId('connection-lost-indicator');
    expect(indicator).toBeTruthy();
    expect(getByText(/Verbindung verloren/)).toBeTruthy();
  });

  it('shows stale-data indicator with distinct DE message when dataStale=true (AC15)', () => {
    const { getByTestId, getByText } = render(ConnectionStatus, {
      props: { connectionLost: false, dataStale: true },
    });
    const indicator = getByTestId('stale-data-indicator');
    expect(indicator).toBeTruthy();
    expect(getByText(/veraltet/)).toBeTruthy();
  });

  it('connection-lost and stale-data have DISTINCT testid (AC15)', () => {
    const { getByTestId } = render(ConnectionStatus, {
      props: { connectionLost: true, dataStale: true },
    });
    // Both present — distinct DOM testid (AC15 contract)
    expect(getByTestId('connection-lost-indicator')).toBeTruthy();
    expect(getByTestId('stale-data-indicator')).toBeTruthy();
  });

  it('no indicator shown when connection is fine and data is fresh', () => {
    const { queryByTestId } = render(ConnectionStatus, {
      props: { connectionLost: false, dataStale: false },
    });
    expect(queryByTestId('connection-lost-indicator')).toBeNull();
    expect(queryByTestId('stale-data-indicator')).toBeNull();
  });
});
