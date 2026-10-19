package es.in2.issuanceportal.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import es.in2.issuanceportal.identification.archfixture.IllegalCaller;
import es.in2.issuanceportal.identification.archfixture.LegalCaller;
import es.in2.issuanceportal.journey.api.archfixture.PublishedPort;
import es.in2.issuanceportal.journey.archfixture.JourneyInternal;
import es.in2.issuanceportal.session.archfixture.SessionKnowingJourney;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Module boundaries and the dependency rule of ports and adapters, enforced instead of documented. */
class ArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ArchitectureRules.BASE);

    @Test
    void domainDoesNotDependOnInfrastructureOrSpringWeb() {
        ArchitectureRules.DOMAIN_DOES_NOT_DEPEND_ON_INFRASTRUCTURE.check(PRODUCTION);
        ArchitectureRules.DOMAIN_DOES_NOT_DEPEND_ON_SPRING_WEB.check(PRODUCTION);
    }

    @Test
    void modulesOnlyTalkThroughPublishedPackages() {
        ArchitectureRules.moduleBoundaries().forEach(rule -> rule.check(PRODUCTION));
        ArchitectureRules.SESSION_DOES_NOT_DEPEND_ON_MODULES.check(PRODUCTION);
    }

    @Test
    void moduleRuleDetectsACallIntoAnotherModulesInternals() {
        JavaClasses classes = new ClassFileImporter().importClasses(IllegalCaller.class, JourneyInternal.class);

        assertThat(ArchitectureRules.moduleOnlyUsesPublishedPackagesOfOthers("identification")
                .evaluate(classes).hasViolation()).isTrue();
    }

    @Test
    void moduleRuleAllowsACallIntoAnotherModulesPublishedPackage() {
        JavaClasses classes = new ClassFileImporter().importClasses(LegalCaller.class, PublishedPort.class);

        assertThat(ArchitectureRules.moduleOnlyUsesPublishedPackagesOfOthers("identification")
                .evaluate(classes).hasViolation()).isFalse();
    }

    @Test
    void sessionRuleDetectsADependencyOnAModule() {
        JavaClasses classes = new ClassFileImporter().importClasses(SessionKnowingJourney.class, PublishedPort.class);

        assertThat(ArchitectureRules.SESSION_DOES_NOT_DEPEND_ON_MODULES.evaluate(classes).hasViolation()).isTrue();
    }
}
