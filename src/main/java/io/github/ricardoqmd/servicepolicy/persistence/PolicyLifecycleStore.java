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
                .map(mapper::head)
                .toList();
    }

    /** @return the number of policy heads matching the same app/status combination as {@link #findHeads}. */
    public long countHeads(String app, HeadStatus status) {
        return headRepository.countHeads(app, status);
    }

    /** @return the head for the given identity {@code (app, policyId)} (ADR-026), if present. */
    public Optional<PolicyHead> findHead(String app, String policyId) {
        return headRepository.findByAppAndPolicyId(app, policyId).map(mapper::head);
    }

    /** @return {@code true} if a head exists for the given identity {@code (app, policyId)}. */
    public boolean headExists(String app, String policyId) {
        return headRepository.existsByAppAndPolicyId(app, policyId);
    }

    /** @return versions of the given policy (newest first) for the requested zero-based page. */
    public List<PolicyVersion> findVersions(String app, String policyId, int pageIndex, int size) {
        return versionRepository.findByAppAndPolicyId(app, policyId, pageIndex, size).stream()
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
                .append("audit", auditDoc);
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

        UpdateResult cas = headRepository
                .mongoCollection()
                .updateOne(
                        Filters.and(identity(app, policyId), Filters.eq("revision", ifMatch)),
                        Updates.inc("revision", 1L));

        if (cas.getMatchedCount() == 0) {
            throw staleOrMissing(app, policyId);
        }

        List<PolicyVersionDocument> latest = versionRepository.findByAppAndPolicyId(app, policyId, 0, 1);
        int nextVersion = latest.isEmpty() ? 1 : latest.get(0).version + 1;

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
        Optional<PolicyVersionDocument> versionDocOpt =
                versionRepository.findByAppAndPolicyIdAndVersion(app, policyId, version);
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
                        Filters.and(identity(app, policyId), Filters.eq("revision", ifMatch)),
                        Updates.combine(
                                Updates.set("activeVersion", version),
                                Updates.set("activeContent", versionDoc.content),
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
                        Filters.and(identity(app, policyId), Filters.eq("revision", ifMatch)),
                        Updates.combine(
                                Updates.set("activeVersion", null),
                                Updates.set("activeContent", null),
                                Updates.set("audit", auditDoc),
                                Updates.inc("revision", 1L)));

        if (cas.getMatchedCount() == 0) {
            throw staleOrMissing(app, policyId);
        }

        return findHead(app, policyId).orElseThrow(() -> new PolicyNotFoundException(app, policyId));
    }

    /** The composite-identity filter every single-policy write CASes against (ADR-026). */
    private static Bson identity(String app, String policyId) {
        return Filters.and(Filters.eq("app", app), Filters.eq("policyId", policyId));
    }

    /**
     * A CAS that matched nothing is either a stale If-Match on an existing policy (412) or a policy
     * that does not exist in this app (404).
     */
    private ProblemException staleOrMissing(String app, String policyId) {
        Optional<PolicyHead> existing = findHead(app, policyId);
        if (existing.isPresent()) {
            return new PreconditionFailedException(app, policyId, existing.get().revision());
        }
        return new PolicyNotFoundException(app, policyId);
    }
}
