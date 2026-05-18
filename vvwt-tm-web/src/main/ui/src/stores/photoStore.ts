// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
export interface PhotoMetadata {
    filename: string;
    sizeBytes: number;
    uploadedAt: string;
}

/**
 * Upload error with an optional i18n message key from the server's ApiErrorResponse.
 *
 * When the server returns a JSON body with a {@code messageKey} field (e.g.
 * {@code "error.photo.tooLarge"}), the error carries that key so the caller can resolve it
 * through the svelte-i18n layer instead of displaying the English {@code message} field.
 *
 * E12S08 AC4: over-limit errors must reach the organiser through the i18n layer, in German,
 * stating the maximum allowed photo size.
 */
export class PhotoUploadError extends Error {
    readonly messageKey: string | undefined;

    constructor(message: string, messageKey?: string) {
        super(message);
        this.name = 'PhotoUploadError';
        this.messageKey = messageKey;
    }
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
                    reject(new PhotoUploadError('Invalid response from server'));
                }
            } else {
                let msg = `HTTP ${xhr.status}`;
                let messageKey: string | undefined;
                try {
                    const body = JSON.parse(xhr.responseText) as {
                        message?: string;
                        messageKey?: string;
                    };
                    if (body.messageKey) messageKey = body.messageKey;
                    if (body.message) msg = body.message;
                } catch {
                    /* ignore parse error */
                }
                reject(new PhotoUploadError(msg, messageKey));
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
