# VVW · Urkunden-Template

Mustache-Template + Renderer für VVW-Tournament-Manager-Urkunden.
Jede gerenderte HTML-Datei ist **self-contained** — Schrift und Foto sind als Base64 eingebettet, keine externen Ressourcen.

## Inhalt

| Datei | Zweck |
|---|---|
| `urkunde.mustache.html` | Mustache-Template (das Layout) |
| `fonts.base64.json` | Plus Jakarta Sans 700 + 800 als Base64 WOFF2 |
| `team-photo.jpg` | Beispiel-Mannschaftsfoto (JPEG q85, 1600×1069 px, 528 KB) |
| `team-photo.jpg.base64.txt` | Beispiel-Foto schon als Base64 (Referenz) |
| `urkunden.json` | Beispiel-Datensatz (3 Mannschaften) |
| `render.js` | Node-Skript, das alles zusammenrendert |
| `package.json` | Mustache-Dependency |
| `PlusJakartaSans-Bold.woff2` / `…-ExtraBold.woff2` | Original-Fonts (Quelle für `fonts.base64.json`) |

## Quickstart

```bash
cd handoff/certificate-template
npm install
npm run render
open out/urkunde-01-smashing-pumpkins.html
```

Drucken in die PDF: Browser öffnen → Cmd/Ctrl+P → **Layout: Querformat**, **Format: A4**, **Ränder: keine**, Hintergrundgrafiken aktivieren.

## Eigene Daten

`urkunden.json` ist ein Array. Jeder Eintrag rendert eine Urkunde:

```json
{
  "turnier_name":       "26. Frühjahrsturnier",
  "verein":             "Volleyballverein Werratal",
  "platz":              "1",
  "mannschaft":         "Smashing Pumpkins",
  "datum":              "18.05.2019",
  "foto":               "team-photo.jpg"
}
```

`foto` ist ein Pfad relativ zum Template-Ordner. Das Skript liest die Datei, encodiert sie als Base64 und cached pro Pfad — gleicher Dateipfad wird also nur einmal gelesen, auch wenn 24 Mannschaften dasselbe Mannschaftsfoto teilen.

```bash
node render.js my-andere-daten.json
```

## In die Webanwendung integrieren

Wenn Sie das Rendering selbst in Ihrer Anwendung machen wollen, brauchen Sie nur:

1. `urkunde.mustache.html` — als Template-String laden
2. `fonts.base64.json` — einmal beim Build/Start einlesen
3. Pro Urkunde: Foto als Base64 encodieren

Die Mustache-Variablen, die das Template erwartet:

| Variable | Typ | Beispiel |
|---|---|---|
| `turnier_name` | String | `"26. Frühjahrsturnier"` |
| `turnier_untertitel` | String | `"Volleyballverein Werratal"` |
| `platz` | String/Number | `"1"` oder `1` |
| `mannschaft` | String | `"Smashing Pumpkins"` |
| `verein_und_datum` | String | `"Volleyballverein Werratal am 18.05.2019"` |
| `foto_base64` | Base64-String (ohne `data:`-Prefix) | `"/9j/4AAQSkZJRg..."` |
| `font_700_base64` | Base64-String (Plus Jakarta Sans Bold) | `"d09GMgAB..."` |
| `font_800_base64` | Base64-String (Plus Jakarta Sans ExtraBold) | `"d09GMgAB..."` |

**Wichtig:** Mustache standardmäßig HTML-escaped alle Variablen (`{{x}}`). Für die Base64-Blobs ist das egal (kein `<`, `>`, `&`), aber **Mannschaftsnamen mit `&`** würden falsch escaped wenn doch. Das mitgelieferte `render.js` deaktiviert HTML-Escape global (`Mustache.escape = x => x`); falls Sie es selbst integrieren, entweder das auch tun oder die Tripple-Mustache `{{{var}}}` in den Variablen verwenden, bei denen Sie Escape vermeiden wollen.

## Foto-Größe

Die Beispiel-`team-photo.jpg` ist **1600 px Breite, JPEG Quality 85** (~530 KB). Das ist der gute Punkt:

- Pro Urkunde-HTML ≈ 850 KB (Foto + 2× Font + Markup)
- Druckqualität auf A4 quer → 1600 px verteilt auf 297 mm = **137 dpi** — für Fotos auf Urkunden völlig ausreichend
- Bei einem Turnier mit 24 Teams → ~20 MB statt ~400 MB (PNG-Original)

Wenn Sie höhere Druckqualität brauchen: in `render.js` einfach das Originalfoto referenzieren (lassen Sie das JPEG-Re-Encoding weg) oder skalieren Sie auf 2400 px hoch.

## Schriftlizenz

Plus Jakarta Sans steht unter der **SIL Open Font License 1.1** — frei nutzbar, auch in eingebetteter Form. Quelle: <https://github.com/tokotype/PlusJakartaSans>.

Verwendete Schnitte: **Bold (700)** und **ExtraBold (800)**. Reichen für das Layout. Wenn Sie weitere brauchen (z. B. Regular für längere Fließtexte), `fonts.base64.json` um weitere Weights ergänzen und im Template einen weiteren `@font-face`-Block einfügen.
