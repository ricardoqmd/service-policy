package io.github.ricardoqmd.servicepolicy.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Singleton;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.jboss.logging.Logger;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;

import io.github.ricardoqmd.servicepolicy.domain.policy.HeadStatus;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.problem.PolicyAlreadyExistsException;
import io.github.ricardoqmd.servicepolicy.problem.PolicyNotFoundException;
import io.github.ricardoqmd.servicepolicy.problem.PreconditionFailedException;
import io.github.ricardoqmd.servicepolicy.problem.ProblemException;
import io.github.ricardoqmd.servicepolicy.problem.VersionNotFoundException;

/**
 * Mediates between the head-pointer repositories (ADR-016) and the read/evaluate models, keeping
 * the REST and evaluation layers off Panache. Write invariants (ADR-019): head-first idempotent
 * create with commit point at version insert; CAS-guarded append with commit point at CAS update.
 *
 * <p>After the evaluator cutover (ADR-021) this store is the sole source of policy data for reads,
 * writes, activation, and evaluation; the legacy {@code policies} collection is abandoned.
 *
 * <p>Every persisted write also passes through {@link ActionCatalogueResolver} first (ADR-028), so
 * {@code '*'} is expanded and uncatalogued actions are rejected <em>here</em> rather than in the
 * REST layer. The store is the door that guarantees no unexpanded or uncatalogued policy is ever
 * stored, for the same reason {@code POLICY_ALREADY_EXISTS} lives here and not upstream: an
 * invariant of the stored data belongs to whatever owns the storage.
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed (see ADR-009).
@Singleton
public class PolicyLifecycleStore {

    private static final Logger log = Logger.getLogger(PolicyLifecycleStore.class);
    private static final int DUPLICATE_KEY_CODE = 11000;

    private static final String SCHEMA_VERSION = "schemaVersion";
    private static final String ACTIVE_CONTENT_SCHEMA_VERSION = "activeContentSchemaVersion";

    private final PolicyHeadRepository headRepository;
    private final PolicyVersionRepository versionRepository;
    private final ActionCatalogueResolver catalogueResolver;
    private final PolicyLifecycleDocumentMapper mapper =
            new PolicyLifecycleDocumentMapper(new PolicyDocumentMapper(new ConditionDocumentMapper()));

    PolicyLifecycleStore(
            PolicyHeadRepository headRepository,
            PolicyVersionRepository versionRepository,
            ActionCatalogueResolver catalogueResolver) {
        this.headRepository = headRepository;
        this.versionRepository = versionRepository;
        this.catalogueResolver = catalogueResolver;
    }

    /**
     * @return policy heads for the requested zero-based page, scoped to {@code app} when non-null
     *     (ADR-024) and to the lifecycle {@code status} (ADR-025); the filters compose with AND.
     */
    public List<PolicyHead> findHeads(String app, HeadStatus status, int pageIndex, int size) {
        return headRepository.findHeads(app, status, pageIndex, size).stream()
                .map(PolicyLifecycleStore::recognisedHead)
                .map(mapper::head)
                .toList();
    }

    /** @return the number of policy heads matching the same app/status combination as {@link #findHeads}. */
    public long countHeads(String app, HeadStatus status) {
        return headRepository.countHeads(app, status);
    }

    /** @return the head for the given identity {@code (app, policyId)} (ADR-026), if present. */
    public Optional<PolicyHead> findHead(String app, String policyId) {
        return headRepository
                .findByAppAndPolicyId(app, policyId)
                .map(PolicyLifecycleStore::recognisedHead)
                .map(mapper::head);
    }

    /** @return {@code true} if a head exists for the given identity {@code (app, policyId)}. */
    public boolean headExists(String app, String policyId) {
        return headRepository.existsByAppAndPolicyId(app, policyId);
    }

    /** @return versions of the given policy (newest first) for the requested zero-based page. */
    public List<PolicyVersion> findVersions(String app, String policyId, int pageIndex, int size) {
        return versionRepository.findByAppAndPolicyId(app, policyId, pageIndex, size).stream()
                .map(PolicyLifecycleStore::recognisedVersion)
                .map(mapper::version)
                .toList();
    }

    /** @return the number of versions of the given policy. */
    public long countVersions(String app, String policyId) {
        return versionRepository.countByAppAndPolicyId(app, policyId);
    }

    /** @return the specific version of the given policy, if present. */
    public Optional<PolicyVersion> findVersion(String app, String policyId, int version) {
        return versionRepository
                .findByAppAndPolicyIdAndVersion(app, policyId, version)
                .map(PolicyLifecycleStore::recognisedVersion)
                .map(mapper::version);
    }

    /**
     * @return all active policies for the given app and resource type, for use by the evaluator
     *     (ADR-021, ADR-024). Returns the complete set — not paged — so the evaluator sees every
     *     candidate.
     *     <p>This is the single place where app scoping is enforced for evaluation. Since ADR-026
     *     the policy content no longer carries its app, so the selector can no longer re-check it
     *     downstream; the defence in depth lives here instead, as a re-check of each head's own
     *     {@code app} against the requested one. The Mongo filter already guarantees it — a head
     *     that fails this check means the query and the stored data disagree, which is a bug, so it
     *     is dropped and logged rather than evaluated.
     */
    public List<Policy> activePoliciesFor(String app, String resourceType) {
        return headRepository.findActiveByAppAndResourceType(app, resourceType).stream()
                .map(PolicyLifecycleStore::recognisedHead)
                .filter(head -> {
                    boolean sameApp = app.equals(head.app);
                    if (!sameApp) {
                        log.errorf(
                                "head '%s' returned for app '%s' but belongs to app '%s'; dropped from evaluation",
                                head.policyId, app, head.app);
                    }
                    return sameApp;
                })
                .map(mapper::activeContentPolicy)
                .toList();
    }

    /**
     * Creates a new policy in the given app (head-first, version insert as commit point — ADR-019
     * §create). The head upsert is idempotent; a concurrent create that wins the upsert race does
     * not block this thread — both proceed to the version insert where the unique index arbitrates.
     *
     * <p>Identity is {@code (app, policyId)} (ADR-026), so the upsert filter and the unique indexes
     * are per-app: the same policyId in another app is a different policy and is created normally.
     * The ADR-024 app-immutability guard that used to fire here is gone — it misdiagnosed exactly
     * that legitimate case as an attempt to mutate another app's policy.
     *
     * @throws PolicyAlreadyExistsException (409) if version 1 already exists <em>in this app</em>.
     * @throws io.github.ricardoqmd.servicepolicy.problem.PolicyValidationException (400) if the
     *     actions are not catalogued in this app (ADR-028).
     */
    public void create(String app, Policy policy, String callerSubject, String changeReason) {
        policy = catalogueResolver.resolve(app, policy);
        String policyId = policy.id();
        Document auditDoc = mapper.toAuditDocument(callerSubject, Instant.now().toString(), changeReason);

        Document setOnInsert = new Document()
                .append("policyId", policyId)
                .append("app", app)
                .append("resourceType", policy.resourceType())
                .append("activeVersion", null)
                .append("activeContent", null)
                .append("revision", 0L)
                .append("audit", auditDoc)
                // A raw document, so the POJO's field initialisers do not reach it: the head's own marker
                // is written here (ADR-034 §2). There is no content-marker: a new head holds no copied
                // content, and a marker exists only while its content does (ADR-034 §9). $setOnInsert
                // leaves an existing head as it was — no backfill.
                .append(SCHEMA_VERSION, PolicyHeadDocument.SCHEMA_VERSION);
        try {
            headRepository
                    .mongoCollection()
                    .updateOne(
                            identity(app, policyId),
                            new Document("$setOnInsert", setOnInsert),
                            new UpdateOptions().upsert(true));
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == DUPLICATE_KEY_CODE) {
                log.debugf("head for '%s/%s' already exists (concurrent create); proceeding", app, policyId);
            } else {
                throw e;
            }
        }

        PolicyVersionDocument versionDoc = mapper.toVersionDocument(app, policyId, 1, policy, auditDoc);
        try {
            versionRepository.mongoCollection().insertOne(versionDoc);
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == DUPLICATE_KEY_CODE) {
                throw new PolicyAlreadyExistsException(app, policyId);
            }
            throw e;
        }

        log.infof("policy '%s/%s' created inactive; not evaluable until activation", app, policyId);
    }

    /**
     * Appends a new version (CAS on head revision as commit point — ADR-019 §append).
     *
     * <p>The ADR-024 rule "a new version cannot change the policy's app" still holds, but it is no
     * longer checked: it is now impossible to express. The head is located by {@code (app,
     * policyId)} taken from the path, and the content carries no app of its own (ADR-026), so there
     * are no two app values that could disagree. Appending "under a different app" simply addresses
     * a different policy — which either exists in that app or yields 404.
     *
     * @param ifMatch the revision value from the client's {@code If-Match} header.
     * @return the new version number.
     * @throws PreconditionFailedException (412) if {@code ifMatch} is stale.
     * @throws PolicyNotFoundException (404) if no head exists for {@code (app, policyId)}.
     * @throws io.github.ricardoqmd.servicepolicy.problem.PolicyValidationException (400) if the
     *     actions are not catalogued in this app (ADR-028).
     */
    public int append(
            String app, String policyId, Policy content, long ifMatch, String callerSubject, String changeReason) {
        content = catalogueResolver.resolve(app, content);
        if (!headExists(app, policyId)) {
            throw new PolicyNotFoundException(app, policyId);
        }

        // Read before the CAS only to refuse: a latest version of a shape this build does not know is
        // refused here, before the revision moves (ADR-034 §8). The number is not taken from this read.
        latestVersion(app, policyId);

        UpdateResult cas = headRepository
                .mongoCollection()
                .updateOne(writableHead(app, policyId, ifMatch), Updates.inc("revision", 1L));

        if (cas.getMatchedCount() == 0) {
            throw staleOrMissing(app, policyId);
        }

        // Read again after the CAS, on purpose: ADR-019 §2 takes max(version) + 1 fresh *after* winning it,
        // which is what makes the number collision-free. It still passes through the guard: a version of an
        // unknown shape inserted between the two reads is refused here, with the revision already advanced
        // and no version written — the same benign revision gap ADR-019 accepts for a crash at this point.
        int nextVersion =
                latestVersion(app, policyId).map(latest -> latest.version + 1).orElse(1);

        Document auditDoc = mapper.toAuditDocument(callerSubject, Instant.now().toString(), changeReason);
        PolicyVersionDocument versionDoc = mapper.toVersionDocument(app, policyId, nextVersion, content, auditDoc);
        versionRepository.mongoCollection().insertOne(versionDoc);

        return nextVersion;
    }

    /**
     * Activates a specific version (version read + single-doc CAS commit point — ADR-020).
     *
     * <p>{@code activeContent} is copied verbatim from the version document so the evaluator can
     * read it without a second lookup. Only this method and {@link #deactivate} write
     * {@code activeContent} (ADR-020 §4 behavioral invariant).
     *
     * @throws VersionNotFoundException (404) if the version does not exist on a known policy.
     * @throws PolicyNotFoundException (404) if neither head nor version exists.
     * @throws PreconditionFailedException (412) if {@code ifMatch} is stale.
     */
    public PolicyHead activate(
            String app, String policyId, int version, long ifMatch, String callerSubject, String changeReason) {
        Optional<PolicyVersionDocument> versionDocOpt = versionRepository
                .findByAppAndPolicyIdAndVersion(app, policyId, version)
                .map(PolicyLifecycleStore::recognisedVersion);
        if (versionDocOpt.isEmpty()) {
            if (headExists(app, policyId)) {
                throw new VersionNotFoundException(app, policyId, version);
            }
            throw new PolicyNotFoundException(app, policyId);
        }
        PolicyVersionDocument versionDoc = versionDocOpt.get();

        Document auditDoc = mapper.toAuditDocument(callerSubject, Instant.now().toString(), changeReason);
        UpdateResult cas = headRepository
                .mongoCollection()
                .updateOne(
                        // The head's own marker only: this replaces activeContent and its marker whole, so a
                        // content marker this build does not know is overwritten, not an obstacle.
                        writableHead(app, policyId, ifMatch),
                        Updates.combine(
                                Updates.set("activeVersion", version),
                                Updates.set("activeContent", versionDoc.content),
                                // The copy takes the source's marker with it (ADR-034 §2).
                                Updates.set(ACTIVE_CONTENT_SCHEMA_VERSION, versionDoc.schemaVersion),
                                Updates.set("audit", auditDoc),
                                Updates.inc("revision", 1L)));

        if (cas.getMatchedCount() == 0) {
            throw staleOrMissing(app, policyId);
        }

        return findHead(app, policyId).orElseThrow(() -> new PolicyNotFoundException(app, policyId));
    }

    /**
     * Deactivates the policy, clearing the active version pointer (soft retire — ADR-014, ADR-020).
     * Version history is untouched.
     *
     * @throws PolicyNotFoundException (404) if the policy does not exist.
     * @throws PreconditionFailedException (412) if {@code ifMatch} is stale.
     */
    public PolicyHead deactivate(String app, String policyId, long ifMatch, String callerSubject, String changeReason) {
        Document auditDoc = mapper.toAuditDocument(callerSubject, Instant.now().toString(), changeReason);
        UpdateResult cas = headRepository
                .mongoCollection()
                .updateOne(
                        writableHead(app, policyId, ifMatch),
                        Updates.combine(
                                Updates.set("activeVersion", null),
                                Updates.set("activeContent", null),
                                // The content goes, and its marker with it: removed, not set to a value
                                // (ADR-034 §9).
                                Updates.unset(ACTIVE_CONTENT_SCHEMA_VERSION),
                                Updates.set("audit", auditDoc),
                                Updates.inc("revision", 1L)));

        if (cas.getMatchedCount() == 0) {
            throw staleOrMissing(app, policyId);
        }

        return findHead(app, policyId).orElseThrow(() -> new PolicyNotFoundException(app, policyId));
    }

    /**
     * The one entry point through which a head read back from storage reaches anything else (ADR-034
     * §7). Every read of a head in this store passes through here before any of its fields is used,
     * including the reads that never reach a mapper.
     *
     * <p>Both markers are checked: {@code activeContentSchemaVersion} names a version's shape, so it is
     * judged against the versions' marker, not the head's — and only while there is content for it to
     * describe (ADR-034 §9). A head without content is not refused over a marker left beside nothing,
     * which also keeps readable a head stored with one before deactivation began removing it.
     *
     * @throws StoredDocumentSchemaException if either marker names a shape this build does not know.
     */
    private static PolicyHeadDocument recognisedHead(PolicyHeadDocument head) {
        recognisedOwnShape(head);
        if (head.activeContent != null && head.activeContentSchemaVersion != PolicyVersionDocument.SCHEMA_VERSION) {
            throw new StoredDocumentSchemaException(
                    PolicyHeadDocument.COLLECTION,
                    head.id,
                    ACTIVE_CONTENT_SCHEMA_VERSION,
                    head.activeContentSchemaVersion);
        }
        return head;
    }

    /**
     * The head's own shape only, for the one read that uses nothing of the head but its revision
     * ({@link #staleOrMissing}). Every read that returns a head, and so its content, goes through
     * {@link #recognisedHead} instead.
     *
     * @throws StoredDocumentSchemaException if the head's own marker names a shape this build does not know.
     */
    private static PolicyHeadDocument recognisedOwnShape(PolicyHeadDocument head) {
        if (head.schemaVersion != PolicyHeadDocument.SCHEMA_VERSION) {
            throw new StoredDocumentSchemaException(
                    PolicyHeadDocument.COLLECTION, head.id, SCHEMA_VERSION, head.schemaVersion);
        }
        return head;
    }

    /**
     * The same entry point for a version: every read of one in this store passes through here first
     * (ADR-034 §7).
     *
     * @throws StoredDocumentSchemaException if the marker names a shape this build does not know.
     */
    private static PolicyVersionDocument recognisedVersion(PolicyVersionDocument version) {
        if (version.schemaVersion != PolicyVersionDocument.SCHEMA_VERSION) {
            throw new StoredDocumentSchemaException(
                    PolicyVersionDocument.COLLECTION, version.id, SCHEMA_VERSION, version.schemaVersion);
        }
        return version;
    }

    /** @return the policy's newest version, through its guard, if it has any. */
    private Optional<PolicyVersionDocument> latestVersion(String app, String policyId) {
        return versionRepository.findByAppAndPolicyId(app, policyId, 0, 1).stream()
                .findFirst()
                .map(PolicyLifecycleStore::recognisedVersion);
    }

    /** The composite-identity filter every single-policy write CASes against (ADR-026). */
    private static Bson identity(String app, String policyId) {
        return Filters.and(Filters.eq("app", app), Filters.eq("policyId", policyId));
    }

    /**
     * The condition of every write to an existing head: its identity, the caller's {@code If-Match}, and
     * a shape this build knows (ADR-034 §8). The marker is part of the filter rather than a read before
     * it, so the engine decides the condition and applies the update in one operation — there is no
     * window in which the head could change shape between a check and the write.
     *
     * <p>Only the head's <em>own</em> marker is conditioned. No head write depends on the copied content
     * it may hold: append does not touch it, and activation and deactivation replace or remove it whole.
     *
     * <p>"Known" is the marker as an integer, 32- or 64-bit, or absent (ADR-034 §3, §10) — see
     * {@link StoredDocumentSchemaCondition} for why equality alone would admit values the read refuses.
     *
     * <p>A head this build does not know therefore fails the CAS like a stale revision, and
     * {@link #staleOrMissing} re-reads it through {@link #recognisedOwnShape}, which refuses it: nothing is
     * written, and no new outcome is needed to say so.
     */
    private static Bson writableHead(String app, String policyId, long ifMatch) {
        return Filters.and(
                identity(app, policyId),
                Filters.eq("revision", ifMatch),
                StoredDocumentSchemaCondition.writable(SCHEMA_VERSION, PolicyHeadDocument.SCHEMA_VERSION));
    }

    /**
     * A CAS that matched nothing is either a stale If-Match on an existing policy (412), a policy that
     * does not exist in this app (404), or a head whose shape this build does not know — which the
     * re-read below refuses with {@link StoredDocumentSchemaException} before either could be decided.
     *
     * <p>The re-read exists only to report a revision, so it checks the head's <em>own</em> marker and not
     * the marker of the content it does not return. A head this build can read in its own shape, holding
     * content copied in a shape it cannot, still answers a stale {@code If-Match} with its revision — the
     * one way a client of this build can learn it, and so reach the activation that replaces that content.
     */
    private ProblemException staleOrMissing(String app, String policyId) {
        Optional<PolicyHeadDocument> existing =
                headRepository.findByAppAndPolicyId(app, policyId).map(PolicyLifecycleStore::recognisedOwnShape);
        if (existing.isPresent()) {
            return new PreconditionFailedException(app, policyId, existing.get().revision);
        }
        return new PolicyNotFoundException(app, policyId);
    }
}
