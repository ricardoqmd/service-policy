package io.github.ricardoqmd.servicepolicy.persistence;

import org.bson.Document;

import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;

/**
 * Maps between head-pointer persistence documents (ADR-016) and their read models. Content mapping
 * is delegated to the shared {@link PolicyDocumentMapper} so the head's {@code activeContent} and a
 * version's {@code content} use exactly the same validated shape as authoring and storage.
 */
public class PolicyLifecycleDocumentMapper {

    private static final String CREATED_BY = "createdBy";
    private static final String CREATED_AT = "createdAt";
    private static final String CHANGE_REASON = "changeReason";

    private final PolicyDocumentMapper policyMapper;

    public PolicyLifecycleDocumentMapper(PolicyDocumentMapper policyMapper) {
        this.policyMapper = policyMapper;
    }

    public PolicyHead head(PolicyHeadDocument document) {
        return new PolicyHead(
                document.policyId,
                document.app,
                document.resourceType,
                document.activeVersion,
                document.revision,
                audit(document.audit),
                document.activeContent == null ? null : policyMapper.fromDocument(document.activeContent));
    }

    public PolicyVersion version(PolicyVersionDocument document) {
        return new PolicyVersion(policyMapper.fromDocument(document.content), audit(document.audit));
    }

    /**
     * Maps the {@code activeContent} of an active head document to a domain {@link Policy}.
     * Callers must only invoke this on heads where {@code activeContent} is non-null (i.e. heads
     * returned by {@code findActiveByResourceType}).
     */
    public Policy activeContentPolicy(PolicyHeadDocument document) {
        return policyMapper.fromDocument(document.activeContent);
    }

    public Document toAuditDocument(String createdBy, String createdAt, String changeReason) {
        return new Document(CREATED_BY, createdBy).append(CREATED_AT, createdAt).append(CHANGE_REASON, changeReason);
    }

    public PolicyVersionDocument toVersionDocument(String policyId, int version, Policy policy, Document audit) {
        PolicyVersionDocument doc = new PolicyVersionDocument();
        doc.policyId = policyId;
        doc.version = version;
        doc.content = new Document(policyMapper.toDocument(policy));
        doc.audit = audit;
        return doc;
    }

    private PolicyAudit audit(Document doc) {
        if (doc == null) {
            return new PolicyAudit(null, null, null);
        }
        return new PolicyAudit(doc.getString(CREATED_BY), doc.getString(CREATED_AT), doc.getString(CHANGE_REASON));
    }
}
