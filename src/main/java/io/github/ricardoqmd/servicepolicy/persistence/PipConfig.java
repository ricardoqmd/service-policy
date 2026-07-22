package io.github.ricardoqmd.servicepolicy.persistence;

/**
 * Attribute-source (PIP) settings of an application (ADR-029): where to fetch the subject attributes
 * a token does not carry.
 *
 * <p>Read model, so every field is present and within bounds — {@link AppConfigValidator} rejected
 * the document otherwise before it could be stored.
 *
 * @param url             absolute http/https URL of the source, containing the literal
 *                        {@code &#123;sub&#125;} placeholder that the future adapter replaces with the
 *                        subject id.
 * @param timeoutMs       request timeout in milliseconds.
 * @param cacheTtlSeconds how long a fetched attribute set may be reused.
 * @param credentialRef   the <em>name</em> of a credential, not the credential. It is resolved
 *                        against the deployment's secret mechanism by the future PIP adapter; this
 *                        codebase stores it, echoes it back, and does nothing else with it (ADR-029
 *                        §secrets are referenced, never stored).
 */
public record PipConfig(String url, int timeoutMs, int cacheTtlSeconds, String credentialRef) {}
