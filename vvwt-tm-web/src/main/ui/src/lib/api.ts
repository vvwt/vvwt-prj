// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
export async function apiFetch(
    input: RequestInfo | URL,
    init?: RequestInit
): Promise<Response> {
    const mergedInit: RequestInit = {
        ...init,
        // AC7: include browser-cached basic-auth credentials on every same-origin request.
        // 'same-origin' is sufficient because the SPA and the API share the same origin
        // (both served from the same Spring Boot instance on the same port and host).
        // If the SPA is ever served from a different origin, change this to 'include'.
        credentials: 'same-origin',
    };
    return fetch(input, mergedInit);
}
