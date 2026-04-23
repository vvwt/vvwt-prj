/**
 * API client for tournament-scoped team photo management (E12S03, E23S05 Cutover-1).
 *
 * Wraps the E23S05 REST endpoints (URL renamed at Cutover-1 per DEC-21 + DEC-40 precedent):
 *   POST   /api/photo/tournaments/{tournamentId}/teams/{teamId}  — upload (or replace) photo
 *   GET    /api/photo/tournaments/{tournamentId}/teams/{teamId}  — retrieve photo (binary)
 *   DELETE /api/photo/tournaments/{tournamentId}/teams/{teamId}  — delete photo
 *
 * Upload uses XMLHttpRequest to expose upload progress (consistent with audioStore.ts pattern).
 * Photo retrieval is done via a URL (used as <img src>) rather than fetching the binary in JS.
 *
 * @see TeamPhotoController  (Java, E23S05)
 * @see AudioStore           (E11S06 — pattern reference)
 */

/** Photo metadata returned by the upload endpoint (E12S02). */
export interface PhotoMetadata {
    filename: string;
    sizeBytes: number;
    uploadedAt: string;
}

// ---------------------------------------------------------------------------
// getPhotoUrl — construct URL for <img src>
// ---------------------------------------------------------------------------

/**
 * Returns the URL for a team's photo (for use as <img src>).
 * Appends a cache-busting query parameter to ensure the browser
 * re-fetches the image after upload or replace.
 *
 * @param tournamentId  UUID of the tournament
 * @param teamId        UUID of the team
 * @param bust          Optional cache-busting value (e.g. Date.now())
 * @returns             URL string for use in <img src>
 */
export function getPhotoUrl(tournamentId: string, teamId: string, bust?: number): string {
    const base = `/api/photo/tournaments/${tournamentId}/teams/${teamId}`;
    return bust !== undefined ? `${base}?t=${bust}` : base;
}

// ---------------------------------------------------------------------------
// uploadPhoto — POST /api/photo/tournaments/{tournamentId}/teams/{teamId}
// ---------------------------------------------------------------------------

/**
 * Uploads (or replaces) the photo for a team in a tournament (AC3, AC5).
 *
 * Uses XMLHttpRequest to expose upload progress to the caller via the optional
 * `onProgress` callback.
 *
 * @param tournamentId  UUID of the tournament
 * @param teamId        UUID of the team
 * @param file          The File object from the file picker (.jpg, .jpeg, or .png)
 * @param onProgress    Optional callback receiving progress percentage 0–100
 * @returns             PhotoMetadata returned by the server on success (200)
 * @throws              Error with user-friendly message on failure
 */
export function uploadPhoto(
    tournamentId: string,
    teamId: string,
    file: File,
    onProgress?: (percent: number) => void,
): Promise<PhotoMetadata> {
    return new Promise((resolve, reject) => {
        const formData = new FormData();
        formData.append('file', file, file.name);

        const xhr = new XMLHttpRequest();
        xhr.withCredentials = true;   // same-origin basic-auth

        if (onProgress && xhr.upload) {
            xhr.upload.addEventListener('progress', (event) => {
                if (event.lengthComputable) {
                    onProgress(Math.round((event.loaded / event.total) * 100));
                }
            });
        }

        xhr.addEventListener('load', () => {
            if (xhr.status === 200) {
                try {
                    resolve(JSON.parse(xhr.responseText) as PhotoMetadata);
                } catch {
                    reject(new Error('Invalid response from server'));
                }
            } else {
                let msg = `HTTP ${xhr.status}`;
                try {
                    const body = JSON.parse(xhr.responseText) as { message?: string };
                    if (body.message) msg = body.message;
                } catch {
                    /* ignore parse error */
                }
                reject(new Error(msg));
            }
        });

        xhr.addEventListener('error', () => {
            reject(new Error('Network error during upload'));
        });

        xhr.addEventListener('abort', () => {
            reject(new Error('Upload cancelled'));
        });

        xhr.open('POST', `/api/photo/tournaments/${tournamentId}/teams/${teamId}`);
        xhr.send(formData);
    });
}

// ---------------------------------------------------------------------------
// deletePhoto — DELETE /api/photo/tournaments/{tournamentId}/teams/{teamId}
// ---------------------------------------------------------------------------

/**
 * Deletes the photo for a team in a tournament (AC6).
 *
 * @param tournamentId  UUID of the tournament
 * @param teamId        UUID of the team
 * @throws              Error with message if the photo does not exist (404) or on network failure
 */
export async function deletePhoto(tournamentId: string, teamId: string): Promise<void> {
    const response = await fetch(
        `/api/photo/tournaments/${tournamentId}/teams/${teamId}`,
        {
            method: 'DELETE',
            credentials: 'same-origin',
        }
    );
    if (!response.ok) {
        throw new Error(await extractErrorMessage(response));
    }
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

/**
 * Extracts a user-readable error message from an HTTP error response.
 * Tries to parse a JSON body with a `message` field (Spring `ApiErrorResponse`),
 * falls back to HTTP status text.
 */
async function extractErrorMessage(response: Response): Promise<string> {
    try {
        const body = (await response.json()) as { message?: string };
        if (body.message) return body.message;
    } catch {
        /* ignore — fall through to status text */
    }
    return `HTTP ${response.status} ${response.statusText}`;
}
