package es.in2.issuanceportal.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The rules, kept apart from the test that applies them so they can also be checked against
 * fixtures that break them on purpose.
 */
final class ArchitectureRules {

    static final String BASE = "es.in2.issuanceportal";
    static final List<String> MODULES = List.of("journey", "identification", "authenticsource");
    /** Modules that still have no classes: their rules may match nothing until their stories land. */
    static final Set<String> EMPTY_MODULES = Set.of("identification", "authenticsource");
    /** What a module may use from another: its published package, nothing else. */
    static final String PUBLISHED = "api";

    private ArchitectureRules() {
    }

    /**
     * The domain depends on the JDK and on itself only: no infrastructure, no Spring, and none of the
     * shared web or tenant types either.
     */
    static final ArchRule DOMAIN_ONLY_DEPENDS_ON_JAVA_AND_DOMAIN = classes()
            .that().resideInAPackage("..domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "..domain..");

    static final ArchRule APPLICATION_DOES_NOT_DEPEND_ON_INFRASTRUCTURE = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    /** The session is the contract between modules (AD-3), so it cannot know any of them. */
    static final ArchRule SESSION_DOES_NOT_DEPEND_ON_MODULES = noClasses()
            .that().resideInAPackage(BASE + ".session..")
            .should().dependOnClassesThat().resideInAnyPackage(modulePackages(MODULES));

    static ArchRule moduleOnlyUsesPublishedPackagesOfOthers(String module) {
        List<String> others = new ArrayList<>(MODULES);
        others.remove(module);
        others.add("session");
        DescribedPredicate<JavaClass> internalsOfOthers = resideInAnyPackage(modulePackages(others))
                .and(DescribedPredicate.not(resideInAnyPackage(publishedPackages(others))))
                .as("internals of " + others);
        return noClasses()
                .that().resideInAPackage(BASE + "." + module + "..")
                .should().dependOnClassesThat(internalsOfOthers)
                .allowEmptyShould(EMPTY_MODULES.contains(module));
    }

    private static String[] modulePackages(List<String> modules) {
        return modules.stream().map(m -> BASE + "." + m + "..").toArray(String[]::new);
    }

    private static String[] publishedPackages(List<String> modules) {
        return modules.stream().map(m -> BASE + "." + m + "." + PUBLISHED + "..").toArray(String[]::new);
    }

    static List<ArchRule> moduleBoundaries() {
        return MODULES.stream().map(ArchitectureRules::moduleOnlyUsesPublishedPackagesOfOthers).toList();
    }
}
