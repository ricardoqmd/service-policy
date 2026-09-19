package io.github.ricardoqmd.servicepolicy.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import jakarta.inject.Singleton;

import org.bson.Document;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;

/**
 * The one-way installation marker of the control plane (ADR-033 §6).
 *
 * <p>While no marker exists the service is in installation mode; the first successful control-plane write
 * accepted from the bootstrap subject records it, and installation mode ends permanently. Installation
 * mode is decided by the marker's presence and by nothing else — in particular never by the absence of
 * policies, because deleting every policy would otherwise reopen it.
 *
 * <p><strong>The marker records the reserved application.</strong> Which application holds the
 * control-plane policy set is fixed at installation and read back at startup, so that no later
 * configuration change can point the control plane at a different one.
 *
 * <p><strong>One-way by construction.</strong> This store has no operation that removes the marker, and no
 * other class in this service reaches its collection. Losing the store is a reinstallation, and behaves as
 * one.
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed (see ADR-009).
@Singleton
public class InstallationStore {

    private static final int DUPLICATE_KEY_CODE = 11000;

    /** The fixed identity of the single marker. */
    static final String MARKER_ID = "control-plane";

    private static final String RESERVED_APP = "reservedApp";
    private static final String SCHEMA_VERSION = "schemaVersion";

    private final InstallationRepository repository;

    InstallationStore(InstallationRepository repository) {
        this.repository = repository;
    }

    /**
     * Records the marker if it does not exist yet. Idempotent: a marker already recorded — by an earlier
     * write or by a concurrent one — is left exactly as it was, so the first record is the one that stays,
     * with the reserved application the write that closed installation ran under.
     *
     * @param installedBy the credential whose write closed installation mode.
     * @param reservedApp the application reserved for the control plane at that moment; never blank.
     * @throws IllegalArgumentException if {@code reservedApp} is blank: the marker it would write is one
     *     {@link #marker} does not read as {@link InstallationMarker.Status#RECORDED}.
     */
    public void recordInstalled(String installedBy, String reservedApp) {
        // What the reader accepts, the writer is held to (ADR-034 §11): a marker recording a blank
        // identifier would install a store that this build then refuses to start on.
        if (reservedApp == null || reservedApp.isBlank()) {
            throw new IllegalArgumentException("an installation marker must record a non-blank reserved application");
        }
        Document fields = new Document("installedAt", Instant.now().toString())
                .append("installedBy", installedBy)
                .append(RESERVED_APP, reservedApp)
                .append(SCHEMA_VERSION, InstallationDocument.SCHEMA_VERSION);
        try {
            repository
                    .mongoCollection()
                    .updateOne(
                            Filters.eq("_id", MARKER_ID),
                            new Document("$setOnInsert", fields),
                            new UpdateOptions().upsert(true));
        } catch (MongoWriteException e) {
            // Two upserts racing on the same _id: one inserts, the other loses on the key and is a no-op.
            if (e.getError().getCode() != DUPLICATE_KEY_CODE) {
                throw e;
            }
        }
    }

    /**
     * The marker as stored, in one query, read as a raw document rather than through the codec so that a
     * shape this build does not write is reported as what it is instead of being mapped onto a default.
     *
     * <p>{@link InstallationMarker.Status#RECORDED} is exactly what {@link #recordInstalled} writes: the
     * schema marker {@code int} {@value InstallationDocument#SCHEMA_VERSION} and a non-blank string
     * identifier. Nothing else is read as an identifier, and nothing is guessed or backfilled.
     */
    public InstallationMarker marker() {
        Document marker = repository
                .mongoDatabase()
                .getCollection(InstallationDocument.COLLECTION)
                .find(Filters.eq("_id", MARKER_ID))
                .first();
        if (marker == null) {
            return InstallationMarker.absent();
        }
        Object schemaVersion = marker.get(SCHEMA_VERSION);
        if (Objects.equals(schemaVersion, InstallationDocument.EARLIER_SCHEMA_VERSION)) {
            return InstallationMarker.unusable(InstallationMarker.Status.EARLIER_SHAPE);
        }
        if (!Objects.equals(schemaVersion, InstallationDocument.SCHEMA_VERSION)) {
            return InstallationMarker.unusable(InstallationMarker.Status.UNRECOGNISED_SHAPE);
        }
        if (marker.get(RESERVED_APP) instanceof String reservedApp && !reservedApp.isBlank()) {
            return InstallationMarker.recorded(reservedApp);
        }
        return InstallationMarker.unusable(InstallationMarker.Status.NO_USABLE_IDENTIFIER);
    }

    /**
     * The application this store was installed with (ADR-033 §6).
     *
     * @return the recorded identifier, or empty whenever {@link #marker} is not
     *     {@link InstallationMarker.Status#RECORDED}. Empty is never a licence to assume one.
     */
    public Optional<String> recordedReservedApp() {
        return Optional.ofNullable(marker().reservedApp());
    }
}
