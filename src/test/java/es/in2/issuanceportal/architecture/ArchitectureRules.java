package es.in2.issuanceportal.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;

import java.util.Arrays;
import java.util.List;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The rules, kept apart from the test that applies them so they can also be checked against
 * fixtures that break them on purpose.
 */
final class ArchitectureRules {

    static final String BASE = "es.in2.issuanceportal";
    static final List<String> MODULES = List.of("journey", "identification", "authenticsource");
    /** What a module may use from another: its published package, nothing else. */
    static final String PUBLISHED = "api";

    private ArchitectureRules() {
    }

    static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_INFRASTRUCTURE = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .allowEmptyShould(true);

    static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_SPRING_WEB = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.web..")
            .allowEmptyShould(true);

    /** The session is the contract between modules (AD-3), so it cannot know any of them. */
    static final ArchRule SESSION_DOES_NOT_DEPEND_ON_MODULES = noClasses()
            .that().resideInAPackage(BASE + ".session..")
            .should().dependOnClassesThat().resideInAnyPackage(modulePackages(MODULES))
            .allowEmptyShould(true);

    static ArchRule moduleOnlyUsesPublishedPackagesOfOthers(String module) {
        List<String> others = new java.util.ArrayList<>(MODULES);
        others.remove(module);
        others.add("session");
        DescribedPredicate<JavaClass> internalsOfOthers = resideInAnyPackage(modulePackages(others))
                .and(DescribedPredicate.not(resideInAnyPackage(publishedPackages(others))))
                .as("internals of " + others);
        return noClasses()
                .that().resideInAPackage(BASE + "." + module + "..")
                .should().dependOnClassesThat(internalsOfOthers)
                .allowEmptyShould(true);
    }

    private static String[] modulePackages(List<String> modules) {
        return modules.stream().map(m -> BASE + "." + m + "..").toArray(String[]::new);
    }

    private static String[] publishedPackages(List<String> modules) {
        return modules.stream().map(m -> BASE + "." + m + "." + PUBLISHED + "..").toArray(String[]::new);
    }

    static List<ArchRule> moduleBoundaries() {
        return Arrays.asList(MODULES.stream()
                .map(ArchitectureRules::moduleOnlyUsesPublishedPackagesOfOthers)
                .toArray(ArchRule[]::new));
    }
}
