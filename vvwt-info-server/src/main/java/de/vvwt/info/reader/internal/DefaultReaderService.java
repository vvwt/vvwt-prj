package de.vvwt.info.reader.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.info.dto.event.DomainEvent;
import de.vvwt.info.dto.snapshot.TeamEntry;
import de.vvwt.info.dto.snapshot.TournamentSnapshot;
import de.vvwt.info.persistence.tournament.TournamentDao;
import de.vvwt.info.persistence.tournament.TournamentDeltaDao;
import de.vvwt.info.persistence.tournament.TournamentDeltaRecord;
import de.vvwt.info.persistence.tournament.TournamentRecord;
import de.vvwt.info.reader.ReaderService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link ReaderService} (E38S06).
 *
 * <p>Orchestrates token validation (via {@link HmacTokenValidator}), per-team view projection (via
 * {@link TeamViewProjection}), and delta retrieval from {@link TournamentDeltaDao}.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S06.story.md">E38S06</a>
 */
@Service
class DefaultReaderService implements ReaderService {

    private static final Logger log = LoggerFactory.getLogger(DefaultReaderService.class);

    /** Grace window: 24 hours from superseded_at. */
    private static final long GRACE_HOURS = 24L;

    private final TournamentDao tournamentDao;
    private final TournamentDeltaDao deltaDao;
    private final HmacTokenValidator hmacValidator;
    private final TeamViewProjection teamViewProjection;
    private final ObjectMapper objectMapper;

    DefaultReaderService(
            TournamentDao tournamentDao,
            TournamentDeltaDao deltaDao,
            HmacTokenValidator hmacValidator,
            TeamViewProjection teamViewProjection,
            ObjectMapper objectMapper) {
        this.tournamentDao = tournamentDao;
        this.deltaDao = deltaDao;
        this.hmacValidator = hmacValidator;
        this.teamViewProjection = teamViewProjection;
        this.objectMapper = objectMapper;
    }

    @Override
    public TokenValidationResult validateTokens(String tournamentToken, String teamToken) {
        // AC9: shape validation first — delegate to HmacTokenValidator
        // AC10: uniform 410 for unknown tournament_token
        Optional<TournamentRecord> found = tournamentDao.findByTournamentToken(tournamentToken);
        if (found.isEmpty()) {
            return new TokenValidationResult.Invalid();
        }

        TournamentRecord tournament = found.get();

        // AC5/AC11: grace window check — if superseded_at is set, check >= boundary
        if (tournament.supersededAt() != null) {
            LocalDateTime supersededAt = tournament.supersededAt();
            Instant supersededInstant = supersededAt.atZone(ZoneId.systemDefault()).toInstant();
            Instant graceEnd = supersededInstant.plus(GRACE_HOURS, ChronoUnit.HOURS);

            // >= boundary: if now is AT or after graceEnd → 410
            if (Instant.now().compareTo(graceEnd) >= 0) {
                return new TokenValidationResult.Superseded();
            }
            // Within grace window — validate token and serve frozen state
            Optional<TeamEntry> teamEntry = resolveTeamEntry(tournament, teamToken);
            if (teamEntry.isEmpty()) {
                return new TokenValidationResult.Invalid();
            }
            return new TokenValidationResult.Valid(tournament, teamEntry.get(), true);
        }

        // Active tournament — validate HMAC token
        Optional<TeamEntry> teamEntry = resolveTeamEntry(tournament, teamToken);
        if (teamEntry.isEmpty()) {
            return new TokenValidationResult.Invalid();
        }
        return new TokenValidationResult.Valid(tournament, teamEntry.get(), false);
    }

