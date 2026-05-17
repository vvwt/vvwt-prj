// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.result;

/**
 * Thrown when the {@code packetId} in a {@code submit-result} request is not found.
 *
 * <p>Maps to HTTP 404 Not Found at the controller layer.
 *
 * <p>Spec: E37S02 spec section (b) Endpoint 4 error cases; Story: E37S09; AC-SUBMIT-RESULT-SERVICE
 */
public class PacketNotFoundException extends RuntimeException {

    public PacketNotFoundException(String message) {
        super(message);
    }
}
