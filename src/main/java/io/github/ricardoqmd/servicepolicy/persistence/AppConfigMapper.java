package io.github.ricardoqmd.servicepolicy.persistence;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bson.Document;

/**
 * Maps between {@link AppConfigDocument} and the {@link AppConfig} read model (ADR-029).
 *
 * <p>Shared by {@link AppConfigStore} and {@link AppConfigProvider} so the cached path and the
 * administrative path cannot drift into reading the same document two different ways.
 *
 * <p>Null sections survive the round trip as null. "Not configured" and "configured empty" are
 * different states — the first is the app that never declared a claim mapping, the second would be
 * one that declared an empty one, which validation rejects — and collapsing them here would erase a
 * distinction the future readers of this document depend on.
 */
final class AppConfigMapper {

    private static final String URL = "url";
    private static final String TIMEOUT_MS = "timeoutMs";
    private static final String CACHE_TTL_SECONDS = "cacheTtlSeconds";
    private static final String CREDENTIAL_REF = "credentialRef";

    private AppConfigMapper() {
        // static helper
    }

    static AppConfig toAppConfig(AppConfigDocument document) {
        return new AppConfig(
                document.app, toAttributeMap(document.subjectAttributes), toPipConfig(document.pip), document.revision);
    }

    static Document toSubjectAttributesDocument(Map<String, String> subjectAttributes) {
        if (subjectAttributes == null) {
            return null;
        }
        Document document = new Document();
        subjectAttributes.forEach(document::append);
        return document;
    }

    static Document toPipDocument(AppConfigDraft.PipDraft pip) {
        if (pip == null) {
            return null;
        }
        return new Document(URL, pip.url())
                .append(TIMEOUT_MS, pip.timeoutMs())
                .append(CACHE_TTL_SECONDS, pip.cacheTtlSeconds())
                .append(CREDENTIAL_REF, pip.credentialRef());
    }

    /**
     * Maps the stored claim mapping, <strong>omitting</strong> any entry whose value is null.
     *
     * <p>The write path cannot produce such an entry — validation rejects a blank claim path, and a
     * JSON null never reaches storage — so this can only come from a document written out of band.
     * Dropping the entry rather than carrying a null through is the fail-safe reading: a mapping that
     * is not there is one fewer subject attribute the engine can derive, and under deny-overrides
     * with {@code defaultEffect: DENY} fewer resolved attributes can only produce equal or stricter
     * outcomes (ADR-011). Nothing widens. Propagating the null instead would fail the whole read —
     * turning one malformed key into a 500 for the entire configuration, which is a worse answer to
     * the same corrupt data.
     */
    private static Map<String, String> toAttributeMap(Document document) {
        if (document == null) {
            return null;
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        document.forEach((name, claimPath) -> {
            if (claimPath != null) {
                attributes.put(name, claimPath.toString());
            }
        });
        return attributes;
    }

    private static PipConfig toPipConfig(Document document) {
        if (document == null) {
            return null;
        }
        return new PipConfig(
                document.getString(URL),
                document.getInteger(TIMEOUT_MS),
                document.getInteger(CACHE_TTL_SECONDS),
                document.getString(CREDENTIAL_REF));
    }
}
