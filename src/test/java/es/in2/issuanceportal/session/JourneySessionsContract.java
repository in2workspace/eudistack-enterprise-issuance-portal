package es.in2.issuanceportal.session;

import es.in2.issuanceportal.session.api.HolderAlreadyHasJourneyException;
import es.in2.issuanceportal.session.api.HolderRef;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.Section;
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
    void open_newSession_isReachedByItsHolder() {
        HolderRef holder = Holders.next();
        UUID journey = newJourney(SANDBOX);

        sessions().open(SANDBOX, holder, journey);

        assertThat(sessions().journeyOf(SANDBOX, holder)).contains(journey);
    }

    @Test
    void open_holderWithAnotherOpenSession_throwsAndKeepsTheFirst() {
        HolderRef holder = Holders.next();
        UUID first = newJourney(SANDBOX);
        sessions().open(SANDBOX, holder, first);

        assertThatThrownBy(() -> sessions().open(SANDBOX, holder, newJourney(SANDBOX)))
                .isInstanceOf(HolderAlreadyHasJourneyException.class);

        assertThat(sessions().journeyOf(SANDBOX, holder)).contains(first);
    }

    @Test
    void open_holderAfterItsSessionClosed_opensAnotherOne() {
        HolderRef holder = Holders.next();
        UUID first = newJourney(SANDBOX);
        sessions().open(SANDBOX, holder, first);
        sessions().close(SANDBOX, first);
        UUID second = newJourney(SANDBOX);

        sessions().open(SANDBOX, holder, second);

        assertThat(sessions().journeyOf(SANDBOX, holder)).contains(second);
    }

    @Test
    void read_writtenSections_returnsContent() {
        UUID journey = openSession(SANDBOX);

        sessions().write(SANDBOX, journey, Section.IDENTITY, "{\"givenName\":\"Ana\"}");
        sessions().write(SANDBOX, journey, Section.EMAIL, "\"ana@example.org\"");

        assertThat(sessions().read(SANDBOX, journey, Section.IDENTITY)).contains("{\"givenName\":\"Ana\"}");
        assertThat(sessions().read(SANDBOX, journey, Section.EMAIL)).contains("\"ana@example.org\"");
        assertThat(sessions().read(SANDBOX, journey, Section.ENTITLEMENT)).isEmpty();
    }

    @Test
    void write_sameSectionTwice_replacesContent() {
        UUID journey = openSession(SANDBOX);

        sessions().write(SANDBOX, journey, Section.ISSUANCE, "{\"v\":1}");
        sessions().write(SANDBOX, journey, Section.ISSUANCE, "{\"v\":2}");

        assertThat(sessions().read(SANDBOX, journey, Section.ISSUANCE)).contains("{\"v\":2}");
    }

    @Test
    void clear_writtenSection_emptiesOnlyThatSection() {
        UUID journey = openSession(SANDBOX);
        sessions().write(SANDBOX, journey, Section.EMAIL, "{\"email\":\"ana@example.org\"}");
        sessions().write(SANDBOX, journey, Section.EMAIL_VERIFICATION, "{\"sends\":1}");

        sessions().clear(SANDBOX, journey, Section.EMAIL);
        sessions().clear(SANDBOX, journey, Section.EMAIL);
        sessions().clear(SANDBOX, journey, Section.IDENTITY);

        assertThat(sessions().read(SANDBOX, journey, Section.EMAIL)).isEmpty();
        assertThat(sessions().read(SANDBOX, journey, Section.EMAIL_VERIFICATION)).contains("{\"sends\":1}");
    }

    @Test
    void write_emailVerificationSection_isReadBack() {
        UUID journey = openSession(SANDBOX);

        sessions().write(SANDBOX, journey, Section.EMAIL_VERIFICATION, "{\"sends\":2}");

        assertThat(sessions().read(SANDBOX, journey, Section.EMAIL_VERIFICATION)).contains("{\"sends\":2}");
        assertThat(sessions().read(SANDBOX, journey, Section.EMAIL)).isEmpty();
    }

    @Test
    void close_openSession_deletesItIdempotently() {
        HolderRef holder = Holders.next();
        UUID journey = newJourney(SANDBOX);
        sessions().open(SANDBOX, holder, journey);
        sessions().write(SANDBOX, journey, Section.IDENTITY, "{}");

        sessions().close(SANDBOX, journey);
        sessions().close(SANDBOX, journey);

        assertThat(sessions().journeyOf(SANDBOX, holder)).isEmpty();
        assertJourneyClosed(() -> sessions().read(SANDBOX, journey, Section.IDENTITY));
    }

    @Test
    void operations_unknownJourney_throwJourneyClosed() {
        UUID unknown = UUID.randomUUID();

        assertThat(sessions().journeyOf(SANDBOX, Holders.next())).isEmpty();
        assertJourneyClosed(() -> sessions().read(SANDBOX, unknown, Section.IDENTITY));
        assertJourneyClosed(() -> sessions().write(SANDBOX, unknown, Section.IDENTITY, "{}"));
        assertJourneyClosed(() -> sessions().clear(SANDBOX, unknown, Section.EMAIL));
    }

    @Test
    void journeyOf_holderOfAnotherTenant_returnsEmpty() {
        HolderRef holder = Holders.next();
        sessions().open(KPMG, holder, newJourney(KPMG));

        assertThat(sessions().journeyOf(SANDBOX, holder)).isEmpty();
    }

    private UUID openSession(TenantId tenant) {
        UUID journey = newJourney(tenant);
        sessions().open(tenant, Holders.next(), journey);
        return journey;
    }

    private static void assertJourneyClosed(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ProblemException.class)
                .extracting(e -> ((ProblemException) e).problem())
                .isEqualTo(ProblemCode.JOURNEY_CLOSED);
    }
}
