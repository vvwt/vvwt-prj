/**
 * API client for certificate generation readiness and team listing (E12S07).
 *
 * Wraps the E12S07 readiness endpoint and the E05S05 team listing endpoint:
 *   GET  /api/tournaments/{id}/certificate-readiness  — readiness status (E12S07 AC1)
 *   GET  /api/tournaments/{id}/teams                  — team list for preview selector (AC4)
 *
 * All authenticated endpoints rely on browser-cached basic-auth via same-origin credentials
 * (consistent with the existing `apiFetch` pattern across other stores).
 *
 * @see CertificateReadinessController (Java, E12S07)
 * @see TeamController (Java, E05S05)
 */

/**
 * Mirrors CertificateReadinessResponse DTO (E12S07).
 */
export interface CertificateReadiness {
    templateUploaded: boolean;
    standingsAvailable: boolean;
    totalTeams: number;
    teamsWithPhoto: number;
}

// ---------------------------------------------------------------------------
// getReadiness — GET /api/tournaments/{id}/certificate-readiness
// ---------------------------------------------------------------------------

/**
 * Fetches the certificate readiness status for a tournament.
 *
 * @param tournamentId  UUID of the tournament
 * @returns             CertificateReadiness with all four readiness signals
 * @throws              Error with message on HTTP error or network failure
 */
export async function getReadiness(tournamentId: string): Promise<CertificateReadiness> {
    const response = await fetch(
        `/api/tournaments/${tournamentId}/certificate-readiness`,
        { credentials: 'same-origin' },
    );
    if (!response.ok) {
        throw new Error(await extractErrorMessage(response));
    }
    return response.json() as Promise<CertificateReadiness>;
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

/**
 * Extracts a user-readable error message from an HTTP error response.
 * Tries to parse a JSON body with a `message` field (Spring `ApiErrorResponse`),
 * falls back to HTTP status code.
 */
async function extractErrorMessage(response: Response): Promise<string> {
    try {
        const body = (await response.json()) as { message?: string };
        if (body.message) return body.message;
    } catch {
        /* ignore — fall through to status code */
    }
    return `HTTP ${response.status} ${response.statusText}`;
}
