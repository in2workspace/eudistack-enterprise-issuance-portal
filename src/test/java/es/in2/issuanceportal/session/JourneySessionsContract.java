package es.in2.issuanceportal.session;

import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What every implementation of the session port must do, run against each of them. */
abstract class JourneySessionsContract {

    static final TenantId SANDBOX = new TenantId("sandbox");
    static final TenantId KPMG = new TenantId("kpmg");

    abstract JourneySessions sessions();

    /** A journey the session can belong to; the JDBC adapter needs its row to exist. */
    abstract UUID newJourney(TenantId tenant);

    @Test
    void open_newSession_belongsToItsJourney() {
        SessionId session = SessionId.generate();
        UUID journey = newJourney(SANDBOX);

        sessions().open(SANDBOX, session, journey);

        assertThat(sessions().journeyOf(SANDBOX, session)).contains(journey);
    }

    @Test
    void read_writtenSections_returnsContent() {
        SessionId session = openSession(SANDBOX);

        sessions().write(SANDBOX, session, Section.IDENTITY, "{\"givenName\":\"Ana\"}");
        sessions().write(SANDBOX, session, Section.EMAIL, "\"ana@example.org\"");

        assertThat(sessions().read(SANDBOX, session, Section.IDENTITY)).contains("{\"givenName\":\"Ana\"}");
        assertThat(sessions().read(SANDBOX, session, Section.EMAIL)).contains("\"ana@example.org\"");
        assertThat(sessions().read(SANDBOX, session, Section.ENTITLEMENT)).isEmpty();
    }

    @Test
    void write_sameSectionTwice_replacesContent() {
        SessionId session = openSession(SANDBOX);

        sessions().write(SANDBOX, session, Section.ISSUANCE, "{\"v\":1}");
        sessions().write(SANDBOX, session, Section.ISSUANCE, "{\"v\":2}");

        assertThat(sessions().read(SANDBOX, session, Section.ISSUANCE)).contains("{\"v\":2}");
    }

    @Test
    void close_openSession_deletesItIdempotently() {
        SessionId session = SessionId.generate();
        UUID journey = newJourney(SANDBOX);
        sessions().open(SANDBOX, session, journey);
        sessions().write(SANDBOX, session, Section.IDENTITY, "{}");

        sessions().close(SANDBOX, journey);
        sessions().close(SANDBOX, journey);

        assertThat(sessions().journeyOf(SANDBOX, session)).isEmpty();
        assertJourneyClosed(() -> sessions().read(SANDBOX, session, Section.IDENTITY));
    }

    @Test
    void operations_unknownSession_throwJourneyClosed() {
        SessionId unknown = SessionId.generate();

        assertThat(sessions().journeyOf(SANDBOX, unknown)).isEmpty();
        assertJourneyClosed(() -> sessions().read(SANDBOX, unknown, Section.IDENTITY));
        assertJourneyClosed(() -> sessions().write(SANDBOX, unknown, Section.IDENTITY, "{}"));
        assertJourneyClosed(() -> sessions().rotate(SANDBOX, unknown));
    }

    @Test
    void rotate_openSession_keepsContentAndRetiresOldId() {
        SessionId session = openSession(SANDBOX);
        sessions().write(SANDBOX, session, Section.IDENTITY, "{\"givenName\":\"Ana\"}");

        SessionId rotated = sessions().rotate(SANDBOX, session);

        assertThat(rotated).isNotEqualTo(session);
        assertThat(sessions().read(SANDBOX, rotated, Section.IDENTITY)).contains("{\"givenName\":\"Ana\"}");
        assertThat(sessions().journeyOf(SANDBOX, session)).isEmpty();
    }

    @Test
    void journeyOf_sessionOfAnotherTenant_returnsEmpty() {
        SessionId session = openSession(KPMG);

        assertThat(sessions().journeyOf(SANDBOX, session)).isEmpty();
    }

    private SessionId openSession(TenantId tenant) {
        SessionId session = SessionId.generate();
        sessions().open(tenant, session, newJourney(tenant));
        return session;
    }

    private static void assertJourneyClosed(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ProblemException.class)
                .extracting(e -> ((ProblemException) e).problem())
                .isEqualTo(ProblemCode.JOURNEY_CLOSED);
    }
}
