package io.github.ricardoqmd.servicepolicy.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import io.github.ricardoqmd.servicepolicy.domain.policy.HeadStatus;
import io.quarkus.mongodb.panache.PanacheMongoRepository;
import io.quarkus.mongodb.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;

/** Repository for {@link PolicyHeadDocument} (ADR-016). */
@ApplicationScoped
public class PolicyHeadRepository implements PanacheMongoRepository<PolicyHeadDocument> {

    private static final String IDENTITY = "{'app': ?1, 'policyId': ?2}";
    private static final String ACTIVE_CLAUSE = "'activeVersion': {$ne: null}";
    private static final String INACTIVE_CLAUSE = "'activeVersion': null";
    private static final String APP_CLAUSE = "'app': ?1";
    private static final Sort BY_POLICY_ID = Sort.ascending("policyId");

    /**
     * @return heads matching the optional app scope (ADR-024) and the lifecycle status (ADR-025) —
     *     the two filters compose with AND — ordered by policyId, for the requested zero-based page.
     */
    public List<PolicyHeadDocument> findHeads(String app, HeadStatus status, int pageIndex, int size) {
        return headQuery(app, status).page(Page.of(pageIndex, size)).list();
    }

    /** @return the number of heads matching the same app/status combination as {@link #findHeads}. */
    public long countHeads(String app, HeadStatus status) {
        String filter = headFilter(app, status);
        if (filter == null) {
            return count();
        }
        return app == null ? count(filter) : count(filter, app);
    }

    private PanacheQuery<PolicyHeadDocument> headQuery(String app, HeadStatus status) {
        String filter = headFilter(app, status);
        if (filter == null) {
            return findAll(BY_POLICY_ID);
        }
        return app == null ? find(filter, BY_POLICY_ID) : find(filter, BY_POLICY_ID, app);
    }

    /**
     * @return the Mongo filter for the given app/status combination, or {@code null} when neither
     *     filter applies (status {@code ALL}, no app) and every head matches.
     */
    private static String headFilter(String app, HeadStatus status) {
        List<String> clauses = new ArrayList<>(2);
        switch (status) {
            case ACTIVE -> clauses.add(ACTIVE_CLAUSE);
            case INACTIVE -> clauses.add(INACTIVE_CLAUSE);
            case ALL -> {
                /* no state clause */
            }
        }
        if (app != null) {
            clauses.add(APP_CLAUSE);
        }
        return clauses.isEmpty() ? null : "{" + String.join(", ", clauses) + "}";
    }

    /** @return the head for the given composite identity {@code (app, policyId)} (ADR-026), if present. */
    public Optional<PolicyHeadDocument> findByAppAndPolicyId(String app, String policyId) {
        return find(IDENTITY, app, policyId).firstResultOptional();
    }

    /** @return {@code true} if a head exists for the given composite identity {@code (app, policyId)}. */
    public boolean existsByAppAndPolicyId(String app, String policyId) {
        return count(IDENTITY, app, policyId) > 0;
    }

    /**
     * @return all active heads (non-null {@code activeVersion}) for the given app and resource type.
     *     Returns the complete set — not paged — because the evaluator needs every candidate.
     */
    public List<PolicyHeadDocument> findActiveByAppAndResourceType(String app, String resourceType) {
        return find("{'activeVersion': {$ne: null}, 'app': ?1, 'resourceType': ?2}", app, resourceType)
                .list();
    }
}
