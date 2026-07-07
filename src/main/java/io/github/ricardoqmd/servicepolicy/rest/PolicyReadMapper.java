package io.github.ricardoqmd.servicepolicy.rest;

import java.util.Map;

import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.persistence.ConditionDocumentMapper;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyAudit;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyDocumentMapper;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHead;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersion;

/**
 * Maps persistence read models to the REST wire shapes (ADR-017). Content is rendered through the
 * shared {@link PolicyDocumentMapper}, so the {@code activeContent} / version content on the wire is
 * exactly the document shape that authoring accepts and storage persists.
 */
public class PolicyReadMapper {

    private final PolicyDocumentMapper policyMapper = new PolicyDocumentMapper(new ConditionDocumentMapper());

    public PolicyHeadSummary headSummary(PolicyHead head) {
        return new PolicyHeadSummary(
                head.policyId(), head.resourceType(), head.activeVersion(), head.revision(), auditView(head.audit()));
    }

    public PolicyHeadView headView(PolicyHead head) {
        Map<String, Object> activeContent =
                head.activeContent() == null ? null : policyMapper.toDocument(head.activeContent());
        return new PolicyHeadView(
                head.policyId(),
                head.resourceType(),
                head.activeVersion(),
                head.revision(),
                auditView(head.audit()),
                activeContent);
    }

    public PolicyVersionSummary versionSummary(PolicyVersion version) {
        Policy content = version.content();
        return new PolicyVersionSummary(
                content.id(), content.version(), content.resourceType(), auditView(version.audit()));
    }

    public Map<String, Object> versionContent(PolicyVersion version) {
        return policyMapper.toDocument(version.content());
    }

    private AuditView auditView(PolicyAudit audit) {
        return audit == null ? null : new AuditView(audit.createdBy(), audit.createdAt(), audit.changeReason());
    }
}
