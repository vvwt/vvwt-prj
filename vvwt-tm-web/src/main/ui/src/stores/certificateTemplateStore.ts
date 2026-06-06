// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
export interface CertificateTemplateMetadata {
    tournamentId: string;
    filename: string;
    /** Detected format: 'html' or 'svg'. */
    format: 'html' | 'svg';
    uploadedAt: string;
    fileSizeBytes: number;
    /** Width component of the per-template crop aspect ratio override (E71S02 AC1). null = no override. */
    photoAspectRatioWidth: number | null;
    /** Height component of the per-template crop aspect ratio override (E71S02 AC1). null = no override. */
    photoAspectRatioHeight: number | null;
}

/** Mirrors CertificateTemplateVariableResponse DTO (E12S04 AC6). */
export interface CertificateTemplateVariable {
    name: string;
    type: string;
    example: string;
}

/** Effective crop aspect ratio response (E71S02 AC2). */
export interface EffectiveAspectRatio {
    width: number;
    height: number;
}

// ---------------------------------------------------------------------------
// getTemplateMetadata — GET /api/certificate/tournaments/{id}/template/info
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
        `/api/certificate/tournaments/${tournamentId}/template/info`,
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
// uploadTemplate — POST /api/certificate/tournaments/{id}/template
// ---------------------------------------------------------------------------

/**
 * Options for uploadTemplate (E71S02 AC1 — optional ratio override).
 */
export interface UploadTemplateOptions {
    /** Optional width component of the per-template crop ratio override. Omit to leave unset. */
    photoAspectRatioWidth?: number | null;
    /** Optional height component of the per-template crop ratio override. Omit to leave unset. */
    photoAspectRatioHeight?: number | null;
    /** Optional progress callback receiving percentage 0–100. */
    onProgress?: (percent: number) => void;
}

/**
 * Uploads (or replaces) the certificate template for a tournament (AC1, AC3, E71S02).
 *
 * Uses XMLHttpRequest to expose upload progress to the caller via the optional
 * `onProgress` callback (mirrors uploadAudio from audioStore.ts).
 *
 * @param tournamentId  UUID of the tournament
 * @param file          The File object from the file picker (.html or .svg)
 * @param options       Optional upload options (ratio override, progress callback)
 * @returns             CertificateTemplateMetadata returned by the server on success (200)
 * @throws              Error with a user-friendly message on failure
 */
export function uploadTemplate(
    tournamentId: string,
    file: File,
    options?: UploadTemplateOptions | ((percent: number) => void),
): Promise<CertificateTemplateMetadata> {
    // Backwards-compatible: options may be a plain progress callback (legacy callers)
    const opts: UploadTemplateOptions =
        typeof options === 'function' ? { onProgress: options } : (options ?? {});

    return new Promise((resolve, reject) => {
        const formData = new FormData();
        formData.append('file', file, file.name);

        // E71S02 AC1: append optional ratio override params when set
        if (opts.photoAspectRatioWidth != null) {
            formData.append('photoAspectRatioWidth', String(opts.photoAspectRatioWidth));
        }
        if (opts.photoAspectRatioHeight != null) {
            formData.append('photoAspectRatioHeight', String(opts.photoAspectRatioHeight));
        }

        const xhr = new XMLHttpRequest();
        xhr.withCredentials = true;   // same-origin basic-auth

        if (opts.onProgress && xhr.upload) {
            xhr.upload.addEventListener('progress', (event) => {
                if (event.lengthComputable) {
                    opts.onProgress!(Math.round((event.loaded / event.total) * 100));
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

        xhr.open('POST', `/api/certificate/tournaments/${tournamentId}/template`);
        xhr.send(formData);
    });
}

// ---------------------------------------------------------------------------
// getEffectiveAspectRatio — GET /api/certificate/tournaments/{id}/effective-ratio
// ---------------------------------------------------------------------------

/**
 * Returns the effective crop aspect ratio for the given tournament's certificate template
 * (E71S02 AC2).
 *
 * Returns the per-template override when set, otherwise the global default from tm.photos.
 * Never returns null — always resolves to a valid aspect ratio.
 *
 * @param tournamentId  UUID of the tournament
 * @returns             EffectiveAspectRatio with width and height
 * @throws              Error with message on HTTP error or network failure
 */
export async function getEffectiveAspectRatio(
    tournamentId: string,
): Promise<EffectiveAspectRatio> {
    const response = await fetch(
        `/api/certificate/tournaments/${tournamentId}/effective-ratio`,
        { credentials: 'same-origin' },
    );
    if (!response.ok) {
        throw new Error(await extractErrorMessage(response));
    }
    return response.json() as Promise<EffectiveAspectRatio>;
}

// ---------------------------------------------------------------------------
// deleteTemplate — DELETE /api/certificate/tournaments/{id}/template
// ---------------------------------------------------------------------------

/**
 * Deletes the certificate template for a tournament (AC4).
 *
 * @param tournamentId  UUID of the tournament
 * @throws              Error with message if the template does not exist (404) or on network failure
 */
export async function deleteTemplate(tournamentId: string): Promise<void> {
    const response = await fetch(
        `/api/certificate/tournaments/${tournamentId}/template`,
        { method: 'DELETE', credentials: 'same-origin' },
    );
    if (!response.ok) {
        throw new Error(await extractErrorMessage(response));
    }
}

// ---------------------------------------------------------------------------
// listVariables — GET /api/certificate/variables
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
    const response = await fetch('/api/certificate/variables', {
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
