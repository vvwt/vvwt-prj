// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
const de: Record<string, string> = {
  // App / loading
  'app.title': 'vvwt-info',
  'app.loading': 'Wird geladen …',
  'app.subtitle': 'Öffentliches Teilnehmerportal',

  // Supersede UX — tournament ended within 24h grace (AC5)
  'tournament.ended': 'Dieses Turnier ist beendet',
  'tournament.ended.subtitle': 'Die Ergebnisse und der Zeitplan sind zur Ansicht verfügbar.',

  // 410 Gone UX — link expired after grace (AC6)
  'link.expired':
    'Dieser Link ist nicht mehr gültig — bitte fragen Sie Ihren Veranstalter nach dem aktuellen Link.',

  // Connection indicators (AC14)
  'connection.lost': 'Verbindung verloren — Wiederverbindung …',
  'connection.reconnecting': 'Verbindung wird wiederhergestellt …',

  // Stale data indicator (AC15, distinct from connection-lost)
  'data.stale': 'Daten möglicherweise veraltet',

  // Schedule entry labels (AC4)
  'schedule.match': 'Spiel',
  'schedule.pause': 'Pause',
  'schedule.special': 'Sondertermin',

  // Live indicator
  'live.indicator': 'Live',

  // Round label
  'schedule.round': 'Runde',

  // Separator used between team names in match display
  'schedule.vs.separator': ' — ',

  // E59S03 AC2/AC5: AGPL §13 source-code offer (English per AC5)
  'license.sourceCode': 'Source code (AGPL-3.0-or-later)',
};

export default de;
