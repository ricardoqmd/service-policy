package io.github.ricardoqmd.servicepolicy.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

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
 *       ({@code domain}, {@code persistence}, {@code rest}, {@code evaluation}, {@code problem},
 *       {@code config}, {@code health}).
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
}
