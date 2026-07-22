package io.github.ricardoqmd.servicepolicy.rest;

/**
 * The {@code pip} section of a configuration write (ADR-029). All four fields are required when the
 * section is present — a partially configured source cannot be called at all.
 *
 * <p>Boxed integers so that "absent" is representable and can be reported as a validation error
 * naming the field, rather than defaulting silently to zero.
 *
 * @param url             absolute http/https URL, containing the literal {@code &#123;sub&#125;}
 *                        placeholder for the subject id. It is never contacted at write time
 *                        (ADR-029): validation is syntax and bounds only.
 * @param timeoutMs       request timeout, 1..10000.
 * @param cacheTtlSeconds how long a fetched attribute set may be reused, 0..86400.
 * @param credentialRef   the NAME of a credential, never the credential. The secret stays in the
 *                        deployment's secret mechanism and is resolved by the future PIP adapter; a
 *                        configuration document an administrator can read must never become a place
 *                        where credentials live.
 */
public record PipConfigWrite(String url, Integer timeoutMs, Integer cacheTtlSeconds, String credentialRef) {}
