package io.github.ricardoqmd.servicepolicy.persistence;

import java.util.Optional;
import java.util.function.Consumer;

import jakarta.inject.Singleton;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;

import io.github.ricardoqmd.servicepolicy.problem.AppConfigAlreadyExistsException;
import io.github.ricardoqmd.servicepolicy.problem.AppConfigNotFoundException;
import io.github.ricardoqmd.servicepolicy.problem.InvalidAppConfigException;
import io.github.ricardoqmd.servicepolicy.problem.PreconditionFailedException;

/**
 * The only door to the per-application configuration collection (ADR-029): REST reads and writes
 * through this store, never through {@link AppConfigRepository} directly, exactly as
 * {@link ActionCatalogueStore} does for the action catalogue.
 *
 * <p>Validation lives here rather than in the resource, for the same reason wildcard expansion lives
 * in {@link PolicyLifecycleStore} (ADR-028): an invariant about what may be <em>stored</em> belongs
 * to whatever owns the storage, so no caller can route around it.
 *
 * <p>Writes follow the conditional-write invariants of ADR-018: the precondition is checked before
 * anything else the method would object to, a single-document CAS on {@code (app, revision)} is the
 * commit point, and a CAS that matches nothing is disambiguated into 412 or 404. Create relies on
 * the unique index rather than a pre-check, so two concurrent creates cannot both win.
 *
 * <p>Every successful write invalidates {@link AppConfigProvider}, which is what makes a change
 * visible to the next read on this instance (ADR-029 staleness contract).
 *
 * <p>There is no in-use guard here, unlike the action catalogue's. Nothing references a
 * configuration document, and removing one cannot widen a decision: it withdraws the engine's
 * ability to derive attributes, which makes fewer things determinable and never more permitted
 * (ADR-029 §absent configuration degrades, it does not deny).
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed (see ADR-009).
@Singleton
public class AppConfigStore {

    private static final int DUPLICATE_KEY_CODE = 11000;

    private static final String APP = "app";
    private static final String SUBJECT_ATTRIBUTES = "subjectAttributes";
    private static final String PIP = "pip";
    private static final String REVISION = "revision";
    private static final String AUDIT = "audit";
    private static final String SCHEMA_VERSION = "schemaVersion";

    private final AppConfigRepository repository;
    private final AppConfigProvider provider;

    AppConfigStore(AppConfigRepository repository, AppConfigProvider provider) {
        this.repository = repository;
        this.provider = provider;
    }

    /** @return the app's configuration, if it has one. */
    public Optional<AppConfig> find(String app) {
        return repository.findByApp(app).map(AppConfigStore::recognised).map(AppConfigMapper::toAppConfig);
    }

    /**
     * The claim path stored for one subject attribute of {@code app}, exactly as stored. {@link #find} reads
     * every stored value as text, so {@code 5} or {@code ["apps"]} come back as claim paths; a check that must
     * tell a usable claim path from a value that only reads as one reads it here instead.
     *
     * @return empty when {@code app} has no configuration, no mapping, or no value for {@code attribute};
     *     otherwise the stored value, of whatever type it was stored with.
     * @throws StoredDocumentSchemaException if the configuration's marker names a shape this build does not
     *     know, exactly as {@link #find} does.
     */
    public Optional<Object> storedMappingValue(String app, String attribute) {
        if (find(app).isEmpty()) {
            return Optional.empty();
        }
        Document stored = repository
                .mongoCollection()
                .withDocumentClass(Document.class)
                .find(identity(app))
                .first();
        if (stored == null || !(stored.get(SUBJECT_ATTRIBUTES) instanceof Document mapping)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapping.get(attribute));
    }

    /**
     * Creates the app's configuration at {@code revision = 1}.
     *
     * @throws InvalidAppConfigException (400) listing every violation in {@code draft}.
     * @throws AppConfigAlreadyExistsException (409) if the app already has one — arbitrated by the
     *     unique index, so a concurrent create loses rather than duplicating.
     */
    public AppConfig create(String app, AppConfigDraft draft, AuditActor actor) {
        AppConfigValidator.validate(draft);

        AppConfigDocument document = new AppConfigDocument();
        document.app = app;
        document.subjectAttributes = AppConfigMapper.toSubjectAttributesDocument(draft.subjectAttributes());
        document.pip = AppConfigMapper.toPipDocument(draft.pip());
        document.revision = 1L;
        document.audit = AuditDocuments.of(actor);

        try {
            repository.mongoCollection().insertOne(document);
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == DUPLICATE_KEY_CODE) {
                throw new AppConfigAlreadyExistsException(app);
            }
            // Any other write error is infrastructure, not a client mistake, so it surfaces as a 500
            // rather than being dressed up as a 409. Reachable only from a racing writer or a failing
            // database, hence untested: provoking it would mean breaking Mongo mid-insert.
            throw e;
        }
        provider.invalidate(app);
        return AppConfigMapper.toAppConfig(document);
    }

    /**
     * Replaces the FULL configuration document — both sections, so omitting one removes it. A merge
     * would make "remove the attribute source" inexpressible without a second verb.
     *
     * @param ifMatch the revision value from the client's {@code If-Match} header.
     * @throws AppConfigNotFoundException (404) if the app has no configuration.
     * @throws PreconditionFailedException (412) if {@code ifMatch} is stale.
     * @throws InvalidAppConfigException (400) listing every violation in {@code draft}.
     */
    public AppConfig replace(String app, AppConfigDraft draft, long ifMatch, AuditActor actor) {
        return replace(app, draft, ifMatch, actor, valid -> {});
    }

    /**
     * The same replace, with one more refusal the caller supplies, applied where validation is: after the
     * precondition and after {@link AppConfigValidator}, before the CAS. A guard that throws leaves the
     * stored document exactly as it was.
     *
     * @param writeGuard inspects the validated draft and throws to refuse it.
     */
    public AppConfig replace(
            String app, AppConfigDraft draft, long ifMatch, AuditActor actor, Consumer<AppConfigDraft> writeGuard) {
        requirePrecondition(app, ifMatch);
        AppConfigValidator.validate(draft);
        writeGuard.accept(draft);

        UpdateResult cas = repository
                .mongoCollection()
                .updateOne(
                        writable(app, ifMatch),
                        Updates.combine(
                                Updates.set(
                                        SUBJECT_ATTRIBUTES,
                                        AppConfigMapper.toSubjectAttributesDocument(draft.subjectAttributes())),
                                Updates.set(PIP, AppConfigMapper.toPipDocument(draft.pip())),
                                Updates.set(AUDIT, AuditDocuments.of(actor)),
                                Updates.inc(REVISION, 1L)));

        if (cas.getMatchedCount() == 0) {
            throw staleOrMissing(app);
        }
        provider.invalidate(app);
        // The re-read can only come back empty if another writer deleted the document between this
        // CAS and this line — race-only, and untested for that reason.
        return find(app).orElseThrow(() -> new AppConfigNotFoundException(app));
    }

    /**
     * Removes the app's configuration. Unguarded on purpose: the app simply returns to evaluating on
     * asserted attributes alone, which is how the engine behaved before ADR-029.
     *
     * @param ifMatch the revision value from the client's {@code If-Match} header.
     * @throws AppConfigNotFoundException (404) if the app has no configuration.
     * @throws PreconditionFailedException (412) if {@code ifMatch} is stale.
     */
    public void delete(String app, long ifMatch) {
        requirePrecondition(app, ifMatch);

        DeleteResult result = repository.mongoCollection().deleteOne(writable(app, ifMatch));

        if (result.getDeletedCount() == 0) {
            throw staleOrMissing(app);
        }
        provider.invalidate(app);
    }

    /**
     * Loads the document and checks {@code If-Match} before the method does anything else it could
     * object to — so a stale precondition is answered with 412 rather than with a validation error
     * the client cannot act on. RFC 9110 §13.1 puts preconditions ahead of method semantics for
     * exactly this reason: a client holding a stale ETag is reasoning about a document it has not
     * seen, and the only useful answer is "reload and look again".
     *
     * <p>Best-effort by construction — this is a read, so another writer can still slip in before the
     * CAS. The CAS remains the commit point and the authority (ADR-018/ADR-019), and it is the CAS, not
     * this read, that carries the shape condition (ADR-034 §8).
     */
    private void requirePrecondition(String app, long ifMatch) {
        AppConfigDocument current = repository
                .findByApp(app)
                .map(AppConfigStore::recognised)
                .orElseThrow(() -> new AppConfigNotFoundException(app));
        if (current.revision != ifMatch) {
            throw PreconditionFailedException.forAppConfiguration(app, current.revision);
        }
    }

    /** Configuration is a singleton per app, so {@code app} alone is the whole identity (ADR-029). */
    private static Bson identity(String app) {
        return Filters.eq(APP, app);
    }

    /**
     * The condition of every write to an existing configuration: its identity, the caller's
     * {@code If-Match}, and a shape this build knows (ADR-034 §8) — in the write itself, not in the read
     * before it. A document of an unknown shape fails the CAS like a stale revision, and
     * {@link #staleOrMissing} asks the store which cause it was.
     */
    private static Bson writable(String app, long ifMatch) {
        return Filters.and(identity(app), Filters.eq(REVISION, ifMatch), writableMarker());
    }

    /** The marker as the write condition accepts it (ADR-034 §8, §10). */
    private static Bson writableMarker() {
        return StoredDocumentSchemaCondition.writable(SCHEMA_VERSION, AppConfigDocument.SCHEMA_VERSION);
    }

    /**
     * A CAS that matched nothing has exactly three causes, and the store is asked which one it was rather
     * than inferred from a revision. {@link #requirePrecondition} has already compared revisions, so getting
     * here means another writer replaced, deleted or reshaped the document since — or that its marker is
     * malformed in a way the read tolerates and the write does not (ADR-034 §11). Answering that last case
     * with 412 would tell a caller whose {@code If-Match} is current that it is not; and the revision cannot
     * decide it, because a configuration deleted and recreated is reborn at revision 1.
     *
     * <ul>
     *   <li>no document for the app — 404;
     *   <li>a document with a marker this build accepts for writing — genuinely stale, 412 with its revision;
     *   <li>a document whose marker this build does not accept for writing — the shape refusal, whatever its
     *       revision, naming the marker as stored rather than as the codec would convert it.
     * </ul>
     *
     * <p>It stays because the CAS — not the courtesy check above — is the commit point, and this is what
     * makes its verdict safe to act on.
     */
    private RuntimeException staleOrMissing(String app) {
        MongoCollection<Document> configs = repository.mongoCollection().withDocumentClass(Document.class);
        Document stored = configs.find(identity(app))
                .projection(Projections.include(SCHEMA_VERSION))
                .first();
        if (stored == null) {
            return new AppConfigNotFoundException(app);
        }
        if (configs.countDocuments(Filters.and(identity(app), writableMarker())) == 0) {
            return new StoredDocumentSchemaException(
                    AppConfigDocument.COLLECTION,
                    stored.getObjectId("_id"),
                    SCHEMA_VERSION,
                    stored.get(SCHEMA_VERSION));
        }
        return find(app)
                .<RuntimeException>map(
                        existing -> PreconditionFailedException.forAppConfiguration(app, existing.revision()))
                .orElseGet(() -> new AppConfigNotFoundException(app));
    }

    /**
     * The one entry point through which a configuration document read back from storage reaches
     * anything else (ADR-034 §7). Every read of the collection passes through here before any field is
     * used — {@link AppConfigProvider}'s included, which is why this is static and package-private: the
     * provider is this store's dependency, so it cannot inject the store, and still has to be guarded.
     *
     * @throws StoredDocumentSchemaException if the marker names a shape this build does not know.
     */
    static AppConfigDocument recognised(AppConfigDocument document) {
        if (document.schemaVersion != AppConfigDocument.SCHEMA_VERSION) {
            throw new StoredDocumentSchemaException(
                    AppConfigDocument.COLLECTION, document.id, SCHEMA_VERSION, document.schemaVersion);
        }
        return document;
    }
}
