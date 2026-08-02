package cn.timebank.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * B1.6 / B1.8's exit criterion, made mechanical rather than a review
 * checklist: "every endpoint carries a permission annotation; an endpoint
 * without one fails a test that scans for it." {@code isAuthenticated()}
 * counts — it is the correct annotation for endpoints the contract marks
 * merely "Authenticated" (e.g. {@code GET /me}) rather than gated by a
 * specific permission code.
 *
 * Uses {@code classes().should(rawCondition)} rather than {@code
 * noClasses()} — see {@code ModuleBoundaryTest}'s note on why the two
 * differ for a hand-written {@link ArchCondition}.
 */
@AnalyzeClasses(packages = "cn.timebank", importOptions = ImportOption.DoNotIncludeTests.class)
class PermissionAnnotationTest {

    @ArchTest
    static final ArchRule every_mapped_controller_method_declares_preauthorize =
            classes().that().areAnnotatedWith(RestController.class)
                    .should(new ArchCondition<JavaClass>("have @PreAuthorize on every request-mapped method") {
                        @Override
                        public void check(JavaClass javaClass, ConditionEvents events) {
                            for (JavaMethod method : javaClass.getMethods()) {
                                boolean isEndpoint = method.isAnnotatedWith(GetMapping.class)
                                        || method.isAnnotatedWith(PostMapping.class)
                                        || method.isAnnotatedWith(PatchMapping.class)
                                        || method.isAnnotatedWith(PutMapping.class)
                                        || method.isAnnotatedWith(DeleteMapping.class)
                                        || method.isAnnotatedWith(RequestMapping.class);
                                if (isEndpoint && !method.isAnnotatedWith(PreAuthorize.class)) {
                                    events.add(SimpleConditionEvent.violated(method,
                                            method.getFullName() + " is a mapped endpoint with no @PreAuthorize"));
                                }
                            }
                        }
                    });
}
