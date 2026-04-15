package de.vvwt.tm.infrastructure.web.certificate;

/**
 * JSON response for the certificate readiness endpoint (E12S07 AC1).
 *
 * <p>Aggregates all four readiness signals the UI needs to render the readiness checklist
 * in a single round-trip, avoiding multiple sequential API calls.
 *
 * @param templateUploaded    {@code true} if a certificate template has been uploaded for the tournament
 * @param standingsAvailable  {@code true} if the final phase exists and has at least one rated TeamAvatar
 * @param totalTeams          number of teams in the tournament
 * @param teamsWithPhoto      number of teams that have a photo uploaded
 */
public record CertificateReadinessResponse(
        boolean templateUploaded,
        boolean standingsAvailable,
        int totalTeams,
        int teamsWithPhoto
) {
}
