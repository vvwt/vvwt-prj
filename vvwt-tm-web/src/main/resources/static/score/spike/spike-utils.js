// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
"use strict";

var SpikeI18n = {
    fetchTestTitle: "AC1: fetch + XHR Test",
    fetchSuccess: "fetch succeeded",
    fetchFallbackXhr: "fetch unavailable — falling back to XHR",
    xhrSuccess: "XHR succeeded",
    xhrFailed: "XHR request failed",
    fetchFailed: "fetch request failed",
    wsTestTitle: "AC2: WebSocket Test",
    wsConnecting: "Connecting to WebSocket...",
    wsConnected: "WebSocket connected",
    wsDisconnected: "WebSocket disconnected",
    wsError: "WebSocket error",
    wsIdleSurvived: "WebSocket survived idle period",
    wsReconnecting: "Reconnecting WebSocket...",
    wsReconnected: "WebSocket reconnected after disconnect",
    wsReconnectFailed: "WebSocket reconnect failed",
    pollingTestTitle: "AC3: Polling Fallback Test",
    pollingStarted: "Polling started",
    pollingSuccess: "Poll response received",
    pollingFailed: "Poll request failed",
    pollingStopped: "Polling stopped",
    domTestTitle: "AC4: DOM + ES5 Test",
    domFormSubmitted: "Form submitted successfully",
    domTouchDetected: "Touch event detected",
    domClickDetected: "Click event detected",
    bgTestTitle: "AC5: Background/Foreground Test",
    bgBackgrounded: "Page backgrounded — waiting for foreground",
    bgForegrounded: "Page foregrounded — checking connection recovery",
    bgRecoverySuccess: "Connection recovered after foreground",
    bgRecoveryFailed: "Connection did NOT recover after foreground",
    bgNotSupported: "visibilitychange not supported — using focus/blur fallback",
    netTestTitle: "AC6: Network Drop Test",
    netOffline: "Network offline detected",
    netOnline: "Network online detected — recovering",
    netRecoverySuccess: "Communication recovered after network return",
    netRecoveryFailed: "Communication did NOT recover after network return",
    errorTestTitle: "AC8: Error Handling Test",
    errorCaught: "Global error caught",
    errorDisplay: "Error displayed to user",
    reportTitle: "AC7: Spike Report",
    reportPass: "PASS",
    reportFail: "FAIL",
    reportPending: "PENDING",
    reportOverallPass: "Overall verdict: PASS — all critical ACs green",
    reportOverallFail: "Overall verdict: FAIL — critical AC(s) failed",
    missingKey: function(key) { return "[missing: " + key + "]"; }
};

/**
 * Get an i18n string by key, with fallback to key name (AC10).
 * @param {string} key
 * @returns {string}
 */
function spikeText(key) {
    if (SpikeI18n.hasOwnProperty(key)) {
        var val = SpikeI18n[key];
        return typeof val === "function" ? val(key) : val;
    }
    return "[missing: " + key + "]";
}

/* ======================================================================
 * AC8: Global error handler — catches uncaught exceptions
 * ====================================================================== */
window.onerror = function(message, source, lineno, colno, error) {
    var errorContainer = document.getElementById("spike-error-display");
    if (errorContainer) {
        var errorDiv = document.createElement("div");
        errorDiv.className = "spike-error-entry";
        errorDiv.textContent = spikeText("errorCaught") + ": " + message +
            " (line " + lineno + ", col " + colno + ")";
        errorContainer.appendChild(errorDiv);
    }
    // Update AC8 result
    updateTestResult("ac8", "pass", spikeText("errorCaught") + ": " + message);
    // Return true to prevent default browser error handling
    return true;
};

/* ======================================================================
 * Test result tracking
 * ====================================================================== */
var spikeResults = {};

/**
 * Update the result display for a given AC test.
 * @param {string} acId - e.g., "ac1", "ac2"
 * @param {string} status - "pass", "fail", "pending"
 * @param {string} detail - observation text
 */
function updateTestResult(acId, status, detail) {
    spikeResults[acId] = { status: status, detail: detail };
    var element = document.getElementById("result-" + acId);
    if (element) {
        element.className = "spike-result spike-result-" + status;
        element.textContent = status.toUpperCase() + ": " + detail;
    }
    updateOverallVerdict();
}

