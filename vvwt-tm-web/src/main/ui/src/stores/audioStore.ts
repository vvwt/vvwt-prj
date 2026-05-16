// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
export type AudioCategory = 'START' | 'END' | 'PAUSE';

/** Mirrors AudioMetadataResponse DTO (E11S01). */
export interface AudioMetadata {
    category: AudioCategory;
    filename: string;
    sizeBytes: number;
    uploadedAt: string;
}

// ---------------------------------------------------------------------------
// listAudio — GET /api/audio/tournaments/{id}
// ---------------------------------------------------------------------------

/**
 * Fetches the list of uploaded audio files for a tournament (AC3).
 * Returns an empty array when no files have been uploaded.
 *
 * @param tournamentId  UUID of the tournament
 * @returns             Array of AudioMetadata (may be empty)
 * @throws              Error with message on HTTP error or network failure
 */
export async function listAudio(tournamentId: string): Promise<AudioMetadata[]> {
    const response = await fetch(`/api/audio/tournaments/${tournamentId}`, {
        credentials: 'same-origin',
    });
    if (!response.ok) {
        throw new Error(await extractErrorMessage(response));
    }
    return response.json() as Promise<AudioMetadata[]>;
}

// ---------------------------------------------------------------------------
// uploadAudio — POST /api/audio/tournaments/{id}/{category}
// ---------------------------------------------------------------------------

/**
 * Uploads (or replaces) an audio file for a tournament and category (AC2, AC4).
 *
 * Uses XMLHttpRequest to expose upload progress to the caller via the optional
 * `onProgress` callback (AC2: upload progress is shown).
 *
 * @param tournamentId  UUID of the tournament
 * @param category      Audio category (START | END | PAUSE)
 * @param file          The File object from the file picker (must be .mp3)
 * @param onProgress    Optional callback receiving progress percentage 0–100
 * @returns             AudioMetadata returned by the server on success (201)
 * @throws              Error with localized-friendly message on failure
 */
export function uploadAudio(
    tournamentId: string,
    category: AudioCategory,
    file: File,
    onProgress?: (percent: number) => void,
): Promise<AudioMetadata> {
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
            if (xhr.status === 201) {
                try {
                    resolve(JSON.parse(xhr.responseText) as AudioMetadata);
                } catch {
                    reject(new Error('Invalid response from server'));
                }
            } else {
                let msg = `HTTP ${xhr.status}`;
                try {
                    const body = JSON.parse(xhr.responseText) as { message?: string };
                    if (body.message) msg = body.message;
                } catch {
                    /* ignore parse error — use status text */
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

        xhr.open('POST', `/api/audio/tournaments/${tournamentId}/${category}`);
        xhr.send(formData);
    });
}

// ---------------------------------------------------------------------------
// deleteAudio — DELETE /api/audio/tournaments/{id}/{category}
// ---------------------------------------------------------------------------

/**
 * Deletes the audio file for a tournament and category (AC5).
 *
 * @param tournamentId  UUID of the tournament
 * @param category      Audio category (START | END | PAUSE)
 * @throws              Error with message if the file does not exist (404) or on network failure
 */
export async function deleteAudio(tournamentId: string, category: AudioCategory): Promise<void> {
    const response = await fetch(`/api/audio/tournaments/${tournamentId}/${category}`, {
        method: 'DELETE',
        credentials: 'same-origin',
    });
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
 *
 * @param response  The Response object from a failed fetch call
 * @returns         A non-empty error message string
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
