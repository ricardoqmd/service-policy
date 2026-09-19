package io.github.ricardoqmd.servicepolicy.controlplane;

import static io.restassured.RestAssured.given;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.github.ricardoqmd.servicepolicy.ActionCatalogueTestSupport;
import io.github.ricardoqmd.servicepolicy.ControlPlaneTestSupport;
import io.github.ricardoqmd.servicepolicy.persistence.ActionCatalogueRepository;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigDraft;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigProvider;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigRepository;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigStore;
import io.github.ricardoqmd.servicepolicy.persistence.AuditActor;
import io.github.ricardoqmd.servicepolicy.persistence.ConditionDocumentMapper;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyDocumentMapper;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersionRepository;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

/**
 * Shared arrangement for the control-plane suites (ADR-033): the applications a caller holds and does not
 * hold, the eighteen application-scoped control-plane endpoints, and a rendering of a response as it is on
 * the wire — status line, every header, body — so a test can compare it against a literal.
 */
@Singleton
public class ControlPlaneFixtures {

    /** Applications the default caller holds. */
    public static final String MINE = "app-mine";

    public static final String MINE_2 = "app-mine-2";

    /** An application that exists, seeded exactly like {@link #MINE}, which the default caller does not hold. */
    public static final String OTHER = "app-other";

    /** An application that does not exist anywhere. */
    public static final String NONCE = "zz-nonce-7f3a";

    /** The default caller's {@code apps} claim. */
    public static final String MY_APPS = "[\"app-mine\",\"app-mine-2\"]";

    public static final String POLICY_ID = "p-1";

    static final String POLICY_TEMPLATE = """
            {"policyId": "%s", "version": 1, "resourceType": "document", "actions": ["read"],
             "combiningAlgorithm": "DENY_OVERRIDES", "defaultEffect": "DENY",
             "rules": [{"id": "r", "effect": "PERMIT", "condition": {"type": "comparison", "op": "EQ",
               "left": {"ref": "subject.id"}, "right": {"value": "someone"}}}]}
            """;

