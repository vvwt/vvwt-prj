package de.vvwt.tm.slotopt;

import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import de.vvwt.slotopt.worker.types.PositionTuple;
import de.vvwt.slotopt.worker.types.RawPhaseDef;
import de.vvwt.slotopt.worker.types.RawRow;
import de.vvwt.slotopt.worker.types.StructuralFingerprint;
import de.vvwt.slotopt.worker.types.TransformResult;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Forward mapper: converts TM domain objects ({@link Match}, {@link TeamAvatar}) for a given phase
 * into a {@link RawPhaseDef} and associated structures needed by the slot-optimization compute
 * kernel.
 *
 * <h2>DEC-9 compliance</h2>
 *
 * <p>UUIDs never cross the optimizer service boundary. The mapper extracts the structural identity
 * tuple {@code (groupNumber, groupPosition)} from each {@link TeamAvatar} and constructs {@link
 * PositionTuple}s from them. No team UUID, name, or identity-bearing attribute is included in the
 * {@link RawPhaseDef}.
 *
 * <h2>Tenant scoping (AC14)</h2>
 *
 * <p>All repository calls delegate to tenant-scoped repositories from E03S05. The TenantContext
 * must be active before calling any method on this service.
 *
 * <h2>Determinism (AC7)</h2>
 *
 * <p>Matches are sorted by UUID (ascending) before being added to the {@link RawPhaseDef}. This
 * guarantees that two calls with the same phase data always produce identical row ordering.
 *
 * @see SlotResultApplicator
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E04S02.story.md">Story
 *     E04S02</a>
 */
@Service
public class PhaseToRawPhaseDefMapper {

    private static final Logger LOG = LoggerFactory.getLogger(PhaseToRawPhaseDefMapper.class);

    private final TeamAvatarRepository teamAvatarRepository;
    private final MatchRepository matchRepository;

    /**
     * Number of courts (fields) for slot assignment. Sourced from the same config property used by
     * FallbackSlotOptimizationClient (AC6). Default: 3.
     */
    @Value("${tm.slotopt.fallback.field-count:3}")
    private int fieldCount;

    /**
     * Constructs the mapper with the required repositories.
     *
     * @param teamAvatarRepository tenant-scoped repository for TeamAvatar entities (AC14)
     * @param matchRepository tenant-scoped repository for Match entities (AC14)
     */
    public PhaseToRawPhaseDefMapper(
            TeamAvatarRepository teamAvatarRepository, MatchRepository matchRepository) {
        this.teamAvatarRepository = teamAvatarRepository;
        this.matchRepository = matchRepository;
    }

