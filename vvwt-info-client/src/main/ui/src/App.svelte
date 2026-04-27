<script lang="ts">
    /**
     * vvwt-info App — minimal placeholder component.
     *
     * Story E38S01 — DEC-2 Svelte 5 scaffold.
     * Per-team timeline view and WebSocket integration are E38S06/E38S08 scope.
     * All user-visible strings are in German and externalised here as constants
     * (full i18n externalisation is E38S08 scope; this stub satisfies AC11's
     * "minimal Hello, vvwt-info placeholder" requirement).
     */

    // DE-only strings (externalised per AC11 requirement, E38S08 expands this)
    const titel = 'vvwt-info';
    const untertitel = 'Öffentliches Teilnehmerportal';
    const platzhalter = 'Wird geladen …';
</script>

<main>
    <h1>{titel}</h1>
    <p>{untertitel}</p>
    <p class="placeholder">{platzhalter}</p>
</main>

<style>
    main {
        font-family: sans-serif;
        padding: 2rem;
        text-align: center;
    }

    .placeholder {
        color: #888;
        font-style: italic;
    }
</style>
