package de.vvwt.slotopt.dispatcher.packet.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.slotopt.dispatcher.job.JobRecord;
import de.vvwt.slotopt.dispatcher.packet.PacketDecomposerService;
import de.vvwt.slotopt.dispatcher.packet.PacketRecord;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultPacketDecomposerService}.
 *
 * <p>Same package (white-box per DEC-36 same-package rule) — references {@link
 * DefaultPacketDecomposerService} directly. Tests in the {@code packet} package would need to
 * reference the {@link PacketDecomposerService} interface.
 *
 * <p>RED-first per DEC-22 / AC-PACKET-DECOMPOSER-SERVICE (E37S08).
 *
 * <p>Story: E37S08; AC-PACKET-DECOMPOSER-SERVICE; DEC-22, DEC-35, DEC-36
 */
class DefaultPacketDecomposerServiceTest {

    private DefaultPacketDecomposerService service;

    @BeforeEach
    void setUp() {
        service = new DefaultPacketDecomposerService();
    }

    /**
     * For N=2 (2! = 2), which is < 4 × 100_000_000, should produce exactly 4 packets.
     */
    @Test
    void decompose_smallN_producesExactlyFourPackets() {
        // N=2: 2! = 2. packetPayloadJson has rankFrom/rankTo intervals covering [0, 2).
        // CanonicalPhaseDef for 2 avatars, 1 row: [[0, 1]]
        String jobDefJson =
                """
                {"jobId":"%s","n":2,"canonicalPhaseDef":{"rowCount":1,"avatarCount":2,"rows":[[0,1]]}}
                """.formatted(UUID.randomUUID()).trim();

        JobRecord job = buildJobRecord(UUID.randomUUID(), jobDefJson);
        List<PacketRecord> packets = service.decompose(job);

        assertThat(packets).hasSize(4);
        assertPacketsValid(packets, job.getJobId());
        assertRanksContiguous(packets, 2L);
    }

    /**
     * For N=14 (~8.7×10^10 perms), which is > 4 × permsPerPacket (400M), packets cover [0, 14!).
     * At 100M perms/packet: ceil(87178291200 / 100000000) = 872 packets.
     */
    @Test
    void decompose_largeN_producesMultiplePackets() {
        long n14Factorial = 87178291200L; // 14!
        // CanonicalPhaseDef for 2 avatars, 1 row (n field is what matters for decomposition)
        String jobDefJson =
                """
                {"jobId":"%s","n":14,"canonicalPhaseDef":{"rowCount":1,"avatarCount":2,"rows":[[0,1]]}}
                """.formatted(UUID.randomUUID()).trim();

        JobRecord job = buildJobRecord(UUID.randomUUID(), jobDefJson);
        List<PacketRecord> packets = service.decompose(job);

        assertThat(packets).hasSizeGreaterThan(4);
        assertPacketsValid(packets, job.getJobId());
        assertRanksContiguous(packets, n14Factorial);
    }

    /**
     * All packets are UNCLAIMED on creation.
     */
    @Test
    void decompose_allPacketsAreUnclaimed() {
        String jobDefJson =
                """
                {"jobId":"%s","n":2,"canonicalPhaseDef":{"rowCount":1,"avatarCount":2,"rows":[[0,1]]}}
                """.formatted(UUID.randomUUID()).trim();

        JobRecord job = buildJobRecord(UUID.randomUUID(), jobDefJson);
        List<PacketRecord> packets = service.decompose(job);

        assertThat(packets).allMatch(p -> "UNCLAIMED".equals(p.getStatus()));
    }

    /**
     * All packets have the correct jobId.
     */
    @Test
    void decompose_allPacketsHaveCorrectJobId() {
        UUID jobId = UUID.randomUUID();
        String jobDefJson =
                """
                {"jobId":"%s","n":2,"canonicalPhaseDef":{"rowCount":1,"avatarCount":2,"rows":[[0,1]]}}
                """.formatted(jobId).trim();

        JobRecord job = buildJobRecord(jobId, jobDefJson);
        List<PacketRecord> packets = service.decompose(job);

        assertThat(packets).allMatch(p -> p.getJobId().equals(jobId));
    }

    /**
     * Each packet has a unique packetId.
     */
    @Test
    void decompose_allPacketsHaveUniquePacketIds() {
        String jobDefJson =
                """
                {"jobId":"%s","n":2,"canonicalPhaseDef":{"rowCount":1,"avatarCount":2,"rows":[[0,1]]}}
                """.formatted(UUID.randomUUID()).trim();

        JobRecord job = buildJobRecord(UUID.randomUUID(), jobDefJson);
        List<PacketRecord> packets = service.decompose(job);

        long distinctIds = packets.stream().map(PacketRecord::getPacketId).distinct().count();
        assertThat(distinctIds).isEqualTo(packets.size());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static JobRecord buildJobRecord(UUID jobId, String jobDefJson) {
        JobRecord job = new JobRecord();
        job.setJobId(jobId);
        job.setJobDefJson(jobDefJson);
        job.setStatus("RECEIVED");
        return job;
    }

    private static void assertPacketsValid(List<PacketRecord> packets, UUID expectedJobId) {
        assertThat(packets).isNotEmpty();
        for (PacketRecord p : packets) {
            assertThat(p.getPacketId()).isNotNull();
            assertThat(p.getJobId()).isEqualTo(expectedJobId);
            assertThat(p.getPacketPayloadJson()).isNotBlank();
            assertThat(p.getStatus()).isEqualTo("UNCLAIMED");
            assertThat(p.getClaimedByWorkerId()).isNull();
            assertThat(p.getClaimedAt()).isNull();
            assertThat(p.getTimeoutAt()).isNull();
        }
    }

    private static void assertRanksContiguous(List<PacketRecord> packets, long totalPerms) {
        // Parse rankFrom/rankTo from payloads to verify contiguity
        // payload format: {"rankFrom":X,"rankTo":Y,...}
        long[] starts = new long[packets.size()];
        long[] ends = new long[packets.size()];
        for (int i = 0; i < packets.size(); i++) {
            String payload = packets.get(i).getPacketPayloadJson();
            starts[i] = extractLong(payload, "rankFrom");
            ends[i] = extractLong(payload, "rankTo");
        }
        // Sort by start
        java.util.Arrays.sort(starts);
        java.util.Arrays.sort(ends);

        assertThat(starts[0]).isEqualTo(0L);
        assertThat(ends[ends.length - 1]).isEqualTo(totalPerms);
    }

    private static long extractLong(String json, String key) {
        int idx = json.indexOf("\"" + key + "\":");
        if (idx < 0) throw new IllegalArgumentException("Key not found: " + key + " in " + json);
        int start = idx + key.length() + 3;
        int end = start;
        while (end < json.length()
                && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }
}
