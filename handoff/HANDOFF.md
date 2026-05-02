# VVW · TM Logo-System — Handoff

Stand: Mai 2026  
Bestandteile: 3 App-Lockups, 2 Hex-Icons, 10 Favicon-PNGs

---

## Marke & Konzept

Das Logo-System verbindet die VVW-Wortmarke mit der App-Familie Tournament Manager. Das **Hexagon** dient als Container und differenziert die App-Marke optisch von Standard-App-Icons (die meist Squircles sind). Im Hexagon sitzt ein **stilisierter Pokal** als zentrales Bildmotiv.

Die App-Wortmarken folgen dem Schema **„T 🏆 M"** — das Hex-Icon ersetzt einen Buchstaben in der Mitte:
- **T**ournament **M**anager (TM)
- **I**nformation (I·NFO)
- **S**lot-**O**pt **D**ispatcher (S·OD)

Diese Konstruktion erlaubt es, jede App eigenständig zu kennzeichnen und gleichzeitig ihre Zugehörigkeit zur Familie sichtbar zu machen.

## Farben

| Token | Hex | Verwendung |
|---|---|---|
| `--vvw-blue` | `#1956a8` | Primärfarbe, Hex-Container (TM, SOD) |
| `--vvw-yellow` | `#ffcb1f` | Sekundärfarbe, Pokal, Hex-Container (Info) |
| `--vvw-ink` | `#0a1f3a` | VVW-Wortmarke, Body Text |

**Regel:** Bei der Info-App ist das Verhältnis invertiert (gelbes Hex, blauer Pokal) — das macht Info auf einen Blick als „die andere" App in der Familie erkennbar.

## Typografie

- **VVW-Wortmarke:** TT Norms Pro Bold (in den SVGs als Pfade hinterlegt — keine Font-Abhängigkeit beim Einsatz)
- **Suffix-Tags („Tournament Manager", „Live Information", „Slot-Opt Dispatcher"):** Plus Jakarta Sans 500 (in den SVGs als Pfade hinterlegt)

## Pokal-Position (Optical Centering)

Der Pokal sitzt im Hex bei `dy=3` (von der mathematischen Mitte nach unten verschoben). Das kompensiert die optische Schwerkraft der unteren Hex-Spitze und lässt das Motiv ausgewogen wirken.

---

## Dateien

```
handoff/
├── logos/
│   ├── vvw-tm-logo.svg          553×240  — Tournament Manager
│   ├── vvw-info-logo.svg        442×240  — Live Information (gelb invertiert)
│   └── vvw-sod-logo.svg         539×240  — Slot-Opt Dispatcher
├── icons/
│   ├── vvw-icon-blue.svg        100×100  — Standard-Hex
│   └── vvw-icon-yellow.svg      100×100  — Info-Variante (gelb invertiert)
└── favicons/
    ├── vvw-favicon-blue-{16,32,64,128,256}.png
    └── vvw-favicon-yellow-{16,32,64,128,256}.png
```

## Einsatz

### Web (Favicon)
```html
<link rel="icon" type="image/svg+xml" href="vvw-icon-blue.svg">
<link rel="icon" type="image/png" sizes="32x32" href="vvw-favicon-blue-32.png">
<link rel="apple-touch-icon" sizes="180x180" href="vvw-favicon-blue-256.png">
```

### App-Header
Lockup-SVG bei mind. 32px Höhe einsetzen. Unter 32px nur Hex-Icon ohne Wortmarke verwenden.

### Mindestgrößen
- Lockup: 120px Breite
- Hex-Icon allein: 16px (Favicon-Mindestgröße)

### Schutzraum
Mindestens **1× Hex-Höhe** (≈ Höhe des Hex-Containers) als Freifläche um das Lockup. Keine Elemente, kein Bildhintergrund mit hohem Kontrast unmittelbar angrenzend.

### Hintergründe
- Hell (Empfehlung): `#f4f1ea`, `#ffffff`, `#fafafa`
- Auf farbigen Hintergründen die Lockups in einem hellen Container platzieren (Card mit `#ffffff`)
- **Nicht** auf gemustertem Foto-Hintergrund ohne Container einsetzen

## Was nicht tun

- Pokal nicht durch andere Symbole ersetzen
- Hex nicht zu Squircle/Kreis verändern (das ist das Differenzierungsmerkmal)
- Farben nicht tauschen (z.B. roter Pokal)
- Suffix-Tag nicht eigenständig vergrößern oder andere Schrift verwenden
- Keine Schatten oder Effekte auf das Hex anwenden

---

## Kontakt / Quelle

Originalprojekt: VVW Logo Canvas (HTML)  
Alle Vektorpfade sind in den SVGs als reine `<path>`-Elemente hinterlegt — keine externen Font- oder Bild-Abhängigkeiten.
