package es.in2.issuanceportal.shared.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Programmatic transactions. Declaring them here replaces Spring Boot's single TransactionTemplate,
 * so both are defined: the usual one, and one that always runs in its own transaction.
 */
@Configuration
public class TransactionConfiguration {

    /** Name of the template whose work commits on its own, whatever happens to the caller's transaction. */
    public static final String INDEPENDENT = "independentTransactions";

    @Bean
    @Primary
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    @Qualifier(INDEPENDENT)
    TransactionTemplate independentTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
}
