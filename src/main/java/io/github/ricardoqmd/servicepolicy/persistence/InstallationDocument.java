package io.github.ricardoqmd.servicepolicy.persistence;

import io.quarkus.mongodb.panache.common.MongoEntity;

/**
 * The installation marker (ADR-033 §6): one document, whose presence ends installation mode for good.
 *
 * <p>Its identity is a fixed key, so the store holds at most one and a second record is a no-op rather
 * than a duplicate. Nothing in this service deletes it. Installation mode is decided by its presence; the
 * other fields say when installation closed, which credential closed it, and which application was
 * reserved for the control plane at that moment.
 *
 * <p>{@code reservedApp} is part of the installation, not of the running configuration (ADR-033 §6): a
 * deployment whose configured identifier differs from the recorded one does not start, and one already
 * running denies every control-plane call once it reads a marker that records another, so no configuration
 * change can move the control plane onto the policies of another application.
 *
 * <p>{@code schemaVersion} names the shape it was written in (ADR-034), like every other stored document.
 * Shape 2 is this shape, {@code reservedApp} included. Shape 1 is the marker as an earlier build of this
 * unreleased round wrote it, without the reserved application; this build recognises it and refuses to
 * start on it rather than guessing or backfilling the identifier.
 */
@MongoEntity(collection = InstallationDocument.COLLECTION)
public class InstallationDocument {

    static final String COLLECTION = "installation";

    /** The shape this build writes, and the only one it starts on. */
    static final int SCHEMA_VERSION = 2;

    /** The shape an earlier build of this unreleased change wrote, without {@code reservedApp}. */
    static final int EARLIER_SCHEMA_VERSION = 1;

    public String id;
    public String installedAt;
    public String installedBy;
    public String reservedApp;
    public int schemaVersion = SCHEMA_VERSION;

    public InstallationDocument() {
        // required by the MongoDB POJO codec
    }
}