/**
 * Update the overall spike verdict (AC7).
 */
function updateOverallVerdict() {
    var verdictElement = document.getElementById("spike-overall-verdict");
    if (!verdictElement) return;

    var allAcs = ["ac1", "ac2", "ac3", "ac4", "ac5", "ac6", "ac8", "ac9", "ac10"];
    var hasFail = false;
    var allDone = true;

    for (var i = 0; i < allAcs.length; i++) {
        var result = spikeResults[allAcs[i]];
        if (!result || result.status === "pending") {
            allDone = false;
        } else if (result.status === "fail") {
            hasFail = true;
        }
    }

    if (allDone) {
        if (hasFail) {
            verdictElement.className = "spike-verdict spike-verdict-fail";
            verdictElement.textContent = spikeText("reportOverallFail");
        } else {
            verdictElement.className = "spike-verdict spike-verdict-pass";
            verdictElement.textContent = spikeText("reportOverallPass");
        }
    } else {
        verdictElement.className = "spike-verdict spike-verdict-pending";
        verdictElement.textContent = "Tests in progress...";
    }
}

/* ======================================================================
 * AC1: fetch + XHR test
 * ====================================================================== */
function runFetchXhrTest() {
    var echoUrl = "/score/spike/api/echo";
    var usedMethod = "unknown";

    if (typeof window.fetch === "function") {
        usedMethod = "fetch";
        try {
            window.fetch(echoUrl).then(function(response) {
                if (!response.ok) {
                    throw new Error("HTTP " + response.status);
                }
                return response.json();
            }).then(function(data) {
                updateTestResult("ac1", "pass",
                    spikeText("fetchSuccess") + " (method: fetch, server timestamp: " + data.timestamp + ")");
            })["catch"](function(err) {
                // fetch failed — try XHR fallback
                runXhrFallback(echoUrl, "fetch error: " + err.message);
            });
        } catch (e) {
            // fetch threw synchronously (unlikely but defensive)
            runXhrFallback(echoUrl, "fetch exception: " + e.message);
        }
    } else {
        usedMethod = "xhr";
        runXhrFallback(echoUrl, spikeText("fetchFallbackXhr"));
    }
}

/**
 * XHR fallback for AC1 when fetch is unavailable or fails.
 * @param {string} url
 * @param {string} reason - why we fell back
 */
function runXhrFallback(url, reason) {
    var xhr = new XMLHttpRequest();
    xhr.open("GET", url, true);
    xhr.onreadystatechange = function() {
        if (xhr.readyState === 4) {
            if (xhr.status === 200) {
                var data = JSON.parse(xhr.responseText);
                updateTestResult("ac1", "pass",
                    spikeText("xhrSuccess") + " (fallback reason: " + reason +
                    ", server timestamp: " + data.timestamp + ")");
            } else {
                updateTestResult("ac1", "fail",
                    spikeText("xhrFailed") + " (status: " + xhr.status + ", fallback reason: " + reason + ")");
            }
        }
    };
    xhr.onerror = function() {
        updateTestResult("ac1", "fail",
            spikeText("xhrFailed") + " (network error, fallback reason: " + reason + ")");
    };
    xhr.send();
}

/* ======================================================================
 * AC2: WebSocket test (raw WebSocket to SockJS endpoint)
 * ====================================================================== */
var spikeWs = null;
var spikeWsConnectedAt = null;
var spikeWsIdleTimer = null;

