package io.github.ricardoqmd.servicepolicy.persistence;

import java.time.Instant;
import java.util.Optional;

import jakarta.inject.Singleton;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;

import io.github.ricardoqmd.servicepolicy.problem.AppConfigAlreadyExistsException;
import io.github.ricardoqmd.servicepolicy.problem.AppConfigNotFoundException;
import io.github.ricardoqmd.servicepolicy.problem.InvalidAppConfigException;
import io.github.ricardoqmd.servicepolicy.problem.PreconditionFailedException;
import io.github.ricardoqmd.servicepolicy.problem.ProblemException;

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
    private static final String CREATED_BY = "createdBy";
    private static final String CREATED_AT = "createdAt";
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
     * Creates the app's configuration at {@code revision = 1}.
     *
     * @throws InvalidAppConfigException (400) listing every violation in {@code draft}.
     * @throws AppConfigAlreadyExistsException (409) if the app already has one — arbitrated by the
     *     unique index, so a concurrent create loses rather than duplicating.
     */
    public AppConfig create(String app, AppConfigDraft draft, String callerSubject) {
        AppConfigValidator.validate(draft);

        AppConfigDocument document = new AppConfigDocument();
        document.app = app;
        document.subjectAttributes = AppConfigMapper.toSubjectAttributesDocument(draft.subjectAttributes());
        document.pip = AppConfigMapper.toPipDocument(draft.pip());
        document.revision = 1L;
        document.audit = auditDocument(callerSubject);

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
    public AppConfig replace(String app, AppConfigDraft draft, long ifMatch, String callerSubject) {
        requirePrecondition(app, ifMatch);
        AppConfigValidator.validate(draft);

        UpdateResult cas = repository
                .mongoCollection()
                .updateOne(
                        writable(app, ifMatch),
                        Updates.combine(
                                Updates.set(
                                        SUBJECT_ATTRIBUTES,
                                        AppConfigMapper.toSubjectAttributesDocument(draft.subjectAttributes())),
                                Updates.set(PIP, AppConfigMapper.toPipDocument(draft.pip())),
                                Updates.set(AUDIT, auditDocument(callerSubject)),
                                Updates.inc(REVISION, 1L)));

        if (cas.getMatchedCount() == 0) {
            throw staleOrMissing(app);
        }
        provider.invalidate(app);
        // The re-read can only come back empty if another writer deleted the document between this
        // CAS and this line — race-only, like staleOrMissing below, and untested for the same reason.
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
     * {@link #staleOrMissing} re-reads it through {@link #recognised}, which refuses it.
     */
    private static Bson writable(String app, long ifMatch) {
        return Filters.and(
                identity(app),
                Filters.eq(REVISION, ifMatch),
                StoredDocumentSchemaCondition.writable(SCHEMA_VERSION, AppConfigDocument.SCHEMA_VERSION));
    }

    /**
     * A CAS that matched nothing is either a stale If-Match on an existing document (412), one that no
     * longer exists (404), or one whose shape this build does not know — which the re-read refuses with
     * {@link StoredDocumentSchemaException} before either could be decided.
     *
     * <p>{@link #requirePrecondition} has already read the document and compared revisions, so getting
     * here means another writer replaced, deleted or reshaped the document between that read and this
     * CAS — or that its marker is malformed in a way the read tolerates and the write does not (ADR-034
     * §11), which answers 412 until a person repairs it. It stays because the CAS — not the courtesy
     * check above — is the commit point, and this is what makes its verdict safe to act on.
     */
    private ProblemException staleOrMissing(String app) {
        return find(app)
                .map(existing ->
                        (ProblemException) PreconditionFailedException.forAppConfiguration(app, existing.revision()))
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

    /** Same field names and shape as the head and catalogue audits; configuration has no changeReason. */
    private static Document auditDocument(String callerSubject) {
        return new Document(CREATED_BY, callerSubject)
                .append(CREATED_AT, Instant.now().toString());
    }
}
