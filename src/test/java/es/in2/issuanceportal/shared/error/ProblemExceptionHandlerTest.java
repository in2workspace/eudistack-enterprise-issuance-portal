package es.in2.issuanceportal.shared.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProblemExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new ProblemExceptionHandler())
            .build();

    @Test
    void handleProblem_problemException_returnsProblemWithCodeAsType() throws Exception {
        mvc.perform(get("/closed"))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("journey_closed"))
                .andExpect(jsonPath("$.title").value("Journey closed"))
                .andExpect(jsonPath("$.status").value(410));
    }

    @Test
    void handleDatabaseUnavailable_noConnection_returns503() throws Exception {
        mvc.perform(get("/database-down"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("service_unavailable"));
    }

    @Test
    void handleDatabaseUnavailable_transactionCannotOpen_returns503() throws Exception {
        mvc.perform(get("/transaction-cannot-open"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("service_unavailable"));
    }

    @Test
    void handleDatabaseUnavailable_rollbackCannotReachDatabase_returns503() throws Exception {
        mvc.perform(get("/rollback-failed"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("service_unavailable"));
    }

    @Test
    void handleUnexpected_anyException_returns500WithoutDetail() throws Exception {
        mvc.perform(get("/bug"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Internal error"))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @RestController
    static class FailingController {

        @GetMapping("/closed")
        void closed() {
            throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
        }

        @GetMapping("/database-down")
        void databaseDown() {
            throw new CannotGetJdbcConnectionException("connection refused");
        }

        @GetMapping("/transaction-cannot-open")
        void transactionCannotOpen() {
            throw new CannotCreateTransactionException("Could not open JDBC Connection for transaction");
        }

        @GetMapping("/rollback-failed")
        void rollbackFailed() {
            throw new TransactionSystemException("JDBC rollback failed");
        }

        @GetMapping("/bug")
        void bug() {
            throw new IllegalStateException("secret internal state");
        }
    }
}