    /** Resolves the team entry by HMAC validation against tournament.state teams. */
    private Optional<TeamEntry> resolveTeamEntry(TournamentRecord tournament, String teamToken) {
        if (tournament.state() == null || tournament.state().isBlank()) {
            // No state yet → no teams registered → 410
            return Optional.empty();
        }

        TournamentSnapshot snapshot;
        try {
            // state is stored as Envelope<TournamentSnapshot> JSON
            @SuppressWarnings("unchecked")
            de.vvwt.info.dto.envelope.Envelope<TournamentSnapshot> envelope =
                    objectMapper.readValue(
                            tournament.state(),
                            objectMapper
                                    .getTypeFactory()
                                    .constructParametricType(
                                            de.vvwt.info.dto.envelope.Envelope.class,
                                            TournamentSnapshot.class));
            snapshot = envelope.payload();
        } catch (Exception e) {
            log.error(
                    "Failed to parse tournament state for tournament {}: {}",
                    tournament.tournamentId(),
                    e.getMessage());
            return Optional.empty();
        }

        if (snapshot == null || snapshot.teams() == null || snapshot.teams().isEmpty()) {
            return Optional.empty();
        }

        List<String> teamUuids = snapshot.teams().stream().map(TeamEntry::teamId).toList();

        Optional<String> matchedUuid =
                hmacValidator.validateAndResolveTeam(
                        tournament.perTournamentSecret(), teamUuids, teamToken);

        return matchedUuid.flatMap(
                uuid -> snapshot.teams().stream().filter(t -> uuid.equals(t.teamId())).findFirst());
    }

    @Override
    public Optional<TournamentSnapshot> getTeamSnapshot(
            TournamentRecord tournament, TeamEntry teamEntry) {
        if (tournament.state() == null || tournament.state().isBlank()) {
            return Optional.empty();
        }

        try {
            @SuppressWarnings("unchecked")
            de.vvwt.info.dto.envelope.Envelope<TournamentSnapshot> envelope =
                    objectMapper.readValue(
                            tournament.state(),
                            objectMapper
                                    .getTypeFactory()
                                    .constructParametricType(
                                            de.vvwt.info.dto.envelope.Envelope.class,
                                            TournamentSnapshot.class));
            TournamentSnapshot full = envelope.payload();
            if (full == null) {
                return Optional.empty();
            }
            TournamentSnapshot projected = teamViewProjection.project(full, teamEntry);
            return Optional.of(projected);
        } catch (Exception e) {
            log.error("Failed to parse tournament state for snapshot: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<DomainEvent> getDeltasSince(TournamentRecord tournament, long sinceSeq) {
        List<TournamentDeltaRecord> records =
                deltaDao.findDeltasSince(tournament.tournamentId(), sinceSeq);
        return records.stream()
                .map(this::deserializeDelta)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    @Override
    public boolean hasSequenceGap(TournamentRecord tournament, long sinceSeq) {
        if (sinceSeq < 0) {
            return true;
        }
        List<TournamentDeltaRecord> records =
                deltaDao.findDeltasSince(tournament.tournamentId(), sinceSeq);
        if (records.isEmpty()) {
            // No deltas after sinceSeq — could be up to date (no gap) or post-trim (gap)
            // If sinceSeq < last_applied_seq and no records found, there's a gap
            return sinceSeq < tournament.lastAppliedSeq()
                    && records.isEmpty()
                    && deltaDao.findDeltasSince(tournament.tournamentId(), 0).stream()
                                    .anyMatch(r -> r.id().seq() > sinceSeq)
                            == false;
        }
        // Check if first returned seq == sinceSeq + 1 (no gap)
        long firstReturnedSeq = records.getFirst().id().seq();
        return firstReturnedSeq != sinceSeq + 1;
    }

    private Optional<DomainEvent> deserializeDelta(TournamentDeltaRecord record) {
        try {
            return Optional.of(objectMapper.readValue(record.eventPayload(), DomainEvent.class));
        } catch (Exception e) {
            log.error(
                    "Failed to deserialize delta seq {} for tournament {}: {}",
                    record.id().seq(),
                    record.id().tournamentId(),
                    e.getMessage());
            return Optional.empty();
        }
    }
}
