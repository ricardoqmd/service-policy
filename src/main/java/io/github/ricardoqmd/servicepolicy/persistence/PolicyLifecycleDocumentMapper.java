package io.github.ricardoqmd.servicepolicy.persistence;

import org.bson.Document;

/**
 * Maps head-pointer persistence documents (ADR-016) to their read models. Content mapping is
 * delegated to the shared {@link PolicyDocumentMapper} so the head's {@code activeContent} and a
 * version's {@code content} use exactly the same validated shape as authoring and storage.
 *
 * <p>Read-only in this slice: write mapping (read model -&gt; document) lands with the
 * create/activate operations in later slices.
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
                document.resourceType,
                document.activeVersion,
                document.revision,
                audit(document.audit),
                document.activeContent == null ? null : policyMapper.fromDocument(document.activeContent));
    }

    public PolicyVersion version(PolicyVersionDocument document) {
        return new PolicyVersion(policyMapper.fromDocument(document.content), audit(document.audit));
    }

    private PolicyAudit audit(Document doc) {
        if (doc == null) {
            return new PolicyAudit(null, null, null);
        }
        return new PolicyAudit(doc.getString(CREATED_BY), doc.getString(CREATED_AT), doc.getString(CHANGE_REASON));
    }
}
