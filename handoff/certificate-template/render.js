#!/usr/bin/env node
/**
 * VVW · Urkunden-Renderer
 * ─────────────────────────────────────────────────────────────
 * Liest urkunden.json + urkunde.mustache.html, rendert eine
 * HTML-Datei pro Eintrag in den Ordner ./out/.
 *
 * Jede Urkunde ist self-contained: Schrift + Foto inline als
 * Base64. Die Datei kann ohne Internet im Browser geöffnet und
 * gedruckt werden (Cmd/Ctrl+P → "Als PDF speichern", DIN A4 quer).
 *
 * Aufruf:
 *   node render.js                      # nutzt urkunden.json
 *   node render.js my-urkunden.json     # eigene Datenquelle
 *
 * Abhängigkeiten:
 *   npm install mustache
 *
 * Datenformat (urkunden.json):
 *   [
 *     {
 *       "turnier_name":       "26. Frühjahrsturnier",
 *       "turnier_untertitel": "Volleyballverein Werratal",
 *       "platz":              "1",
 *       "mannschaft":         "Smashing Pumpkins",
 *       "verein_und_datum":   "Volleyballverein Werratal am 18.05.2019",
 *       "foto":               "team-photo.jpg"
 *     },
 *     ...
 *   ]
 *
 * Das `foto`-Feld ist ein Pfad relativ zum aktuellen Verzeichnis;
 * JPEG oder PNG. Wird als Base64 ins Template eingebettet.
 */

const fs       = require('fs');
const path     = require('path');
const Mustache = require('mustache');

const ROOT       = __dirname;
const TEMPLATE   = path.join(ROOT, 'urkunde.mustache.html');
const FONTS_JSON = path.join(ROOT, 'fonts.base64.json');
const OUT_DIR    = path.join(ROOT, 'out');

// ─── Helpers ────────────────────────────────────────────────────
function slugify(s) {
  return String(s)
    .toLowerCase()
    .normalize('NFD').replace(/[\u0300-\u036f]/g, '')
    .replace(/[ä]/g, 'ae').replace(/[ö]/g, 'oe').replace(/[ü]/g, 'ue').replace(/[ß]/g, 'ss')
    .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60);
}

function fileToBase64(p) {
  const buf = fs.readFileSync(p);
  return buf.toString('base64');
}

// ─── Load shared assets once ────────────────────────────────────
const template = fs.readFileSync(TEMPLATE, 'utf8');
const fonts    = JSON.parse(fs.readFileSync(FONTS_JSON, 'utf8'));

// Mustache: don't HTML-escape the base64 blobs (tilde-prefix in template would also work)
Mustache.escape = function (text) { return text; };

// ─── Photo cache: same file shared across many entries ──────────
const photoCache = new Map();
function getPhotoBase64(relPath) {
  const abs = path.resolve(ROOT, relPath);
  if (!photoCache.has(abs)) {
    photoCache.set(abs, fileToBase64(abs));
  }
  return photoCache.get(abs);
}

// ─── Render ─────────────────────────────────────────────────────
const dataPath = process.argv[2] || path.join(ROOT, 'urkunden.json');
const data     = JSON.parse(fs.readFileSync(dataPath, 'utf8'));

if (!fs.existsSync(OUT_DIR)) fs.mkdirSync(OUT_DIR, { recursive: true });

let count = 0;
for (const entry of data) {
  const view = {
    ...entry,
    foto_base64:     getPhotoBase64(entry.foto),
    font_700_base64: fonts['700'],
    font_800_base64: fonts['800'],
  };

  const html = Mustache.render(template, view);
  const fname = `urkunde-${String(entry.platz).padStart(2, '0')}-${slugify(entry.mannschaft)}.html`;
  fs.writeFileSync(path.join(OUT_DIR, fname), html, 'utf8');
  console.log(`✓ ${fname}`);
  count++;
}

console.log(`\n${count} Urkunde${count === 1 ? '' : 'n'} → ${path.relative(process.cwd(), OUT_DIR)}/`);
