package es.in2.issuanceportal.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import es.in2.issuanceportal.identification.archfixture.IllegalCaller;
import es.in2.issuanceportal.identification.archfixture.LegalCaller;
import es.in2.issuanceportal.journey.api.archfixture.PublishedPort;
import es.in2.issuanceportal.journey.application.archfixture.ApplicationUsingInfrastructure;
import es.in2.issuanceportal.journey.archfixture.JourneyInternal;
import es.in2.issuanceportal.journey.domain.archfixture.DomainUsingSharedError;
import es.in2.issuanceportal.journey.domain.archfixture.DomainUsingSpringWeb;
import es.in2.issuanceportal.journey.infrastructure.archfixture.SomeAdapter;
import es.in2.issuanceportal.session.archfixture.SessionKnowingJourney;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Module boundaries and the dependency rule of ports and adapters, enforced instead of documented. */
class ArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ArchitectureRules.BASE);

    @Test
    void layerRules_productionCode_haveNoViolations() {
        ArchitectureRules.DOMAIN_ONLY_DEPENDS_ON_JAVA_AND_DOMAIN.check(PRODUCTION);
        ArchitectureRules.APPLICATION_DOES_NOT_DEPEND_ON_INFRASTRUCTURE.check(PRODUCTION);
    }

    @Test
    void domainRule_domainUsesSharedErrorCodes_reportsViolation() {
        JavaClasses classes = new ClassFileImporter().importClasses(DomainUsingSharedError.class);

        assertThat(ArchitectureRules.DOMAIN_ONLY_DEPENDS_ON_JAVA_AND_DOMAIN.evaluate(classes).hasViolation()).isTrue();
    }

    @Test
    void domainRule_domainUsesSpringWeb_reportsViolation() {
        JavaClasses classes = new ClassFileImporter().importClasses(DomainUsingSpringWeb.class);

        assertThat(ArchitectureRules.DOMAIN_ONLY_DEPENDS_ON_JAVA_AND_DOMAIN.evaluate(classes).hasViolation()).isTrue();
    }

    @Test
    void applicationRule_applicationUsesInfrastructure_reportsViolation() {
        JavaClasses classes = new ClassFileImporter()
                .importClasses(ApplicationUsingInfrastructure.class, SomeAdapter.class);

        assertThat(ArchitectureRules.APPLICATION_DOES_NOT_DEPEND_ON_INFRASTRUCTURE.evaluate(classes).hasViolation())
                .isTrue();
    }

    @Test
    void moduleBoundaryRules_productionCode_haveNoViolations() {
        ArchitectureRules.moduleBoundaries().forEach(rule -> rule.check(PRODUCTION));
        ArchitectureRules.SESSION_DOES_NOT_DEPEND_ON_MODULES.check(PRODUCTION);
    }

    @Test
    void moduleBoundaryRule_callIntoAnotherModulesInternals_reportsViolation() {
        JavaClasses classes = new ClassFileImporter().importClasses(IllegalCaller.class, JourneyInternal.class);

        assertThat(ArchitectureRules.moduleOnlyUsesPublishedPackagesOfOthers("identification")
                .evaluate(classes).hasViolation()).isTrue();
    }

    @Test
    void moduleBoundaryRule_callIntoPublishedPackage_reportsNoViolation() {
        JavaClasses classes = new ClassFileImporter().importClasses(LegalCaller.class, PublishedPort.class);

        assertThat(ArchitectureRules.moduleOnlyUsesPublishedPackagesOfOthers("identification")
                .evaluate(classes).hasViolation()).isFalse();
    }

    @Test
    void sessionRule_sessionDependsOnModule_reportsViolation() {
        JavaClasses classes = new ClassFileImporter().importClasses(SessionKnowingJourney.class, PublishedPort.class);

        assertThat(ArchitectureRules.SESSION_DOES_NOT_DEPEND_ON_MODULES.evaluate(classes).hasViolation()).isTrue();
    }
}
