// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { mount } from 'svelte';
import App from './App.svelte';
import { initI18n } from './lib/i18n.js';

// AC5, AC9: initialize i18n (locale detection + fallback) before mount
initI18n();

// Mount the root Svelte component
mount(App, { target: document.getElementById('app')! });