    /** One control-plane endpoint with a route application, as a request that succeeds when authorized. */
    public record Endpoint(
            String name, String method, String path, String body, String ifMatch, int success, boolean needsFreshApp) {

        public Response send(String app) {
            RestAssured.urlEncodingEnabled = false;
            try {
                RequestSpecification request = given();
                if (body != null) {
                    request = request.contentType(ContentType.JSON).body(body);
                }
                if (ifMatch != null) {
                    request = request.header("If-Match", ifMatch);
                }
                return request.when().request(method, path.replace("{app}", app));
            } finally {
                RestAssured.urlEncodingEnabled = true;
            }
        }

        /**
         * Whether this endpoint performs a write — one that stores or removes a document, and therefore one
         * whose success closes installation mode (ADR-033 §6). The simulation is a {@code POST} that stores
         * nothing and is authorized as a read.
         */
        public boolean write() {
            return !"GET".equals(method) && !"policy.simulate".equals(name);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** Every application-scoped control-plane endpoint; the merged catalogue is tested on its own. */
    public static List<Endpoint> endpoints() {
        String policy = POLICY_TEMPLATE.formatted("p-new");
        return List.of(
                new Endpoint("policy.create", "POST", "/v1/apps/{app}/policies", policy, null, 201, false),
                new Endpoint(
                        "policy.append",
                        "PUT",
                        "/v1/apps/{app}/policies/p-1",
                        "{\"content\": " + POLICY_TEMPLATE.formatted(POLICY_ID) + "}",
                        "\"0\"",
                        200,
                        false),
                new Endpoint(
                        "policy.activate",
                        "POST",
                        "/v1/apps/{app}/policies/p-1/activate",
                        "{\"version\": 1}",
                        "\"0\"",
                        200,
                        false),
                new Endpoint(
                        "policy.deactivate",
                        "POST",
                        "/v1/apps/{app}/policies/p-1/deactivate",
                        "{}",
                        "\"0\"",
                        200,
                        false),
                new Endpoint("policy.list", "GET", "/v1/apps/{app}/policies", null, null, 200, false),
                new Endpoint("policy.get", "GET", "/v1/apps/{app}/policies/p-1", null, null, 200, false),
                new Endpoint("policy.versions", "GET", "/v1/apps/{app}/policies/p-1/versions", null, null, 200, false),
                new Endpoint("policy.version", "GET", "/v1/apps/{app}/policies/p-1/versions/1", null, null, 200, false),
                new Endpoint(
                        "policy.simulate",
                        "POST",
                        "/v1/apps/{app}/policies:simulate",
                        "{\"policy\": " + policy + ", \"request\": {\"action\": \"document:read\","
                                + " \"resource\": {\"type\": \"document\", \"id\": \"d1\"}}}",
                        null,
                        200,
                        false),
                new Endpoint(
                        "catalogue.create",
                        "POST",
                        "/v1/apps/{app}/action-catalogue",
                        "{\"resourceType\": \"invoice\", \"actions\": [\"read\"]}",
                        null,
                        201,
                        false),
                new Endpoint("catalogue.list", "GET", "/v1/apps/{app}/action-catalogue", null, null, 200, false),
                new Endpoint(
                        "catalogue.get", "GET", "/v1/apps/{app}/action-catalogue/document", null, null, 200, false),
                new Endpoint(
                        "catalogue.replace",
                        "PUT",
                        "/v1/apps/{app}/action-catalogue/document",
                        "{\"actions\": [\"read\", \"write\"]}",
                        "\"1\"",
                        200,
                        false),
                new Endpoint(
                        "catalogue.delete",
                        "DELETE",
                        "/v1/apps/{app}/action-catalogue/document",
                        null,
                        "\"1\"",
                        204,
                        false),
                new Endpoint(
                        "configuration.create",
                        "POST",
                        "/v1/apps/{app}/configuration",
                        "{\"subjectAttributes\": {\"unit\": \"unit\"}}",
                        null,
                        201,
                        true),
                new Endpoint("configuration.get", "GET", "/v1/apps/{app}/configuration", null, null, 200, false),
                new Endpoint(
                        "configuration.replace",
                        "PUT",
                        "/v1/apps/{app}/configuration",
                        "{\"subjectAttributes\": {\"unit\": \"other-unit\"}}",
                        "\"1\"",
                        200,
                        false),
                new Endpoint(
                        "configuration.delete", "DELETE", "/v1/apps/{app}/configuration", null, "\"1\"", 204, false));
    }

    /** The ten writes of {@link #endpoints()}, in the same order. */
    public static List<Endpoint> writeEndpoints() {
        return endpoints().stream().filter(Endpoint::write).toList();
    }

    /**
     * The complete response as a client receives it: the status line, every header sorted by name, and the
     * body. Two responses render equal only if a client could not tell them apart.
     */
    public static String wire(Response response) {
        String headers = response.getHeaders().asList().stream()
                .map(header -> header.getName().toLowerCase(Locale.ROOT) + ": " + header.getValue())
                .sorted()
                .collect(Collectors.joining("\n"));
        return response.getStatusLine() + "\n" + headers + "\n\n"
                + response.getBody().asString();
    }

    @Inject
    public ControlPlaneTestSupport controlPlane;

    @Inject
    PolicyHeadRepository headRepository;

    @Inject
    PolicyVersionRepository versionRepository;

    @Inject
    ActionCatalogueRepository catalogueRepository;

    @Inject
    AppConfigRepository configRepository;

    @Inject
    public AppConfigProvider configProvider;

    @Inject
    AppConfigStore configStore;

    @Inject
    PolicyLifecycleStore lifecycleStore;

    private final PolicyDocumentMapper policyMapper = new PolicyDocumentMapper(new ConditionDocumentMapper());

    /** Empties every collection a control-plane suite writes to, reserved application included. */
    public void wipe() {
        headRepository.deleteAll();
        versionRepository.deleteAll();
        catalogueRepository.deleteAll();
        configRepository.deleteAll();
        for (String app : List.of(MINE, MINE_2, OTHER, NONCE, ControlPlaneTestSupport.RESERVED_APP)) {
            configProvider.invalidate(app);
        }
    }

    /** Wiped, both {@link #MINE} and {@link #OTHER} seeded, and the control plane installed. */
    public void arrange() {
        wipe();
        seed(MINE);
        seed(OTHER);
        controlPlane.installed();
    }

    /** Declares the {@code document} vocabulary of {@code app}. */
    public void seedCatalogue(String app) {
        ActionCatalogueTestSupport.declare(catalogueRepository, app, "document", "read");
    }

    /** An application with a catalogue, a configuration at revision 1 and an inactive policy at revision 0. */
    public void seed(String app) {
        ActionCatalogueTestSupport.declare(catalogueRepository, app, "document", "read");
        configStore.create(app, new AppConfigDraft(Map.of("unit", "unit"), null), AuditActor.verified("seed"));
        lifecycleStore.create(
                app,
                policyMapper.fromDocument(Json.parse(POLICY_TEMPLATE.formatted(POLICY_ID))),
                AuditActor.verified("seed"),
                "seed");
    }
}
