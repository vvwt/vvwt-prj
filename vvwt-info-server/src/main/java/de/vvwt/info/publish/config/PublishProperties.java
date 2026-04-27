package de.vvwt.info.publish.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the publisher endpoints (AC14).
 *
 * <p>Bound to the {@code vvwt.info.publish} prefix. Configures body-size caps for delta and
 * snapshot publish requests:
 *
 * <ul>
 *   <li>{@code max-delta-bytes}: default 1 MB (1,048,576 bytes) — matches a single domain-event
 *       envelope upper bound.
 *   <li>{@code max-snapshot-bytes}: default 16 MB (16,777,216 bytes) — matches the {@code
 *       tournament.state} upper bound from E38S03 AC11. Asymmetric ratio (16:1) reflects empirical
 *       estimates for a 100-team / 50-round tournament snapshot (AC14).
 * </ul>
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S05.story.md">E38S05 AC14</a>
 */
@ConfigurationProperties(prefix = "vvwt.info.publish")
public class PublishProperties {

    /** Maximum request body size for a delta publish (in bytes). Default: 1 MB. */
    private long maxDeltaBytes = 1_048_576L;

    /** Maximum request body size for a snapshot publish (in bytes). Default: 16 MB. */
    private long maxSnapshotBytes = 16_777_216L;

    public long getMaxDeltaBytes() {
        return maxDeltaBytes;
    }

    public void setMaxDeltaBytes(long maxDeltaBytes) {
        this.maxDeltaBytes = maxDeltaBytes;
    }

    public long getMaxSnapshotBytes() {
        return maxSnapshotBytes;
    }

    public void setMaxSnapshotBytes(long maxSnapshotBytes) {
        this.maxSnapshotBytes = maxSnapshotBytes;
    }
}
