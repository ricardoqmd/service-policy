package io.github.ricardoqmd.servicepolicy.rest;

import java.util.List;

/**
 * Request body of {@code PUT /v1/apps/&#123;app&#125;/action-catalogue/&#123;resourceType&#125;}
 * (ADR-028): the FULL replacement action set — this is a replace, not a merge.
 *
 * <p>The resource type is the path's, so it is not repeated here; as everywhere else, an undeclared
 * field is rejected rather than ignored (ADR-026).
 *
 * @param actions the complete new action set; same validation as on create.
 * @param subject optional declaration of on whose behalf the write is made (ADR-033 §4). It confers no
 *     authority; it changes only what the audit records.
 */
public record CatalogueEntryReplace(List<String> actions, String subject) {}