function runWebSocketTest() {
    updateTestResult("ac2", "pending", spikeText("wsConnecting"));

    var protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    var wsUrl = protocol + "//" + window.location.host + "/ws/websocket";

    try {
        spikeWs = new WebSocket(wsUrl);
    } catch (e) {
        updateTestResult("ac2", "fail", spikeText("wsError") + ": " + e.message);
        return;
    }

    spikeWs.onopen = function() {
        spikeWsConnectedAt = new Date();
        updateTestResult("ac2", "pending",
            spikeText("wsConnected") + " at " + spikeWsConnectedAt.toISOString() +
            " — waiting 30s idle test...");

        // AC2: test 30-second idle survival
        spikeWsIdleTimer = setTimeout(function() {
            if (spikeWs && spikeWs.readyState === WebSocket.OPEN) {
                updateTestResult("ac2", "pass",
                    spikeText("wsIdleSurvived") + " (connected at " +
                    spikeWsConnectedAt.toISOString() + ", still open after 30s)");
            } else {
                updateTestResult("ac2", "fail",
                    spikeText("wsDisconnected") + " during 30s idle test (readyState: " +
                    (spikeWs ? spikeWs.readyState : "null") + ")");
            }
        }, 30000);
    };

    spikeWs.onerror = function(event) {
        if (spikeWsIdleTimer) clearTimeout(spikeWsIdleTimer);
        updateTestResult("ac2", "fail", spikeText("wsError") + " (check console for details)");
    };

    spikeWs.onclose = function(event) {
        if (spikeWsIdleTimer) clearTimeout(spikeWsIdleTimer);
        // Only mark fail if we haven't already passed
        if (!spikeResults.ac2 || spikeResults.ac2.status !== "pass") {
            updateTestResult("ac2", "fail",
                spikeText("wsDisconnected") + " (code: " + event.code +
                ", reason: " + (event.reason || "none") + ")");
        }
    };
}

/**
 * AC2 supplement: test reconnection after deliberate disconnect.
 */
function runWebSocketReconnectTest() {
    if (spikeWs && spikeWs.readyState === WebSocket.OPEN) {
        spikeWs.close();
    }

    updateTestResult("ac2", "pending", spikeText("wsReconnecting"));

    setTimeout(function() {
        var protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
        var wsUrl = protocol + "//" + window.location.host + "/ws/websocket";

        try {
            spikeWs = new WebSocket(wsUrl);
            spikeWs.onopen = function() {
                updateTestResult("ac2", "pass",
                    spikeText("wsReconnected") + " at " + new Date().toISOString());
            };
            spikeWs.onerror = function() {
                updateTestResult("ac2", "fail", spikeText("wsReconnectFailed"));
            };
        } catch (e) {
            updateTestResult("ac2", "fail", spikeText("wsReconnectFailed") + ": " + e.message);
        }
    }, 2000);
}

/* ======================================================================
 * AC3: Polling fallback test
 * ====================================================================== */
var spikePollingInterval = null;
var spikePollingCount = 0;
var SPIKE_POLLING_INTERVAL_MS = 3000; // 3 seconds (within 2–5s AC3 range)
var SPIKE_POLLING_MAX_CYCLES = 5;

function runPollingTest() {
    spikePollingCount = 0;
    updateTestResult("ac3", "pending", spikeText("pollingStarted"));

    spikePollingInterval = setInterval(function() {
        spikePollingCount++;

        var xhr = new XMLHttpRequest();
        xhr.open("GET", "/score/spike/api/echo", true);
        xhr.onreadystatechange = function() {
            if (xhr.readyState === 4) {
                if (xhr.status === 200) {
                    var data = JSON.parse(xhr.responseText);
                    updateTestResult("ac3", spikePollingCount >= 3 ? "pass" : "pending",
                        spikeText("pollingSuccess") + " (cycle " + spikePollingCount +
                        "/" + SPIKE_POLLING_MAX_CYCLES + ", timestamp: " + data.timestamp + ")");
                } else {
                    updateTestResult("ac3", "fail",
                        spikeText("pollingFailed") + " (cycle " + spikePollingCount +
                        ", status: " + xhr.status + ")");
                }
            }
        };
        xhr.onerror = function() {
            updateTestResult("ac3", "fail",
                spikeText("pollingFailed") + " (cycle " + spikePollingCount + ", network error)");
        };
        xhr.send();

        if (spikePollingCount >= SPIKE_POLLING_MAX_CYCLES) {
            clearInterval(spikePollingInterval);
            spikePollingInterval = null;
            if (spikeResults.ac3 && spikeResults.ac3.status !== "fail") {
                updateTestResult("ac3", "pass",
                    spikeText("pollingStopped") + " after " + SPIKE_POLLING_MAX_CYCLES + " successful cycles");
            }
        }
    }, SPIKE_POLLING_INTERVAL_MS);
}

