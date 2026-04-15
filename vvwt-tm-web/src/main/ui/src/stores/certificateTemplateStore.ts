/**
 * API client for tournament certificate template management (E12S05).
 *
 * Wraps the E12S04 REST endpoints:
 *   GET    /api/tournaments/{id}/certificate-template/info  — metadata (or null if no template)
 *   POST   /api/tournaments/{id}/certificate-template       — upload (or replace) template
 *   DELETE /api/tournaments/{id}/certificate-template       — delete template
 *   GET    /api/certificate-template/variables              — list available Mustache variables
 *
 * Upload uses XMLHttpRequest instead of fetch to expose upload progress (AC1).
 * All authenticated endpoints rely on browser-cached basic-auth via same-origin credentials
 * (consistent with the existing `apiFetch` pattern and audioStore.ts).
 *
 * @see CertificateTemplateController  (Java, E12S04)
 * @see {@link https://developer.mozilla.org/en-US/docs/Web/API/XMLHttpRequest}
 */

/** Mirrors CertificateTemplateMetadataResponse DTO (E12S04). */
export interface CertificateTemplateMetadata {
    tournamentId: string;
    filename: string;
    /** Detected format: 'html' or 'svg'. */
    format: 'html' | 'svg';
    uploadedAt: string;
    fileSizeBytes: number;
}

/** Mirrors CertificateTemplateVariableResponse DTO (E12S04 AC6). */
export interface CertificateTemplateVariable {
    name: string;
    type: string;
    example: string;
}

// ---------------------------------------------------------------------------
// getTemplateMetadata — GET /api/tournaments/{id}/certificate-template/info
// ---------------------------------------------------------------------------

/**
 * Fetches the current certificate template metadata for a tournament.
 * Returns null if no template has been uploaded (HTTP 404).
 *
 * @param tournamentId  UUID of the tournament
 * @returns             CertificateTemplateMetadata, or null if no template exists
 * @throws              Error with message on non-404 HTTP error or network failure
 */
export async function getTemplateMetadata(
    tournamentId: string,
): Promise<CertificateTemplateMetadata | null> {
    const response = await fetch(
        `/api/tournaments/${tournamentId}/certificate-template/info`,
        { credentials: 'same-origin' },
    );
    if (response.status === 404) {
        return null;
    }
    if (!response.ok) {
        throw new Error(await extractErrorMessage(response));
    }
    return response.json() as Promise<CertificateTemplateMetadata>;
}

// ---------------------------------------------------------------------------
// uploadTemplate — POST /api/tournaments/{id}/certificate-template
// ---------------------------------------------------------------------------

/**
 * Uploads (or replaces) the certificate template for a tournament (AC1, AC3).
 *
 * Uses XMLHttpRequest to expose upload progress to the caller via the optional
 * `onProgress` callback (mirrors uploadAudio from audioStore.ts).
 *
 * @param tournamentId  UUID of the tournament
 * @param file          The File object from the file picker (.html or .svg)
 * @param onProgress    Optional callback receiving progress percentage 0–100
 * @returns             CertificateTemplateMetadata returned by the server on success (200)
 * @throws              Error with a user-friendly message on failure
 */
export function uploadTemplate(
    tournamentId: string,
    file: File,
    onProgress?: (percent: number) => void,
): Promise<CertificateTemplateMetadata> {
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
                    resolve(JSON.parse(xhr.responseText) as CertificateTemplateMetadata);
                } catch {
                    reject(new Error('Invalid response from server'));
                }
            } else {
                let msg = `HTTP ${xhr.status}`;
                try {
                    const body = JSON.parse(xhr.responseText) as { message?: string };
                    if (body.message) msg = body.message;
                } catch {
                    /* ignore parse error — use status code */
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

        xhr.open('POST', `/api/tournaments/${tournamentId}/certificate-template`);
        xhr.send(formData);
    });
}

// ---------------------------------------------------------------------------
// deleteTemplate — DELETE /api/tournaments/{id}/certificate-template
// ---------------------------------------------------------------------------

/**
 * Deletes the certificate template for a tournament (AC4).
 *
 * @param tournamentId  UUID of the tournament
 * @throws              Error with message if the template does not exist (404) or on network failure
 */
export async function deleteTemplate(tournamentId: string): Promise<void> {
    const response = await fetch(
        `/api/tournaments/${tournamentId}/certificate-template`,
        { method: 'DELETE', credentials: 'same-origin' },
    );
    if (!response.ok) {
        throw new Error(await extractErrorMessage(response));
    }
}

// ---------------------------------------------------------------------------
// listVariables — GET /api/certificate-template/variables
// ---------------------------------------------------------------------------

/**
 * Fetches the list of available Mustache template variables (AC5).
 *
 * This endpoint is not tournament-scoped (system-level variable catalog).
 *
 * @returns  Array of CertificateTemplateVariable
 * @throws   Error with message on HTTP error or network failure
 */
export async function listVariables(): Promise<CertificateTemplateVariable[]> {
    const response = await fetch('/api/certificate-template/variables', {
        credentials: 'same-origin',
    });
    if (!response.ok) {
        throw new Error(await extractErrorMessage(response));
    }
    return response.json() as Promise<CertificateTemplateVariable[]>;
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
