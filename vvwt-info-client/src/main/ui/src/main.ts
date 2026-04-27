/**
 * vvwt-info-client entry point.
 *
 * Story E38S01 — minimal scaffold (DEC-2: Svelte 5 + TypeScript + Vite, no SvelteKit).
 * Business logic (per-team timeline view, WebSocket reader) is E38S06/E38S08 scope.
 */
import { mount } from 'svelte';
import App from './App.svelte';

const app = mount(App, {
    target: document.getElementById('app')!,
});

export default app;