function stopPollingTest() {
    if (spikePollingInterval) {
        clearInterval(spikePollingInterval);
        spikePollingInterval = null;
        updateTestResult("ac3", spikePollingCount >= 3 ? "pass" : "pending",
            spikeText("pollingStopped") + " at cycle " + spikePollingCount);
    }
}

/* ======================================================================
 * AC4: DOM + ES5 form test
 * ====================================================================== */
function runDomTest() {
    // Test: create form elements, read/write values, handle events
    var testForm = document.getElementById("spike-dom-form");
    if (!testForm) {
        updateTestResult("ac4", "fail", "Test form element not found in DOM");
        return;
    }

    var scoreInput = document.getElementById("spike-score-input");
    var submitBtn = document.getElementById("spike-submit-btn");

    if (!scoreInput || !submitBtn) {
        updateTestResult("ac4", "fail", "Form inputs not found in DOM");
        return;
    }

    // Write a value and read it back
    scoreInput.value = "25";
    var readBack = scoreInput.value;

    if (readBack !== "25") {
        updateTestResult("ac4", "fail", "DOM value read/write failed (expected '25', got '" + readBack + "')");
        return;
    }

    updateTestResult("ac4", "pass",
        spikeText("domFormSubmitted") + " — value write/read OK, " +
        (("ontouchstart" in window) ? spikeText("domTouchDetected") : spikeText("domClickDetected")));
}

/* ======================================================================
 * AC5: Background/foreground recovery
 * ====================================================================== */
var spikeBgTimestamp = null;

function initBackgroundTest() {
    if (typeof document.hidden !== "undefined") {
        document.addEventListener("visibilitychange", handleVisibilityChange, false);
        updateTestResult("ac5", "pending", "Listening for visibilitychange — background the page to test");
    } else if (typeof document.webkitHidden !== "undefined") {
        // iOS 9 Safari uses webkit prefix
        document.addEventListener("webkitvisibilitychange", handleVisibilityChange, false);
        updateTestResult("ac5", "pending", "Listening for webkitvisibilitychange — background the page to test");
    } else {
        // Fallback to focus/blur
        window.addEventListener("blur", function() { handleBackgrounded(); }, false);
        window.addEventListener("focus", function() { handleForegrounded(); }, false);
        updateTestResult("ac5", "pending",
            spikeText("bgNotSupported") + " — using focus/blur, background the page to test");
    }
}

function handleVisibilityChange() {
    var hidden = document.hidden || document.webkitHidden;
    if (hidden) {
        handleBackgrounded();
    } else {
        handleForegrounded();
    }
}

function handleBackgrounded() {
    spikeBgTimestamp = new Date();
    updateTestResult("ac5", "pending",
        spikeText("bgBackgrounded") + " at " + spikeBgTimestamp.toISOString());
}

function handleForegrounded() {
    if (!spikeBgTimestamp) return;

    var fgTimestamp = new Date();
    var elapsedSeconds = Math.round((fgTimestamp.getTime() - spikeBgTimestamp.getTime()) / 1000);

    updateTestResult("ac5", "pending",
        spikeText("bgForegrounded") + " after " + elapsedSeconds + "s — checking connection...");

    // Test if we can still communicate with the server
    var xhr = new XMLHttpRequest();
    xhr.open("GET", "/score/spike/api/echo", true);
    xhr.onreadystatechange = function() {
        if (xhr.readyState === 4) {
            if (xhr.status === 200) {
                updateTestResult("ac5", "pass",
                    spikeText("bgRecoverySuccess") +
                    " (backgrounded " + elapsedSeconds + "s, echo response OK)");
            } else {
                updateTestResult("ac5", "fail",
                    spikeText("bgRecoveryFailed") +
                    " (backgrounded " + elapsedSeconds + "s, echo status: " + xhr.status + ")");
            }
        }
    };
    xhr.onerror = function() {
        updateTestResult("ac5", "fail",
            spikeText("bgRecoveryFailed") +
            " (backgrounded " + elapsedSeconds + "s, network error)");
    };
    xhr.send();

    spikeBgTimestamp = null;
}

/* ======================================================================
 * AC6: Network drop detection
 * ====================================================================== */
