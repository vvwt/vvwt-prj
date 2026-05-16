// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.runtime.internal;

import java.util.List;
import java.util.UUID;

/**
 * Internal POJO for deserializing {@code packetPayloadJson} from a pull-packet response.
 *
 * <p>Maps the JSON fields produced by the dispatcher's packet service to Java types for use by the
 * {@link DefaultWorkerLoop} to invoke {@code PacketSolver.solvePacket}.
 *
 * <p>Package-private — used only within {@code runtime.internal}.
 *
 * <p>Story: E41S05 AC-PACKET-PROCESSING.
 */
class PacketPayload {

    private UUID jobId;
    private int n;
    private long rankFrom;
    private long rankTo;
    private CanonicalPhaseDefJson canonicalPhaseDef;

    public UUID getJobId() {
        return jobId;
    }

    public void setJobId(UUID jobId) {
        this.jobId = jobId;
    }

    public int getN() {
        return n;
    }

    public void setN(int n) {
        this.n = n;
    }

    public long getRankFrom() {
        return rankFrom;
    }

    public void setRankFrom(long rankFrom) {
        this.rankFrom = rankFrom;
    }

    public long getRankTo() {
        return rankTo;
    }

    public void setRankTo(long rankTo) {
        this.rankTo = rankTo;
    }

    public CanonicalPhaseDefJson getCanonicalPhaseDef() {
        return canonicalPhaseDef;
    }

    public void setCanonicalPhaseDef(CanonicalPhaseDefJson canonicalPhaseDef) {
        this.canonicalPhaseDef = canonicalPhaseDef;
    }

    /** Internal POJO for the canonicalPhaseDef nested object. */
    static class CanonicalPhaseDefJson {
        private int rowCount;
        private int avatarCount;
        private List<List<Integer>> rows;

        public int getRowCount() {
            return rowCount;
        }

        public void setRowCount(int rowCount) {
            this.rowCount = rowCount;
        }

        public int getAvatarCount() {
            return avatarCount;
        }

        public void setAvatarCount(int avatarCount) {
            this.avatarCount = avatarCount;
        }

        public List<List<Integer>> getRows() {
            return rows;
        }

        public void setRows(List<List<Integer>> rows) {
            this.rows = rows;
        }
    }
}
