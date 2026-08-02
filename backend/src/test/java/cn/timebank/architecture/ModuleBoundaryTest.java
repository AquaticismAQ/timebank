package cn.timebank.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Design.md §6: "cross-module reads go through the owning module's service
 * interface or a read-model query, not by reaching into another module's
 * tables." §9's anti-pattern to avoid explicitly: a shared entity leaking
 * into a controller. These two rules are the automated version of both
 * sentences (B0.2) — the modular monolith's only real failure mode is
 * silent erosion into a big ball of mud, and that erosion is invisible in
 * code review until it is enforced here.
 *
 * Verified by deliberately introducing each violation locally and
 * confirming the corresponding rule fails, per B0's exit criteria — not
 * committed, since the point is that these rules make the violation
 * impossible to land in the first place.
 */
@AnalyzeClasses(packages = "cn.timebank", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    private static final String ROOT = "cn.timebank.";

    @ArchTest
    static final ArchRule modules_must_not_reach_into_another_modules_internal_layers =
            // classes().should(rawCondition), not noClasses() — with a hand-written
            // ArchCondition (as opposed to ArchUnit's fluent .should().xyz() builders,
            // which negate correctly on their own), noClasses().should() inverts this
            // condition's satisfied/violated verdicts rather than reusing them directly,
            // which silently no-ops a rule written the "obvious" way. Verified by
            // deliberately introducing a violation and confirming noClasses() missed it
            // while classes() catches it (see B0's exit criteria).
            classes().should(new ArchCondition<>("not depend on another module's domain or infrastructure package") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    String originModule = moduleOf(javaClass.getPackageName());
                    javaClass.getDirectDependenciesFromSelf().forEach(dependency -> {
                        JavaClass target = dependency.getTargetClass();
                        String targetPackage = target.getPackageName();
                        String targetModule = moduleOf(targetPackage);
                        boolean targetIsInternal = targetPackage.contains(".domain")
                                || targetPackage.contains(".infrastructure");
                        boolean crossesModuleBoundary = originModule != null && targetModule != null
                                && !originModule.equals(targetModule);
                        if (targetIsInternal && crossesModuleBoundary) {
                            events.add(SimpleConditionEvent.violated(javaClass,
                                    javaClass.getFullName() + " reaches into " + target.getFullName()
                                            + ", which belongs to module '" + targetModule
                                            + "'s internal layer — go through its application package instead"));
                        }
                    });
                }
            });

    @ArchTest
    static final ArchRule controllers_must_not_depend_on_jpa_entities =
            noClasses().that().resideInAPackage("..web..")
                    .or().areAnnotatedWith(RestController.class)
                    .should().dependOnClassesThat().areAnnotatedWith(Entity.class)
                    .as("controllers must return DTOs, never JPA entities (Design.md §6)");

    private static String moduleOf(String packageName) {
        if (!packageName.startsWith(ROOT)) {
            return null;
        }
        String rest = packageName.substring(ROOT.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }
}
