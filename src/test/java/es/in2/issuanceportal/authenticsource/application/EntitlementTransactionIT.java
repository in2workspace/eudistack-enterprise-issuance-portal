package es.in2.issuanceportal.authenticsource.application;

import com.zaxxer.hikari.HikariDataSource;
import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.authenticsource.RecordingAuthenticSourceAuditTrail;
import es.in2.issuanceportal.authenticsource.StubAdapter;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.LookupResult;
import es.in2.issuanceportal.authenticsource.domain.PersonalIdentifier;
import es.in2.issuanceportal.authenticsource.domain.SourceRecord;
import es.in2.issuanceportal.authenticsource.domain.config.AuthenticSourceConfig;
import es.in2.issuanceportal.authenticsource.domain.config.SourceSettings;
import es.in2.issuanceportal.journey.api.JourneyProgress;
import es.in2.issuanceportal.journey.application.JourneyService;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The transaction guarantees of the check, with the real transaction manager and PostgreSQL. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EntitlementTransactionIT {

    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final String DOCTORID = "doctorid.sd.1";
    private static final PersonalIdentifier ACTIVE = new PersonalIdentifier(IdentifierType.DNI, "00000001R");

    @Autowired
    private JourneyService journey;
    @Autowired
    private JourneySessions sessions;
    @Autowired
    private TransactionOperations transactions;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private Clock clock;

    @Autowired
    private DataSource dataSource;

    /**
     * Connections taken from the pool while the source is asked. The transaction state of Spring belongs to
     * the request's thread and the search runs on another, so the pool is what shows a transaction held.
     */
    private final AtomicInteger connectionsDuringLookup = new AtomicInteger(-1);
    private final StubAdapter adapter = StubAdapter.searchingByBoth("stub").answering(searched -> {
        connectionsDuringLookup.set(activeConnections());
        return LookupResult.found(new SourceRecord(List.of(ACTIVE), true, Map.of("registration_number", "280000001")));
    });

    private int activeConnections() {
        try {
            return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
    private EntitlementService service;

    @AfterEach
    void closeService() {
        if (service != null) {
            service.close();
        }
    }

    private EntitlementService serviceWith(JourneyProgress progress) {
        AuthenticSourceConfig config = new AuthenticSourceConfig(DOCTORID, Set.of(), Map.of(DOCTORID,
                new SourceSettings("DoctorID", "stub", null, null, null, Set.of(IdentifierType.DNI), List.of(), null,
                        null)));
        service = new EntitlementService(journey, progress, journey, sessions,
                new AuthenticSourceConfigs(tenant -> Optional.of(config), new AdapterRegistry(List.of(adapter))),
                new RecordingAuthenticSourceAuditTrail(), transactions, clock, new LookupSlots(100, 100));
        return service;
    }

    private SessionId journeyWithIdentity() {
        SessionId session = journey.start(SANDBOX, Optional.empty()).session();
        sessions.write(SANDBOX, session, Section.IDENTITY, new HolderIdentity("Lucía", "Prueba",
                new es.in2.issuanceportal.session.api.PersonalIdentifier(
                        es.in2.issuanceportal.session.api.IdentifierType.DNI, "00000001R"), null).toJson());
        return session;
    }

    @Test
    void check_sourceIsAskedWithoutAConnectionHeld() {
        SessionId session = journeyWithIdentity();

        serviceWith(journey).check(SANDBOX, session);

        assertThat(adapter.searches()).hasSize(1);
        assertThat(connectionsDuringLookup).hasValue(0);
    }

    /** The measure above can fail: a check run inside a transaction holds its connection during the search. */
    @Test
    void check_insideATransaction_holdsAConnectionDuringTheSearch() {
        SessionId session = journeyWithIdentity();
        EntitlementService checks = serviceWith(journey);

        transactions.executeWithoutResult(status -> checks.check(SANDBOX, session));

        assertThat(adapter.searches()).hasSize(1);
        assertThat(connectionsDuringLookup.get()).isPositive();
    }

    @Test
    @DisplayName("ES-06 — check_reachingTheStepFails_rollsBackTheEntitlement")
    void check_reachingTheStepFails_rollsBackTheEntitlement() {
        SessionId session = journeyWithIdentity();
        UUID journeyId = sessions.journeyOf(SANDBOX, session).orElseThrow();
        JourneyProgress failing = (tenant, s, step) -> {
            throw new IllegalStateException("database down");
        };

        assertThatThrownBy(() -> serviceWith(failing).check(SANDBOX, session))
                .isInstanceOf(IllegalStateException.class);

        assertThat(sessions.read(SANDBOX, session, Section.ENTITLEMENT)).isEmpty();
        assertThat(jdbc.sql("SELECT step FROM \"sandbox_issuance_portal\".journey WHERE id = :id")
                .param("id", journeyId).query(String.class).single()).isEqualTo("started");
    }

    @Test
    @DisplayName("AC-03 — check_confirmed_commitsTheEntitlementAndTheStepTogether")
    void check_confirmed_commitsTheEntitlementAndTheStepTogether() {
        SessionId session = journeyWithIdentity();
        UUID journeyId = sessions.journeyOf(SANDBOX, session).orElseThrow();

        serviceWith(journey).check(SANDBOX, session);

        assertThat(sessions.read(SANDBOX, session, Section.ENTITLEMENT)).isPresent();
        assertThat(jdbc.sql("SELECT step FROM \"sandbox_issuance_portal\".journey WHERE id = :id")
                .param("id", journeyId).query(String.class).single()).isEqualTo("entitlement_confirmed");
    }
}
