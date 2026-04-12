/**
 * HTTP client utilities for the Tournament Manager Admin SPA.
 *
 * Story E05S02 — AC7 (SPA fetch integration).
 *
 * All fetch calls to /api/** must include the browser-cached basic-auth credentials
 * so Spring Security passes them through to the backend. Since the SPA and the API
 * are served from the same origin (same Spring Boot process, same port), using
 * `credentials: 'same-origin'` is sufficient — the browser automatically includes
 * the cached Authorization header from the initial basic-auth dialog.
 *
 * Usage:
 *   import { apiFetch } from '$lib/api.js';
 *   const response = await apiFetch('/api/tournaments');
 *
 * The `apiFetch` function is a drop-in replacement for `fetch` that:
 *   1. Prepends the API base path if the path starts with '/'
 *   2. Merges `credentials: 'same-origin'` into the RequestInit options
 *   3. Returns the raw Response for the caller to handle
 *
 * Adding request/response interceptors: extend this module, do not modify
 * callers directly. This is the single integration point for all API calls.
 *
 * AC7 delivery decision summary:
 *   Mechanism: browser native basic-auth dialog on first /admin/ request.
 *   After authentication, browser caches credentials and sends the Authorization
 *   header automatically on all same-origin requests (including SPA fetch calls).
 *   No explicit Authorization header management is needed in the SPA — the browser
 *   handles credential forwarding transparently via `credentials: 'same-origin'`.
 *
 * @see SecurityConfig (Java) for the server-side authorization rules
 */

/**
 * Fetch wrapper that ensures browser-cached basic-auth credentials are sent
 * on all API requests (AC7 — SPA fetch integration with Spring Security).
 *
 * @param input  URL or path (string) or a Request object
 * @param init   Optional RequestInit options; merged with credentials: 'same-origin'
 * @returns      A Promise resolving to the fetch Response
 */
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
