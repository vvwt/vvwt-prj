// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
function VvwtWebSocket(url, options) {
    this._url = url;
    this._options = options || {};
    this._ws = null;
    this._reconnectAttempts = 0;
    this._maxReconnectAttempts = this._options.maxReconnectAttempts || 5;
    this._reconnectDelay = this._options.reconnectDelay || 3000;
    this._stopped = false;
    this._connected = false;
}

VvwtWebSocket.prototype.connect = function () {
    if (this._stopped) { return; }
    var self = this;
    try {
        self._ws = new WebSocket(self._url);
    } catch (e) {
        self._onConnectError(e);
        return;
    }

    self._ws.onopen = function () {
        self._reconnectAttempts = 0;
        self._connected = true;
        if (typeof self._options.onOpen === 'function') {
            self._options.onOpen();
        }
    };

    self._ws.onmessage = function (event) {
        if (typeof self._options.onMessage === 'function') {
            var payload;
            try {
                payload = JSON.parse(event.data);
            } catch (e) {
                payload = event.data;
            }
            self._options.onMessage(payload);
        }
    };

    self._ws.onclose = function (event) {
        self._connected = false;
        if (typeof self._options.onClose === 'function') {
            self._options.onClose(event);
        }
        if (!self._stopped) {
            self._scheduleReconnect();
        }
    };

    self._ws.onerror = function (event) {
        /* onerror is always followed by onclose — reconnect is handled there */
        self._connected = false;
    };
};

VvwtWebSocket.prototype._onConnectError = function (err) {
    this._connected = false;
    if (!this._stopped) {
        this._scheduleReconnect();
    }
};

VvwtWebSocket.prototype._scheduleReconnect = function () {
    var self = this;
    self._reconnectAttempts += 1;
    if (self._reconnectAttempts > self._maxReconnectAttempts) {
        /* Activate polling fallback (AC4b) */
        if (typeof self._options.onFallback === 'function') {
            self._options.onFallback();
        }
        return;
    }
    var delay = self._reconnectDelay * self._reconnectAttempts;
    setTimeout(function () {
        if (!self._stopped) {
            self.connect();
        }
    }, delay);
};

VvwtWebSocket.prototype.send = function (data) {
    if (this._ws && this._ws.readyState === 1 /* OPEN */) {
        this._ws.send(typeof data === 'string' ? data : JSON.stringify(data));
        return true;
    }
    return false;
};

VvwtWebSocket.prototype.stop = function () {
    this._stopped = true;
    this._connected = false;
    if (this._ws) {
        this._ws.close();
        this._ws = null;
    }
};

VvwtWebSocket.prototype.isConnected = function () {
    return this._connected && this._ws !== null && this._ws.readyState === 1 /* OPEN */;
};

/* =========================================================================
   3. Polling fallback (AC4b)
   Short-interval HTTP polling that activates when WebSocket is unavailable.
   ========================================================================= */

/**
 * VvwtPoller — HTTP polling client.
 *
 * @param {string}   url              Endpoint to poll (GET request)
 * @param {object}   options
 * @param {number}   [options.interval=3000]   Poll interval in milliseconds.
 * @param {function} [options.onData]           Called with parsed JSON response on each successful poll.
 * @param {function} [options.onError]          Called with the error when a poll request fails.
 */
function VvwtPoller(url, options) {
    this._url = url;
    this._options = options || {};
    this._interval = this._options.interval || 3000;
    this._timerId = null;
    this._running = false;
}

VvwtPoller.prototype.start = function () {
    if (this._running) { return; }
    this._running = true;
    this._poll();
};

VvwtPoller.prototype._poll = function () {
    var self = this;
    if (!self._running) { return; }

    vvwtFetch(self._url, { method: 'GET' }, function (data) {
        if (typeof self._options.onData === 'function') {
            self._options.onData(data);
        }
        self._timerId = setTimeout(function () { self._poll(); }, self._interval);
    }, function (err) {
        if (typeof self._options.onError === 'function') {
            self._options.onError(err);
        }
        self._timerId = setTimeout(function () { self._poll(); }, self._interval);
    });
};

VvwtPoller.prototype.stop = function () {
    this._running = false;
    if (this._timerId !== null) {
        clearTimeout(this._timerId);
        this._timerId = null;
    }
};

VvwtPoller.prototype.isRunning = function () {
    return this._running;
};

/* =========================================================================
   Helper: vvwtFetch
   fetch() with XMLHttpRequest fallback for older iOS 9 builds.
   ========================================================================= */

/**
 * vvwtFetch — HTTP request helper (fetch with XHR fallback).
 *
 * @param {string}   url
 * @param {object}   opts        { method, headers, body }
 * @param {function} onSuccess   Called with parsed JSON (or raw text if not JSON)
 * @param {function} onError     Called with an error string
 */
