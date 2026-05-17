// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';

describe('TimerAudio.svelte — AC3: per-page header removed (E47S01)', () => {
  it('TimerAudio.svelte source does NOT contain .timer-audio__header class', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TimerAudio.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).not.toContain('timer-audio__header');
  });

  it('TimerAudio.svelte source registers title via pageHeader store', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TimerAudio.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('pageHeader');
    expect(source).toContain('audio.title');
  });
});

describe('TimerAudio.svelte — AC5: backTo registered (E47S01)', () => {
  it('TimerAudio.svelte source registers backTo in pageHeader', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TimerAudio.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('backTo');
  });
});

describe('TimerAudio.svelte — AC6: tournamentId registered (E47S01)', () => {
  it('TimerAudio.svelte source passes tournamentId to pageHeader', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TimerAudio.svelte');
    const source = fs.readFileSync(src, 'utf8');
    const pageHeaderCall = source.match(/pageHeader\.set\(\{([^}]*)\}/s)?.[1] ?? '';
    expect(pageHeaderCall).toContain('tournamentId');
  });
});

describe('TimerAudio.svelte — AC12: pop() back-button removed (E47S01)', () => {
  it('TimerAudio.svelte source has NO button template with audio.backButton', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TimerAudio.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).not.toMatch(/<button[^>]*>\s*\{[^}]*audio\.backButton/);
  });
});