    /**
     * Maps all domain objects for the given phase to a {@link MappingResult}.
     *
     * <p>Performs the forward mapping (TM domain → worker-lib types) per AC1–AC3 and AC7–AC10.
     *
     * @param phaseId the UUID of the phase to map
     * @return the mapping result containing the RawPhaseDef, canonical form, N, and match-to-row
     *     index
     * @throws IllegalArgumentException if {@code phaseId} is {@code null}
     * @throws IllegalStateException if no matches exist (AC8), no avatars exist (AC9), or a match
     *     references an unknown avatar (AC10)
     * @throws IllegalStateException if no tenant context is active (E03S05 guard)
     */
    public MappingResult map(UUID phaseId) {
        if (phaseId == null) {
            throw new IllegalArgumentException("phaseId must not be null");
        }

        // AC14: all repository calls use tenant-scoped repositories — TenantContext guard fires
        // here
        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(phaseId);
        List<Match> matches = matchRepository.findByPhaseId(phaseId);

        // AC9: no avatars
        if (avatars.isEmpty()) {
            throw new IllegalStateException(
                    "No TeamAvatars found for phase "
                            + phaseId
                            + ". Cannot construct RawPhaseDef for an empty phase.");
        }

        // AC8: no matches
        if (matches.isEmpty()) {
            throw new IllegalStateException(
                    "No matches found for phase "
                            + phaseId
                            + ". Cannot construct RawPhaseDef with zero rows.");
        }

        // Build avatar-ID-to-PositionTuple index for DEC-9 mapping and AC10 validation
        Map<UUID, PositionTuple> positionByAvatarId = new HashMap<>(avatars.size() * 2);
        for (TeamAvatar avatar : avatars) {
            // DEC-9: structural identity = (groupNumber, groupPosition) — 1-indexed in DB,
            // but PositionTuple expects non-negative values (0 is valid), so pass them directly.
            positionByAvatarId.put(
                    avatar.getId(),
                    new PositionTuple(avatar.getGroupNumber(), avatar.getGroupPosition()));
        }

        // DEC-61 Clause B: aggregate matches into laps — one RawRow per lap (union of avatars)
        // AC7: within each lap, matches are sorted by UUID ascending for deterministic ordering;
        //      laps themselves are ordered ascending by lapNumber.

        // Step 1: validate null lapNumber and group matches by lapNumber
        TreeMap<Integer, List<Match>> matchesByLap = new TreeMap<>();
        for (Match match : matches) {
            Integer lapNum = match.getLapNumber();
            if (lapNum == null) {
                throw new IllegalStateException(
                        "Match "
                                + match.getId()
                                + " in phase "
                                + phaseId
                                + " has null lapNumber. All matches must have a lap assigned"
                                + " before slot-optimization mapping.");
            }
            // AC10: validate that both avatars exist before grouping
            PositionTuple pt1 = positionByAvatarId.get(match.getMemberAvatar1Id());
            if (pt1 == null) {
                throw new IllegalStateException(
                        "Match "
                                + match.getId()
                                + " references memberAvatar1Id="
                                + match.getMemberAvatar1Id()
                                + " which is not present in the loaded avatar set for phase "
                                + phaseId);
            }
            PositionTuple pt2 = positionByAvatarId.get(match.getMemberAvatar2Id());
            if (pt2 == null) {
                throw new IllegalStateException(
                        "Match "
                                + match.getId()
                                + " references memberAvatar2Id="
                                + match.getMemberAvatar2Id()
                                + " which is not present in the loaded avatar set for phase "
                                + phaseId);
            }
            matchesByLap.computeIfAbsent(lapNum, k -> new ArrayList<>()).add(match);
        }

        // Step 2: build one RawRow per lap (union of all PositionTuples in that lap),
        //         and a flat sorted matchOrder for the applicator.
        List<RawRow> rows = new ArrayList<>(matchesByLap.size());
        List<PositionTuple[]> rawTuplesByRow = new ArrayList<>(matchesByLap.size());
        List<Match> sortedMatches = new ArrayList<>(matches.size());

        for (Map.Entry<Integer, List<Match>> entry : matchesByLap.entrySet()) {
            List<Match> lapMatches = entry.getValue();
            // AC7: sort matches within lap by UUID ascending
            lapMatches.sort(Comparator.comparing(m -> m.getId().toString()));
            sortedMatches.addAll(lapMatches);

            // Collect the union of PositionTuples for this lap (all avatars active in lap)
            LinkedHashMap<PositionTuple, Boolean> seen = new LinkedHashMap<>();
            for (Match match : lapMatches) {
                seen.put(positionByAvatarId.get(match.getMemberAvatar1Id()), Boolean.TRUE);
                seen.put(positionByAvatarId.get(match.getMemberAvatar2Id()), Boolean.TRUE);
            }
            List<PositionTuple> lapTuples = new ArrayList<>(seen.keySet());
            rows.add(new RawRow(lapTuples));
            rawTuplesByRow.add(lapTuples.toArray(new PositionTuple[0]));
        }

        // AC1: phaseId field uses deterministic derivation from UUID (audit-only, not in
        // fingerprint)
        int auditPhaseId = Math.abs(phaseId.hashCode());

        RawPhaseDef raw = new RawPhaseDef(auditPhaseId, rows.size(), rows);

        // Canonicalize to get CanonicalPhaseDef and the dense-ID mapping
        TransformResult transformResult = StructuralFingerprint.transform(raw);
        CanonicalPhaseDef canonical = transformResult.canonical();

        // Rebuild the denseId assignment from the canonical form so we can map
        // PositionTuple → denseId for the applicator (mirrors StructuralFingerprint.canonicalize())
        Map<PositionTuple, Integer> denseIdByTuple = buildDenseIdMapping(raw);

        // Populate denseIdsByRawRow: for each lap-row, the dense IDs of all avatars in that lap
        int[][] denseIdsByRawRow = new int[rows.size()][];
        for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
            PositionTuple[] tuples = rawTuplesByRow.get(rowIdx);
            int[] ids = new int[tuples.length];
            for (int j = 0; j < tuples.length; j++) {
                ids[j] = denseIdByTuple.get(tuples[j]);
            }
            denseIdsByRawRow[rowIdx] = ids;
        }

        // AC2: N = number of distinct avatars
        int n = canonical.avatarCount();

        LOG.info(
                "PhaseToRawPhaseDefMapper: phase={}, avatars={}, matches={}, laps={}, N={}",
                phaseId,
                avatars.size(),
                sortedMatches.size(),
                rows.size(),
                n);

        return new MappingResult(raw, canonical, n, sortedMatches, denseIdsByRawRow);
    }

    /**
     * Returns the configured field count for slot assignment (AC6).
     *
     * @return the number of courts (fields)
     */
    public int getFieldCount() {
        return fieldCount;
    }

    /**
     * Rebuilds the PositionTuple-to-dense-ID mapping from a {@link RawPhaseDef}.
     *
     * <p>Mirrors the first step of {@link StructuralFingerprint#canonicalize(RawPhaseDef)}: collect
     * distinct tuples, sort lexicographically (group asc, pos asc), assign dense IDs in order.
     *
     * @param raw the raw phase definition
     * @return map from PositionTuple to its dense integer ID
     */
    public static Map<PositionTuple, Integer> buildDenseIdMapping(RawPhaseDef raw) {
        // Step 1 of StructuralFingerprint: collect distinct tuples, sort lex, assign dense IDs
        java.util.TreeSet<PositionTuple> distinct =
                new java.util.TreeSet<>(
                        Comparator.comparingInt(PositionTuple::group)
                                .thenComparingInt(PositionTuple::pos));
        for (RawRow row : raw.rows()) {
            distinct.addAll(row.positions());
        }
        Map<PositionTuple, Integer> mapping = new java.util.LinkedHashMap<>(distinct.size() * 2);
        int nextId = 0;
        for (PositionTuple tuple : distinct) {
            mapping.put(tuple, nextId++);
        }
        return mapping;
    }
}
