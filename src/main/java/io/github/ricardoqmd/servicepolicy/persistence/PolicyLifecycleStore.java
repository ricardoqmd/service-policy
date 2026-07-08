package io.github.ricardoqmd.servicepolicy.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Singleton;

import org.bson.Document;
import org.jboss.logging.Logger;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;

import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.rest.PreconditionFailedException;

/**
 * Mediates between the head-pointer repositories (ADR-016) and the read models, keeping the REST
 * layer off Panache. Write invariants (ADR-019): head-first idempotent create with commit point at
 * version insert; CAS-guarded append with commit point at CAS update.
 *
 * <p>Introduced alongside the legacy {@link PolicyStore}, which keeps serving the evaluator from
 * the {@code policies} collection until the activation slice repoints it (ADR-016).
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed (see ADR-009).
@Singleton
public class PolicyLifecycleStore {

    private static final Logger log = Logger.getLogger(PolicyLifecycleStore.class);
    private static final int DUPLICATE_KEY_CODE = 11000;

    private final PolicyHeadRepository headRepository;
    private final PolicyVersionRepository versionRepository;
    private final PolicyLifecycleDocumentMapper mapper =
            new PolicyLifecycleDocumentMapper(new PolicyDocumentMapper(new ConditionDocumentMapper()));

    PolicyLifecycleStore(PolicyHeadRepository headRepository, PolicyVersionRepository versionRepository) {
        this.headRepository = headRepository;
        this.versionRepository = versionRepository;
    }

    /** @return active policy heads for the requested zero-based page. */
    public List<PolicyHead> findActiveHeads(int pageIndex, int size) {
        return headRepository.findActiveHeads(pageIndex, size).stream()
                .map(mapper::head)
                .toList();
    }

    /** @return the number of active policy heads. */
    public long countActiveHeads() {
        return headRepository.countActiveHeads();
    }

    /** @return the head for the given policyId, if present. */
    public Optional<PolicyHead> findHead(String policyId) {
        return headRepository.findByPolicyId(policyId).map(mapper::head);
    }

    /** @return {@code true} if a head exists for the given policyId. */
    public boolean headExists(String policyId) {
        return headRepository.existsByPolicyId(policyId);
    }

    /** @return versions of the given policy (newest first) for the requested zero-based page. */
    public List<PolicyVersion> findVersions(String policyId, int pageIndex, int size) {
        return versionRepository.findByPolicyId(policyId, pageIndex, size).stream()
                .map(mapper::version)
                .toList();
    }

    /** @return the number of versions of the given policy. */
    public long countVersions(String policyId) {
        return versionRepository.countByPolicyId(policyId);
    }

    /** @return the specific version of the given policy, if present. */
    public Optional<PolicyVersion> findVersion(String policyId, int version) {
        return versionRepository.findByPolicyIdAndVersion(policyId, version).map(mapper::version);
    }

    /**
     * Creates a new policy (head-first, version insert as commit point — ADR-019 §create).
     * The head upsert is idempotent; a concurrent create that wins the upsert race does not block
     * this thread — both proceed to the version insert where the unique index arbitrates.
     *
     * @throws PolicyAlreadyExistsException (409) if version 1 already exists.
     */
    public void create(Policy policy, String callerSubject, String changeReason) {
        String policyId = policy.id();
        Document auditDoc = mapper.toAuditDocument(callerSubject, Instant.now().toString(), changeReason);

        Document setOnInsert = new Document()
                .append("policyId", policyId)
                .append("resourceType", policy.resourceType())
                .append("activeVersion", null)
                .append("activeContent", null)
                .append("revision", 0L)
                .append("audit", auditDoc);
        try {
            headRepository
                    .mongoCollection()
                    .updateOne(
                            Filters.eq("policyId", policyId),
                            new Document("$setOnInsert", setOnInsert),
                            new UpdateOptions().upsert(true));
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == DUPLICATE_KEY_CODE) {
                log.debugf("head for '%s' already exists (concurrent create); proceeding", policyId);
            } else {
                throw e;
            }
        }

        PolicyVersionDocument versionDoc = mapper.toVersionDocument(policyId, 1, policy, auditDoc);
        try {
            versionRepository.mongoCollection().insertOne(versionDoc);
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == DUPLICATE_KEY_CODE) {
                throw new PolicyAlreadyExistsException(policyId);
            }
            throw e;
        }

        log.infof("policy '%s' created inactive; not evaluable until activation", policyId);
    }

    /**
     * Appends a new version (CAS on head revision as commit point — ADR-019 §append).
     *
     * @param ifMatch the revision value from the client's {@code If-Match} header.
     * @return the new version number.
     * @throws PreconditionFailedException (412) if {@code ifMatch} is stale.
     * @throws PolicyNotFoundException (404) if no head exists for the given id.
     */
    public int append(String policyId, Policy content, long ifMatch, String callerSubject, String changeReason) {
        UpdateResult cas = headRepository
                .mongoCollection()
                .updateOne(
                        Filters.and(Filters.eq("policyId", policyId), Filters.eq("revision", ifMatch)),
                        Updates.inc("revision", 1L));

        if (cas.getMatchedCount() == 0) {
            Optional<PolicyHead> existing = findHead(policyId);
            if (existing.isPresent()) {
                throw new PreconditionFailedException(policyId, existing.get().revision());
            }
            throw new PolicyNotFoundException(policyId);
        }

        List<PolicyVersionDocument> latest = versionRepository.findByPolicyId(policyId, 0, 1);
        int nextVersion = latest.isEmpty() ? 1 : latest.get(0).version + 1;

        Document auditDoc = mapper.toAuditDocument(callerSubject, Instant.now().toString(), changeReason);
        PolicyVersionDocument versionDoc = mapper.toVersionDocument(policyId, nextVersion, content, auditDoc);
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
    public PolicyHead activate(String policyId, int version, long ifMatch, String callerSubject, String changeReason) {
        Optional<PolicyVersionDocument> versionDocOpt = versionRepository.findByPolicyIdAndVersion(policyId, version);
        if (versionDocOpt.isEmpty()) {
            if (headExists(policyId)) {
                throw new VersionNotFoundException(policyId, version);
            }
            throw new PolicyNotFoundException(policyId);
        }
        PolicyVersionDocument versionDoc = versionDocOpt.get();

        Document auditDoc = mapper.toAuditDocument(callerSubject, Instant.now().toString(), changeReason);
        UpdateResult cas = headRepository
                .mongoCollection()
                .updateOne(
                        Filters.and(Filters.eq("policyId", policyId), Filters.eq("revision", ifMatch)),
                        Updates.combine(
                                Updates.set("activeVersion", version),
                                Updates.set("activeContent", versionDoc.content),
                                Updates.set("audit", auditDoc),
                                Updates.inc("revision", 1L)));

        if (cas.getMatchedCount() == 0) {
            Optional<PolicyHead> existing = findHead(policyId);
            if (existing.isPresent()) {
                throw new PreconditionFailedException(policyId, existing.get().revision());
            }
            throw new PolicyNotFoundException(policyId);
        }

        return findHead(policyId).orElseThrow(() -> new PolicyNotFoundException(policyId));
    }

    /**
     * Deactivates the policy, clearing the active version pointer (soft retire — ADR-014, ADR-020).
     * Version history is untouched.
     *
     * @throws PolicyNotFoundException (404) if the policy does not exist.
     * @throws PreconditionFailedException (412) if {@code ifMatch} is stale.
     */
    public PolicyHead deactivate(String policyId, long ifMatch, String callerSubject, String changeReason) {
        Document auditDoc = mapper.toAuditDocument(callerSubject, Instant.now().toString(), changeReason);
        UpdateResult cas = headRepository
                .mongoCollection()
                .updateOne(
                        Filters.and(Filters.eq("policyId", policyId), Filters.eq("revision", ifMatch)),
                        Updates.combine(
                                Updates.set("activeVersion", null),
                                Updates.set("activeContent", null),
                                Updates.set("audit", auditDoc),
                                Updates.inc("revision", 1L)));

        if (cas.getMatchedCount() == 0) {
            Optional<PolicyHead> existing = findHead(policyId);
            if (existing.isPresent()) {
                throw new PreconditionFailedException(policyId, existing.get().revision());
            }
            throw new PolicyNotFoundException(policyId);
        }

        return findHead(policyId).orElseThrow(() -> new PolicyNotFoundException(policyId));
    }
}