function initNetworkDropTest() {
    if (typeof navigator.onLine !== "undefined") {
        window.addEventListener("offline", function() {
            updateTestResult("ac6", "pending", spikeText("netOffline") + " at " + new Date().toISOString());
        }, false);

        window.addEventListener("online", function() {
            updateTestResult("ac6", "pending",
                spikeText("netOnline") + " at " + new Date().toISOString() + " — verifying communication...");

            // Verify we can actually communicate
            var xhr = new XMLHttpRequest();
            xhr.open("GET", "/score/spike/api/echo", true);
            xhr.onreadystatechange = function() {
                if (xhr.readyState === 4) {
                    if (xhr.status === 200) {
                        updateTestResult("ac6", "pass",
                            spikeText("netRecoverySuccess") + " at " + new Date().toISOString());
                    } else {
                        updateTestResult("ac6", "fail",
                            spikeText("netRecoveryFailed") + " (status: " + xhr.status + ")");
                    }
                }
            };
            xhr.onerror = function() {
                updateTestResult("ac6", "fail",
                    spikeText("netRecoveryFailed") + " (network error on verification)");
            };
            xhr.send();
        }, false);

        updateTestResult("ac6", "pending",
            "Listening for online/offline events — disconnect network to test. " +
            "navigator.onLine = " + navigator.onLine);
    } else {
        updateTestResult("ac6", "pending",
            "navigator.onLine not supported — manual network drop test only. " +
            "Use the 'Manual Network Test' button.");
    }
}

/**
 * Manual network test: attempts a request to verify connectivity (AC6).
 */
function runManualNetworkTest() {
    var xhr = new XMLHttpRequest();
    xhr.open("GET", "/score/spike/api/echo", true);
    xhr.timeout = 5000;
    xhr.onreadystatechange = function() {
        if (xhr.readyState === 4) {
            if (xhr.status === 200) {
                updateTestResult("ac6", "pass",
                    spikeText("netRecoverySuccess") + " (manual test at " + new Date().toISOString() + ")");
            } else {
                updateTestResult("ac6", "pending",
                    spikeText("netRecoveryFailed") + " (manual test, status: " + xhr.status + ")");
            }
        }
    };
    xhr.onerror = function() {
        updateTestResult("ac6", "pending",
            spikeText("netOffline") + " (manual test failed — network appears down)");
    };
    xhr.ontimeout = function() {
        updateTestResult("ac6", "pending",
            spikeText("netOffline") + " (manual test timed out — network appears down)");
    };
    xhr.send();
}

/* ======================================================================
 * AC9: Local asset verification (run at page load)
 * ====================================================================== */
function verifyLocalAssets() {
    var scripts = document.getElementsByTagName("script");
    var links = document.getElementsByTagName("link");
    var externalFound = [];

    var i;
    for (i = 0; i < scripts.length; i++) {
        var src = scripts[i].getAttribute("src");
        if (src && (src.indexOf("http://") === 0 || src.indexOf("https://") === 0 || src.indexOf("//") === 0)) {
            externalFound.push("script: " + src);
        }
    }
    for (i = 0; i < links.length; i++) {
        var href = links[i].getAttribute("href");
        if (href && (href.indexOf("http://") === 0 || href.indexOf("https://") === 0 || href.indexOf("//") === 0)) {
            externalFound.push("link: " + href);
        }
    }

    if (externalFound.length === 0) {
        updateTestResult("ac9", "pass", "All assets are local — no external scripts, stylesheets, or fonts detected");
    } else {
        updateTestResult("ac9", "fail", "External assets found: " + externalFound.join(", "));
    }
}

/* ======================================================================
 * AC10: i18n verification (run at page load)
 * ====================================================================== */
function verifyI18n() {
    // Check that all user-visible text elements have data-i18n attributes
    var elements = document.querySelectorAll("[data-i18n]");
    var totalI18n = elements.length;
    var missingKeys = [];

    for (var i = 0; i < elements.length; i++) {
        var key = elements[i].getAttribute("data-i18n");
        if (!SpikeI18n.hasOwnProperty(key)) {
            missingKeys.push(key);
        }
    }

    if (missingKeys.length === 0 && totalI18n > 0) {
        updateTestResult("ac10", "pass",
            "All " + totalI18n + " i18n keys resolved — no hardcoded text detected");
    } else if (totalI18n === 0) {
        updateTestResult("ac10", "fail", "No data-i18n attributes found — i18n not implemented");
    } else {
        updateTestResult("ac10", "fail",
            "Missing i18n keys: " + missingKeys.join(", ") + " (" + missingKeys.length + " of " + totalI18n + ")");
    }
}