function vvwtFetch(url, opts, onSuccess, onError) {
    opts = opts || {};
    if (typeof window.fetch === 'function') {
        var fetchOpts = { method: opts.method || 'GET' };
        if (opts.headers) { fetchOpts.headers = opts.headers; }
        if (opts.body !== undefined) { fetchOpts.body = opts.body; }
        window.fetch(url, fetchOpts).then(function (resp) {
            if (!resp.ok) {
                if (typeof onError === 'function') {
                    onError('HTTP ' + resp.status);
                }
                return;
            }
            resp.text().then(function (text) {
                var data;
                try { data = JSON.parse(text); } catch (e) { data = text; }
                if (typeof onSuccess === 'function') { onSuccess(data); }
            }, function (err) {
                if (typeof onError === 'function') { onError(String(err)); }
            });
        }, function (err) {
            if (typeof onError === 'function') { onError(String(err)); }
        });
    } else {
        var xhr = new XMLHttpRequest();
        xhr.open(opts.method || 'GET', url, true);
        if (opts.headers) {
            var keys = Object.keys(opts.headers);
            for (var i = 0; i < keys.length; i++) {
                xhr.setRequestHeader(keys[i], opts.headers[keys[i]]);
            }
        }
        xhr.onreadystatechange = function () {
            if (xhr.readyState !== 4) { return; }
            if (xhr.status >= 200 && xhr.status < 300) {
                var data;
                try { data = JSON.parse(xhr.responseText); } catch (e) { data = xhr.responseText; }
                if (typeof onSuccess === 'function') { onSuccess(data); }
            } else {
                if (typeof onError === 'function') { onError('HTTP ' + xhr.status); }
            }
        };
        xhr.onerror = function () {
            if (typeof onError === 'function') { onError('Network error'); }
        };
        if (opts.body !== undefined) {
            xhr.send(opts.body);
        } else {
            xhr.send();
        }
    }
}

/* =========================================================================
   4. DOM helpers (AC4c)
   Minimal, intention-revealing DOM manipulation utilities.
   ========================================================================= */

/**
 * vvwtQuery — select a single DOM element.
 * @param {string} selector   CSS selector
 * @param {Element} [root]    Root element (defaults to document)
 * @returns {Element|null}
 */
function vvwtQuery(selector, root) {
    return (root || document).querySelector(selector);
}

/**
 * vvwtQueryAll — select all matching DOM elements.
 * @param {string} selector
 * @param {Element} [root]
 * @returns {NodeList}
 */
function vvwtQueryAll(selector, root) {
    return (root || document).querySelectorAll(selector);
}

/**
 * vvwtSetText — set element text content safely.
 * @param {Element} el
 * @param {string}  text
 */
function vvwtSetText(el, text) {
    if (el) { el.textContent = text; }
}

/**
 * vvwtShow — make an element visible (removes display:none).
 * @param {Element} el
 */
function vvwtShow(el) {
    if (el) { el.style.display = ''; }
}

/**
 * vvwtHide — hide an element.
 * @param {Element} el
 */
function vvwtHide(el) {
    if (el) { el.style.display = 'none'; }
}

/**
 * vvwtAddClass — add a CSS class to an element.
 * @param {Element} el
 * @param {string}  className
 */
function vvwtAddClass(el, className) {
    if (!el) { return; }
    if (el.classList) {
        el.classList.add(className);
    } else {
        var classes = (el.className || '').split(' ');
        for (var i = 0; i < classes.length; i++) {
            if (classes[i] === className) { return; }
        }
        el.className = (el.className ? el.className + ' ' : '') + className;
    }
}

/**
 * vvwtRemoveClass — remove a CSS class from an element.
 * @param {Element} el
 * @param {string}  className
 */
function vvwtRemoveClass(el, className) {
    if (!el) { return; }
    if (el.classList) {
        el.classList.remove(className);
    } else {
        var classes = (el.className || '').split(' ');
        var remaining = [];
        for (var i = 0; i < classes.length; i++) {
            if (classes[i] !== className) { remaining.push(classes[i]); }
        }
        el.className = remaining.join(' ');
    }
}

/* =========================================================================
   5. Form serialization (AC4d)
   Serialize a form's input/select/textarea values into a plain object.
   ========================================================================= */

/**
 * vvwtSerializeForm — serialize a form element to a JSON-compatible object.
 *
 * Handles: input (text, number, hidden, checkbox, radio), select, textarea.
 * Skips: disabled elements, submit/reset/button inputs.
 *
 * @param {HTMLFormElement} form
 * @returns {object}  Plain object of { name: value } pairs.
 */
function vvwtSerializeForm(form) {
    var result = {};
    if (!form || !form.elements) { return result; }
    var elements = form.elements;
    for (var i = 0; i < elements.length; i++) {
        var el = elements[i];
        if (!el.name || el.disabled) { continue; }
        var tag = el.tagName.toUpperCase();
        var type = (el.type || '').toLowerCase();
        if (type === 'submit' || type === 'reset' || type === 'button') { continue; }
        if (type === 'checkbox') {
            result[el.name] = el.checked;
        } else if (type === 'radio') {
            if (el.checked) { result[el.name] = el.value; }
        } else if (tag === 'SELECT') {
            result[el.name] = el.options[el.selectedIndex] ? el.options[el.selectedIndex].value : '';
        } else {
            result[el.name] = el.value;
        }
    }
    return result;
}
