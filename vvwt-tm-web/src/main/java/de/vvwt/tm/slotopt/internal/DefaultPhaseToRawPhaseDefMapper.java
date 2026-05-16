package de.vvwt.tm.slotopt.internal;

import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import de.vvwt.slotopt.worker.types.PositionTuple;
import de.vvwt.slotopt.worker.types.RawPhaseDef;
import de.vvwt.slotopt.worker.types.RawRow;
import de.vvwt.slotopt.worker.types.StructuralFingerprint;
import de.vvwt.slotopt.worker.types.TransformResult;
import de.vvwt.tm.slotopt.MappingResult;
import de.vvwt.tm.slotopt.PhaseToRawPhaseDefMapper;
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
 * Default implementation of {@link PhaseToRawPhaseDefMapper}.
 *
 * <p>Forward mapper: converts TM domain objects ({@link Match}, {@link TeamAvatar}) for a given
 * phase into a {@link RawPhaseDef} and associated structures needed by the slot-optimization
 * compute kernel.
 *
 * <h2>DEC-9 compliance</h2>
 *
 * <p>UUIDs never cross the optimizer service boundary. The mapper extracts the structural identity
 * tuple {@code (groupNumber, groupPosition)} from each {@link TeamAvatar} and constructs {@link
 * PositionTuple}s from them.
 *
 * @see de.vvwt.tm.slotopt.SlotResultApplicator
 * @since E57S01 (DEC-58/DEC-72 interface extraction: renamed from PhaseToRawPhaseDefMapper, moved
 *     to slotopt.internal, implements {@link PhaseToRawPhaseDefMapper})
 */
@Service
public class DefaultPhaseToRawPhaseDefMapper implements PhaseToRawPhaseDefMapper {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultPhaseToRawPhaseDefMapper.class);

    private final TeamAvatarRepository teamAvatarRepository;
    private final MatchRepository matchRepository;

    @Value("${tm.slotopt.fallback.field-count:3}")
    private int fieldCount;

    /**
     * Constructs the mapper with the required repositories.
     *
     * @param teamAvatarRepository tenant-scoped repository for TeamAvatar entities
     * @param matchRepository tenant-scoped repository for Match entities
     */
    public DefaultPhaseToRawPhaseDefMapper(
            TeamAvatarRepository teamAvatarRepository, MatchRepository matchRepository) {
        this.teamAvatarRepository = teamAvatarRepository;
        this.matchRepository = matchRepository;
    }

    /** {@inheritDoc} */
    @Override
    public MappingResult map(UUID phaseId) {
        if (phaseId == null) {
            throw new IllegalArgumentException("phaseId must not be null");
        }

        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(phaseId);
        List<Match> matches = matchRepository.findByPhaseId(phaseId);

        if (avatars.isEmpty()) {
            throw new IllegalStateException(
                    "No TeamAvatars found for phase "
                            + phaseId
                            + ". Cannot construct RawPhaseDef for an empty phase.");
        }

        if (matches.isEmpty()) {
            throw new IllegalStateException(
                    "No matches found for phase "
                            + phaseId
                            + ". Cannot construct RawPhaseDef with zero rows.");
        }

        Map<UUID, PositionTuple> positionByAvatarId = new HashMap<>(avatars.size() * 2);
        for (TeamAvatar avatar : avatars) {
            positionByAvatarId.put(
                    avatar.getId(),
                    new PositionTuple(avatar.getGroupNumber(), avatar.getGroupPosition()));
        }

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

        List<RawRow> rows = new ArrayList<>(matchesByLap.size());
        List<PositionTuple[]> rawTuplesByRow = new ArrayList<>(matchesByLap.size());
        List<Match> sortedMatches = new ArrayList<>(matches.size());

        for (Map.Entry<Integer, List<Match>> entry : matchesByLap.entrySet()) {
            List<Match> lapMatches = entry.getValue();
            lapMatches.sort(Comparator.comparing(m -> m.getId().toString()));
            sortedMatches.addAll(lapMatches);

            LinkedHashMap<PositionTuple, Boolean> seen = new LinkedHashMap<>();
            for (Match match : lapMatches) {
                seen.put(positionByAvatarId.get(match.getMemberAvatar1Id()), Boolean.TRUE);
                seen.put(positionByAvatarId.get(match.getMemberAvatar2Id()), Boolean.TRUE);
            }
            List<PositionTuple> lapTuples = new ArrayList<>(seen.keySet());
            rows.add(new RawRow(lapTuples));
            rawTuplesByRow.add(lapTuples.toArray(new PositionTuple[0]));
        }

        int auditPhaseId = Math.abs(phaseId.hashCode());

        RawPhaseDef raw = new RawPhaseDef(auditPhaseId, rows.size(), rows);

        TransformResult transformResult = StructuralFingerprint.transform(raw);
        CanonicalPhaseDef canonical = transformResult.canonical();

        Map<PositionTuple, Integer> denseIdByTuple = buildDenseIdMapping(raw);

        int[][] denseIdsByRawRow = new int[rows.size()][];
        for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
            PositionTuple[] tuples = rawTuplesByRow.get(rowIdx);
            int[] ids = new int[tuples.length];
            for (int j = 0; j < tuples.length; j++) {
                ids[j] = denseIdByTuple.get(tuples[j]);
            }
            denseIdsByRawRow[rowIdx] = ids;
        }

        int n = canonical.avatarCount();

        LOG.info(
                "DefaultPhaseToRawPhaseDefMapper: phase={}, avatars={}, matches={}, laps={}, N={}",
                phaseId,
                avatars.size(),
                sortedMatches.size(),
                rows.size(),
                n);

        return new MappingResult(raw, canonical, n, sortedMatches, denseIdsByRawRow);
    }

    /** {@inheritDoc} */
    @Override
    public int getFieldCount() {
        return fieldCount;
    }

    /**
     * Rebuilds the PositionTuple-to-dense-ID mapping from a {@link RawPhaseDef}.
     *
     * <p>Mirrors the first step of {@link StructuralFingerprint#canonicalize(RawPhaseDef)}: collect
     * distinct tuples, sort lexicographically (group asc, pos asc), assign dense IDs in order.
     *
     * <p>This is a static utility method retained on the implementation class — static methods
     * cannot be on interfaces. Test code should reference this as {@code
     * DefaultPhaseToRawPhaseDefMapper.buildDenseIdMapping(raw)}.
     *
     * @param raw the raw phase definition
     * @return map from PositionTuple to its dense integer ID
     */
    public static Map<PositionTuple, Integer> buildDenseIdMapping(RawPhaseDef raw) {
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
