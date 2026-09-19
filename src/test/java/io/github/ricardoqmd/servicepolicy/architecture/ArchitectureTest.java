package io.github.ricardoqmd.servicepolicy.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import java.util.Set;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HttpMethod;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import io.github.ricardoqmd.servicepolicy.rest.AuthContext;
import io.github.ricardoqmd.servicepolicy.rest.ControlPlaneGate;

/**
 * Structural invariants for the service-policy hexagonal architecture (ADR-009/016/018/019/021).
 * Only production classes are analyzed ({@link ImportOption.DoNotIncludeTests}); test scaffolding
 * is legitimately allowed to cross layer boundaries for setup and teardown.
 *
 * <ul>
 *   <li>R1 domainIsPure — {@code ..domain..} has zero framework and zero cross-layer imports;
 *       it depends only on {@code java.*} and itself.
 *   <li>R2 restNoDirectRepositoryAccess — {@code ..rest..} must reach persistence through
 *       {@code PolicyLifecycleStore}, never by injecting Panache repositories directly.
 *   <li>R3 persistenceDoesNotDependOnRest — closes the hexagonal inversion: the store layer
 *       must not import the web layer (exceptions are in the neutral {@code problem} package).
 *   <li>R4 evaluationDoesNotDependOnRest — the evaluator is decoupled from the web layer;
 *       it reads active policies through {@code PolicyLifecycleStore} (ADR-021).
 *   <li>R5 noPackageCycles — no circular dependencies across any first-level sub-package
 *       ({@code domain}, {@code persistence}, {@code rest}, {@code evaluation}, {@code enumeration},
 *       {@code problem}, {@code config}, {@code health}).
 *   <li>R6 enforcementDoesNotDependOnEnumeration — the ADR-030 structural safeguard: neither {@code ..evaluation..}
 *       nor {@code ..domain..} may reach the three-valued {@code ..enumeration..} package, so the
 *       {@code INDETERMINATE} value can never leak into an enforcement decision, which must stay
 *       two-valued and fail-safe (ADR-011).
 *   <li>R7 enumerationDoesNotDependOnRestOrEvaluation — the enumeration package sits below the web
 *       and enforcement layers: it reads {@code domain} and {@code persistence} only, so its isolated
 *       evaluation mode has no path back into either.
 *   <li>R8 everyControlPlaneEndpointCallsTheGate — every endpoint, in any class, that is not in a data-plane or
 *       metadata resource asks the {@code ControlPlaneGate} (ADR-033). An endpoint is a method carrying an
 *       annotation meta-annotated {@code @HttpMethod}; its class need not carry {@code @Path}, so the methods
 *       of a sub-resource are selected however they are reached.
 *   <li>R9 onlyTheDelegationRuleChecksAMarker — {@code AuthContext.has} is called only by the delegation
 *       rule inside {@code AuthContext}: no endpoint gates on a marker again (ADR-033 §5).
 *   <li>R10 everyControlPlaneWriteClosesInstallationMode — every endpoint selected as in R8 that is not a
 *       {@code @GET} calls {@code ControlPlaneGate.writeSucceeded} (ADR-033 §6), unless it is named in an
 *       explicit allow-list of reads. It proves the call is present in each such method, not that the method
 *       writes or that the call closes; the behavioural cases prove that.
 * </ul>
 *
 * <p>NOTE: the {@code activeContent} verbatim invariant (ADR-020 §4) is intentionally NOT
 * covered here. ArchUnit cannot inspect which string key is passed to MongoDB's
 * {@code Updates.set("activeContent", ...)}; that behavioral invariant is enforced by
 * {@code PolicyLifecycleStoreTest#activateSetsActiveVersionAndActiveContentVerbatim}.
 */