/* ======================================================================
 * AC8: Deliberate error trigger for testing global error handler
 * ====================================================================== */
function triggerTestError() {
    // This will be caught by window.onerror (AC8)
    throw new Error("Deliberate spike test error — AC8 validation");
}

/* ======================================================================
 * AC7: Generate spike report (structured PASS/FAIL)
 * ====================================================================== */
function generateSpikeReport() {
    var reportElement = document.getElementById("spike-report-output");
    if (!reportElement) return;

    var lines = [];
    lines.push("=== iOS 9 COMPATIBILITY SPIKE REPORT (E06S01) ===");
    lines.push("Generated: " + new Date().toISOString());
    lines.push("User-Agent: " + navigator.userAgent);
    lines.push("");
    lines.push("--- Per-AC Results ---");

    var allAcs = [
        { id: "ac1", label: "AC1 (fetch + XHR)", critical: true },
        { id: "ac2", label: "AC2 (WebSocket)", critical: true },
        { id: "ac3", label: "AC3 (polling fallback)", critical: true },
        { id: "ac4", label: "AC4 (DOM + ES5)", critical: true },
        { id: "ac5", label: "AC5 (background/foreground)", critical: false },
        { id: "ac6", label: "AC6 (network drop)", critical: false },
        { id: "ac8", label: "AC8 (error handling)", critical: true },
        { id: "ac9", label: "AC9 (local assets)", critical: true },
        { id: "ac10", label: "AC10 (i18n)", critical: false }
    ];

    var hasCriticalFail = false;
    var hasAnyFail = false;

    for (var i = 0; i < allAcs.length; i++) {
        var ac = allAcs[i];
        var result = spikeResults[ac.id];
        var status = result ? result.status.toUpperCase() : "NOT RUN";
        var detail = result ? result.detail : "Test not executed";
        var criticalLabel = ac.critical ? " [CRITICAL]" : "";

        lines.push(ac.label + criticalLabel + ": " + status);
        lines.push("  Observation: " + detail);

        if (result && result.status === "fail") {
            hasAnyFail = true;
            if (ac.critical) hasCriticalFail = true;
        }
    }

    lines.push("");
    lines.push("--- Overall Verdict ---");
    if (hasCriticalFail) {
        lines.push("VERDICT: FAIL (one or more critical ACs failed)");
    } else if (hasAnyFail) {
        lines.push("VERDICT: PASS WITH OBSERVATIONS (non-critical AC(s) failed)");
    } else {
        lines.push("VERDICT: PASS (all ACs green)");
    }

    lines.push("");
    lines.push("--- Recommended Fallback Strategy ---");
    // Derive recommendations from results
    if (spikeResults.ac1 && spikeResults.ac1.detail.indexOf("XHR") !== -1) {
        lines.push("- Use XHR, not fetch (fetch unavailable or unreliable)");
    } else {
        lines.push("- fetch is available and works");
    }
    if (spikeResults.ac2 && spikeResults.ac2.status === "fail") {
        lines.push("- Use polling, not WebSocket (WebSocket unreliable)");
    } else {
        lines.push("- WebSocket is available and stable");
    }

    reportElement.textContent = lines.join("\n");
}

/* ======================================================================
 * Page initialization
 * ====================================================================== */
function initSpikePage() {
    // Initialize i18n labels
    var elements = document.querySelectorAll("[data-i18n]");
    for (var i = 0; i < elements.length; i++) {
        var key = elements[i].getAttribute("data-i18n");
        elements[i].textContent = spikeText(key);
    }

    // Run automatic tests
    verifyLocalAssets();   // AC9
    verifyI18n();          // AC10
    initBackgroundTest();  // AC5
    initNetworkDropTest(); // AC6
}
