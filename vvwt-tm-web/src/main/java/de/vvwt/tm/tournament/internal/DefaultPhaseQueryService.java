package de.vvwt.tm.tournament.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tournament.MatchState;
import de.vvwt.tm.tournament.PhaseOverviewResponse;
import de.vvwt.tm.tournament.PhaseQueryService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link PhaseQueryService} (E48S05, DEC-35).
 *
 * <h2>Design notes</h2>
 *
 * <ul>
 *   <li><b>AC-IMPL-MATCH-COUNTS-BY-STATE:</b> a single {@code GROUP BY} aggregation query fetches
 *       all match counts for all phases of a given tournament at once — no N+1 loop.
 *   <li><b>AC-IMPL-GAMEMODE-FROM-DRAFT-JSON / AC-PHASE-LIST-DEFENSIVE:</b> {@code gameMode} is
 *       derived from the tournament's {@code draft_json} column by parsing the JSON and looking up
 *       the section whose {@code sectionNumber} matches the phase's {@code sequenceNumber}. If
 *       {@code draft_json} is {@code null}, blank, or unparseable, or if no matching section is
 *       found, the field is set to {@code null} — response status remains 200 (no hard fail).
 *   <li><b>DEC-26 Rule 2:</b> this service returns plain projection records; the controller (web
 *       module) serializes them directly — no DAO read path is used in tests to verify writes.
 * </ul>
 *
 * @see PhaseQueryService
 * @see <a href="DEC-35">DEC-35 — impl in tournament.internal</a>
 * @see <a href="E48S05">E48S05 — AC-IMPL-PHASE-LIST-ENDPOINT, AC-IMPL-MATCH-COUNTS-BY-STATE</a>
 */
@Service("tmPhaseQueryService")
public class DefaultPhaseQueryService implements PhaseQueryService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /** SQL: list all phases for a tournament, ordered by sequenceNumber asc. */
    private static final String SELECT_PHASES =
            "SELECT id, sequence_number, description, status, current_lap_number"
                    + " FROM phase WHERE tournament_id = ? ORDER BY sequence_number";

    /** SQL: aggregate match counts by state for all phases of a tournament — single query. */
    private static final String SELECT_MATCH_COUNTS =
            "SELECT phase_id, state, COUNT(*) AS cnt"
                    + " FROM match WHERE tournament_id = ?"
                    + " GROUP BY phase_id, state";

    /** SQL: fetch draft_json column for a tournament (may be null). */
    private static final String SELECT_DRAFT_JSON =
            "SELECT draft_json FROM tournament WHERE id = ?";

    /** SQL: check tournament existence. */
    private static final String EXISTS_TOURNAMENT = "SELECT COUNT(*) FROM tournament WHERE id = ?";

    public DefaultPhaseQueryService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** {@inheritDoc} */
    @Override
    public List<PhaseOverviewResponse> listPhasesWithCounts(UUID tournamentId) {
        // 1. Fetch gameMode map from draft_json (AC-IMPL-GAMEMODE-FROM-DRAFT-JSON)
        Map<Integer, String> gameModeBySection = buildGameModeMap(tournamentId);

        // 2. Aggregate match counts for all phases in a single query
        // (AC-IMPL-MATCH-COUNTS-BY-STATE)
        Map<UUID, Map<String, Long>> countsByPhase = fetchMatchCounts(tournamentId);

        // 3. Fetch phases ordered by sequenceNumber
        List<PhaseOverviewResponse> results = new ArrayList<>();
        jdbc.query(
                SELECT_PHASES,
                rs -> {
                    UUID phaseId = rs.getObject("id", UUID.class);
                    int seq = rs.getInt("sequence_number");
                    String description = rs.getString("description");
                    String status = rs.getString("status");
                    int currentLap = rs.getInt("current_lap_number");

                    // AC-IMPL-GAMEMODE-FROM-DRAFT-JSON: look up by sequenceNumber
                    String gameMode = gameModeBySection.get(seq);

                    // Default all known states to 0 so the map is complete
                    Map<String, Long> counts = initMatchCounts();
                    Map<String, Long> phaseActual = countsByPhase.get(phaseId);
                    if (phaseActual != null) {
                        counts.putAll(phaseActual);
                    }

                    results.add(
                            new PhaseOverviewResponse(
                                    phaseId,
                                    seq,
                                    description,
                                    status,
                                    gameMode,
                                    currentLap,
                                    counts));
                },
                tournamentId);

        return results;
    }

    /** {@inheritDoc} */
    @Override
    public boolean tournamentExists(UUID tournamentId) {
        Integer count = jdbc.queryForObject(EXISTS_TOURNAMENT, Integer.class, tournamentId);
        return count != null && count > 0;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Parses tournament.draft_json and returns a map of sectionNumber → gameMode.
     *
     * <p>Returns an empty map on any failure (AC-PHASE-LIST-DEFENSIVE: null/parse-error → null
     * gameMode fields, response status stays 200).
     */
    private Map<Integer, String> buildGameModeMap(UUID tournamentId) {
        Map<Integer, String> result = new HashMap<>();
        try {
            String draftJson = jdbc.queryForObject(SELECT_DRAFT_JSON, String.class, tournamentId);
            if (draftJson == null || draftJson.isBlank()) {
                return result;
            }
            JsonNode root = objectMapper.readTree(draftJson);
            JsonNode sections = root.path("sections");
            if (!sections.isArray()) {
                return result;
            }
            for (JsonNode section : sections) {
                JsonNode sectionNum = section.path("sectionNumber");
                JsonNode gameMode = section.path("gameMode");
                if (!sectionNum.isMissingNode() && !gameMode.isMissingNode()) {
                    result.put(sectionNum.asInt(), gameMode.asText(null));
                }
            }
        } catch (Exception e) {
            // AC-PHASE-LIST-DEFENSIVE: any error → return empty map (gameMode fields null)
        }
        return result;
    }

    /**
     * Fetches match counts grouped by phase_id and state in a single query.
     *
     * @return map of phaseId → {stateName → count}
     */
    private Map<UUID, Map<String, Long>> fetchMatchCounts(UUID tournamentId) {
        Map<UUID, Map<String, Long>> result = new LinkedHashMap<>();
        jdbc.query(
                SELECT_MATCH_COUNTS,
                rs -> {
                    UUID phaseId = rs.getObject("phase_id", UUID.class);
                    int stateCode = rs.getInt("state");
                    long cnt = rs.getLong("cnt");
                    String stateName = resolveStateName(stateCode);
                    result.computeIfAbsent(phaseId, k -> new LinkedHashMap<>())
                            .merge(stateName, cnt, Long::sum);
                },
                tournamentId);
        return result;
    }

    /**
     * Resolves a legacy integer match state code to its enum name.
     *
     * <p>Falls back to {@code "UNKNOWN_" + code} for any code not in the canonical set (defensive).
     */
    private static String resolveStateName(int code) {
        try {
            return MatchState.fromLegacyCode(code).name();
        } catch (IllegalArgumentException e) {
            return "UNKNOWN_" + code;
        }
    }

    /**
     * Returns a mutable map pre-populated with all known {@link MatchState} values mapped to 0L.
     *
     * <p>This ensures the response always contains all state keys, even when a phase has no matches
     * in a given state — consistent JSON shape per AC-IMPL-MATCH-COUNTS-BY-STATE.
     */
    private static Map<String, Long> initMatchCounts() {
        Map<String, Long> map = new LinkedHashMap<>();
        for (MatchState s : MatchState.values()) {
            map.put(s.name(), 0L);
        }
        return map;
    }
}