@AnalyzeClasses(packages = "io.github.ricardoqmd.servicepolicy", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    /** R1 — domain is framework-free and layer-isolated. */
    @ArchTest
    static final ArchRule domainIsPure = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "io.quarkus..",
                    "jakarta..",
                    "org.bson..",
                    "com.mongodb..",
                    "io.smallrye..",
                    "org.eclipse.microprofile..",
                    "..persistence..",
                    "..rest..",
                    "..evaluation..",
                    "..problem..",
                    "..config..",
                    "..health..");

    /** R2 — REST layer accesses persistence only via the store facade, never via repositories. */
    @ArchTest
    static final ArchRule restNoDirectRepositoryAccess = noClasses()
            .that()
            .resideInAPackage("..rest..")
            .should()
            .dependOnClassesThat()
            .areAssignableTo(io.quarkus.mongodb.panache.PanacheMongoRepository.class);

    /** R3 — persistence must not import the web layer (hexagonal inversion, closed by ADR-018). */
    @ArchTest
    static final ArchRule persistenceDoesNotDependOnRest = noClasses()
            .that()
            .resideInAPackage("..persistence..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..rest..");

    /** R4 — evaluation is decoupled from the web layer; reads policies via the lifecycle store. */
    @ArchTest
    static final ArchRule evaluationDoesNotDependOnRest = noClasses()
            .that()
            .resideInAPackage("..evaluation..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..rest..");

    /** R5 — no circular package dependencies across any first-level slice. */
    @ArchTest
    static final ArchRule noPackageCycles = slices().matching("io.github.ricardoqmd.servicepolicy.(*)..")
            .should()
            .beFreeOfCycles();

    /**
     * R6 — the ADR-030 structural isolation: enforcement (and the pure domain) must not reach the
     * three-valued enumeration package. This is the build-breaking guarantee that {@code INDETERMINATE}
     * cannot escape into a {@code Decision}; without it the isolation would be a convention.
     */
    @ArchTest
    static final ArchRule enforcementDoesNotDependOnEnumeration = noClasses()
            .that()
            .resideInAnyPackage("..evaluation..", "..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..enumeration..");

    /**
     * R7 — the enumeration package depends only downward (domain, persistence, MicroProfile JWT). It
     * must never import the web layer or the enforcement evaluator, so its second evaluation semantics
     * has no route into either.
     */
    @ArchTest
    static final ArchRule enumerationDoesNotDependOnRestOrEvaluation = noClasses()
            .that()
            .resideInAPackage("..enumeration..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..rest..", "..evaluation..");

    /** The resources that are not the control plane: evaluation, enumeration and public metadata. */
    private static final Set<String> NOT_CONTROL_PLANE =
            Set.of("EvaluateResource", "PermissionsResource", "PushedEnumerationResource", "InfoResource");

    /**
     * The endpoints R8 and R10 are about: every method carrying an annotation that is itself annotated
     * {@code @HttpMethod} — {@code @GET}, {@code @POST}, {@code @PUT}, {@code @DELETE}, {@code @PATCH},
     * {@code @HEAD}, {@code @OPTIONS} or one declared in this code base — in any production class, except
     * those of the resources named in {@link #NOT_CONTROL_PLANE}. The class is not required to carry
     * {@code @Path}: a class reached through a sub-resource locator carries none, and its methods are
     * selected all the same. A class is excluded only by its own simple name, so a sub-resource reached from a
     * data-plane resource is held to the control-plane rules rather than exempted with it.
     */
    private static final DescribedPredicate<JavaMethod> CONTROL_PLANE_ENDPOINT = DescribedPredicate.describe(
            "are endpoints (meta-annotated @HttpMethod) outside the resources that are not control plane",
            (JavaMethod method) -> method.isMetaAnnotatedWith(HttpMethod.class)
                    && !NOT_CONTROL_PLANE.contains(method.getOwner().getSimpleName()));

    /**
     * R8 — every control-plane endpoint is decided by the gate (ADR-033). An endpoint added later is covered
     * whatever its HTTP method and whether or not its class carries {@code @Path}, unless its resource is
     * deliberately listed as not control plane.
     */
    @ArchTest
    static final ArchRule everyControlPlaneEndpointCallsTheGate = methods()
            .that(CONTROL_PLANE_ENDPOINT)
            .should(new ArchCondition<JavaMethod>("call ControlPlaneGate.authorize or ControlPlaneGate.readableApps") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    boolean gated = method.getMethodCallsFromSelf().stream()
                            .anyMatch(call -> call.getTargetOwner().isEquivalentTo(ControlPlaneGate.class)
                                    && Set.of("authorize", "readableApps").contains(call.getName()));
                    events.add(new SimpleConditionEvent(
                            method, gated, method.getFullName() + (gated ? " calls" : " does not call") + " the gate"));
                }
            });

    /**
     * The control-plane endpoints that are not {@code @GET} and write nothing, as {@code Resource.method}. Each
     * is a declared exception to R10; today there is one, the simulation.
     */
    private static final Set<String> CONTROL_PLANE_READS_BY_WRITE_METHOD = Set.of("SimulationResource.simulate");

    /**
     * R10 — the control-plane endpoints that are not reads call {@code writeSucceeded}, which closes
     * installation mode once their write succeeds (ADR-033 §6). Without it, removing one call leaves that
     * endpoint able to serve the bootstrap subject forever while the store stays uninstalled.
     *
     * <p>What it selects: the endpoints of {@link #CONTROL_PLANE_ENDPOINT} that are not annotated {@code @GET}
     * and are not named in {@link #CONTROL_PLANE_READS_BY_WRITE_METHOD}. Each must call {@code writeSucceeded}
     * itself. How the endpoint obtains its action does not matter, nor does its HTTP method, nor whether it
     * sits on a class reached as a sub-resource; a {@code @HEAD} or {@code @OPTIONS} counts as a write until it
     * is declared a read. What it proves is only that the call is present in the method; the behavioural
     * cases of {@code InstallationModeTest} prove that it closes.
     */
    @ArchTest
    static final ArchRule everyControlPlaneWriteClosesInstallationMode = methods()
            .that(CONTROL_PLANE_ENDPOINT)
            .and(DescribedPredicate.describe(
                    "are not @GET and not a declared read",
                    (JavaMethod method) -> !method.isAnnotatedWith(GET.class)
                            && !CONTROL_PLANE_READS_BY_WRITE_METHOD.contains(
                                    method.getOwner().getSimpleName() + "." + method.getName())))
            .should(new ArchCondition<JavaMethod>("call ControlPlaneGate.writeSucceeded") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    boolean closes = method.getMethodCallsFromSelf().stream()
                            .anyMatch(call -> call.getTargetOwner().isEquivalentTo(ControlPlaneGate.class)
                                    && "writeSucceeded".equals(call.getName()));
                    events.add(new SimpleConditionEvent(
                            method,
                            closes,
                            method.getFullName() + (closes ? " calls" : " does not call") + " writeSucceeded"));
                }
            });

    /** R9 — no endpoint gates on a marker; the delegation rule is the only caller of {@code has}. */
    @ArchTest
    static final ArchRule onlyTheDelegationRuleChecksAMarker = noClasses()
            .that()
            .doNotHaveSimpleName("AuthContext")
            .should()
            .callMethod(
                    AuthContext.class,
                    "has",
                    io.github.ricardoqmd.servicepolicy.config.ServicePolicyConfig.Marker.class);
}
