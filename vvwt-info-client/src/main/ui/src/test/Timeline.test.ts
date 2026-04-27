/**
 * AC1 (testing), AC4 (ScheduleEntry subtypes rendering).
 * DEC-22 Iron Law: written BEFORE Timeline.svelte exists (RED state).
 * Story: E38S08.
 */

import { describe, it, expect, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/svelte';
import Timeline from '../lib/components/Timeline.svelte';

const matchEntry = { type: 'MATCH', id: 'm1', homeTeamName: 'Team Alpha', awayTeamName: 'Team Beta', roundNumber: 1 };
const specialEntry = { type: 'SPECIAL_APPOINTMENT', id: 'sa1', title: 'Siegerehrung' };
const pauseEntry = { type: 'PAUSE', id: 'p1', label: 'Mittagspause' };

afterEach(() => cleanup());

describe('Timeline component (AC4)', () => {
  it('renders Match entry with DE label "Spiel"', () => {
    const { getByText } = render(Timeline, { props: { entries: [matchEntry] } });
    expect(getByText(/Spiel/)).toBeTruthy();
  });

  it('renders SpecialAppointment with its title', () => {
    const { getByText } = render(Timeline, { props: { entries: [specialEntry] } });
    expect(getByText('Siegerehrung')).toBeTruthy();
  });

  it('renders Pause entry with DE label "Pause"', () => {
    const { getByText } = render(Timeline, { props: { entries: [pauseEntry] } });
    expect(getByText(/Pause/)).toBeTruthy();
  });

  it('renders all three subtypes together (AC4 positive)', () => {
    const { getByText } = render(Timeline, {
      props: { entries: [matchEntry, specialEntry, pauseEntry] },
    });
    expect(getByText(/Spiel/)).toBeTruthy();
    expect(getByText('Siegerehrung')).toBeTruthy();
    expect(getByText(/Pause/)).toBeTruthy();
  });

  it('renders empty schedule without error', () => {
    const { container } = render(Timeline, { props: { entries: [] } });
    expect(container).toBeTruthy();
  });
});
