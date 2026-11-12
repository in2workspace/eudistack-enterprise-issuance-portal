package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.MutableClock;
import es.in2.issuanceportal.journey.InMemoryJourneyRepository;
import es.in2.issuanceportal.journey.RecordingJourneyAuditTrail;
import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail;
import es.in2.issuanceportal.journey.api.JourneyProgress;
import es.in2.issuanceportal.journey.api.JourneyWithdrawal;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyClosedException;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.journey.domain.JourneyStep;
import es.in2.issuanceportal.journey.domain.WithdrawalReason;
import es.in2.issuanceportal.session.Holders;
import es.in2.issuanceportal.session.InMemoryJourneySessions;
import es.in2.issuanceportal.session.api.HolderAlreadyHasJourneyException;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.HolderRef;
import es.in2.issuanceportal.session.api.IdentifierType;
import es.in2.issuanceportal.session.api.PersonalIdentifier;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JourneyServiceTest {

    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final HolderIdentity IDENTITY =
            new HolderIdentity("Lucia", "Prueba Sintetica", new PersonalIdentifier(IdentifierType.PERSONAL_ID, "00000001R"), null);

    private final InMemoryJourneySessions sessions = new InMemoryJourneySessions();
    private final InMemoryJourneyRepository journeys = new InMemoryJourneyRepository(sessions);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-19T10:00:00Z"));
    private final RecordingJourneyAuditTrail trail = new RecordingJourneyAuditTrail();
    private final JourneyService service =
            new JourneyService(journeys, sessions, trail, TransactionOperations.withoutTransaction(),
                TransactionOperations.withoutTransaction(), clock);

    @Test
    void open_holderWithoutJourney_createsJourneyInProgressBoundToHolderWithItsIdentity() {
        HolderRef holder = Holders.next();

        JourneyService.Opened opened = service.open(SANDBOX, holder, IDENTITY, "certificate");

        assertThat(opened.created()).isTrue();
        assertThat(opened.journey().state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(opened.journey().step()).isEqualTo(JourneyStep.STARTED);
        assertThat(opened.journey().identificationMethod()).contains("certificate");
        assertThat(sessions.journeyOf(SANDBOX, holder)).contains(opened.journey().id());
        assertThat(sessions.read(SANDBOX, opened.journey().id(), Section.IDENTITY)).contains(IDENTITY.toJson());
        assertThat(trail.events()).extracting(JourneyAuditTrail.Event::kind).containsExactly(JourneyAuditTrail.Kind.STARTED);
    }

    @Test
    void open_holderWithActiveJourney_returnsItWithoutRewritingItsIdentity() {
        HolderRef holder = Holders.next();
        JourneyService.Opened first = service.open(SANDBOX, holder, IDENTITY, "certificate");
        service.reach(SANDBOX, holder, JourneyProgress.Step.IDENTIFIED);
        HolderIdentity other = new HolderIdentity("Otra", "Persona", new PersonalIdentifier(IdentifierType.PERSONAL_ID, "99999999R"), null);

        JourneyService.Opened second = service.open(SANDBOX, holder, other, "certificate");

        assertThat(second.created()).isFalse();
        assertThat(second.journey().id()).isEqualTo(first.journey().id());
        assertThat(second.journey().step()).isEqualTo(JourneyStep.IDENTIFIED);
        assertThat(sessions.read(SANDBOX, first.journey().id(), Section.IDENTITY)).contains(IDENTITY.toJson());
        assertThat(trail.events()).extracting(JourneyAuditTrail.Event::kind)
                .containsExactly(JourneyAuditTrail.Kind.STARTED, JourneyAuditTrail.Kind.STEP_REACHED);
    }

    @Test
    void open_anotherHolder_opensItsOwnJourney() {
        JourneyService.Opened first = service.open(SANDBOX, Holders.next(), IDENTITY, "certificate");

        JourneyService.Opened second = service.open(SANDBOX, Holders.next(), IDENTITY, "certificate");

        assertThat(second.created()).isTrue();
        assertThat(second.journey().id()).isNotEqualTo(first.journey().id());
    }

    @Test
    void open_holderWithCompletedJourneyStillOpen_returnsIt() {
        Started started = open(service);
        complete(started);

        JourneyService.Opened opened = service.open(SANDBOX, started.holder(), IDENTITY, "certificate");

        assertThat(opened.created()).isFalse();
        assertThat(opened.journey().state()).isEqualTo(JourneyState.COMPLETED);
    }

    @Test
    void open_holderJourneyAlreadyInactive_expiresItAndOpensANewOne() {
        Started previous = open(service);
        clock.advance(Duration.ofMinutes(16));

        JourneyService.Opened next = service.open(SANDBOX, previous.holder(), IDENTITY, "certificate");

        assertThat(next.created()).isTrue();
        assertThat(next.journey().id()).isNotEqualTo(previous.journey().id());
        assertThat(stored(previous).withdrawalReason()).contains(WithdrawalReason.EXPIRED);
        assertThat(sessions.journeyOf(SANDBOX, previous.holder())).contains(next.journey().id());
        assertThat(trail.events())
                .filteredOn(event -> event.journeyId().equals(previous.journey().id())
                        && event.kind() == JourneyAuditTrail.Kind.WITHDRAWN)
                .singleElement()
                .satisfies(event -> assertThat(event.withdrawalReason()).contains(WithdrawalReason.EXPIRED));
    }

    @Test
    void open_holderWithAWithdrawnJourneyLeftBehind_closesItAndOpensANewOne() {
        Started previous = open(service);
        Journey withdrawn = stored(previous);
        withdrawn.withdraw(WithdrawalReason.CANCELLED, clock.instant());
        journeys.update(SANDBOX, withdrawn, JourneyState.IN_PROGRESS);

        JourneyService.Opened next = service.open(SANDBOX, previous.holder(), IDENTITY, "certificate");

        assertThat(next.created()).isTrue();
        assertThat(sessions.journeyOf(SANDBOX, previous.holder())).contains(next.journey().id());
    }

    @Test
    void open_anotherOpeningOfTheSameHolderGetsThereFirst_returnsTheirJourney() {
        AtomicBoolean staleRead = new AtomicBoolean(false);
        InMemoryJourneySessions racing = new InMemoryJourneySessions() {
            @Override
            public Optional<UUID> journeyOf(TenantId tenant, HolderRef who) {
                return staleRead.getAndSet(false) ? Optional.empty() : super.journeyOf(tenant, who);
            }
        };
        JourneyService racingService = new JourneyService(new InMemoryJourneyRepository(racing), racing, trail,
                TransactionOperations.withoutTransaction(), TransactionOperations.withoutTransaction(), clock);
        HolderRef holder = Holders.next();
        UUID theirs = racingService.open(SANDBOX, holder, IDENTITY, "certificate").journey().id();
        // This request read the holder's journey before the other one committed.
        staleRead.set(true);

        JourneyService.Opened opened = racingService.open(SANDBOX, holder, IDENTITY, "certificate");

        assertThat(opened.created()).isFalse();
        assertThat(opened.journey().id()).isEqualTo(theirs);
    }

    @Test
    void open_holderKeepsCollidingWithOthers_givesUpAfterThreeAttempts() {
        InMemoryJourneySessions colliding = new InMemoryJourneySessions() {
            @Override
            public Optional<UUID> journeyOf(TenantId tenant, HolderRef who) {
                return Optional.empty();
            }

            @Override
            public void open(TenantId tenant, HolderRef who, UUID journeyId) {
                throw new HolderAlreadyHasJourneyException(null);
            }
        };
        JourneyService stuck = new JourneyService(journeys, colliding, trail, TransactionOperations.withoutTransaction(),
                TransactionOperations.withoutTransaction(), clock);

        assertThatThrownBy(() -> stuck.open(SANDBOX, Holders.next(), IDENTITY, "certificate"))
                .isInstanceOf(HolderAlreadyHasJourneyException.class);
    }

    @Test
    void cancel_atDataStep_withdrawsWithStepAndDeletesPersonalData() {
        Started started = open(service);
        sessions.write(SANDBOX, started.journey().id(), Section.IDENTITY, "{\"givenName\":\"Ana\"}");
        service.reach(SANDBOX, started.holder(), JourneyProgress.Step.ENTITLEMENT_CONFIRMED);

        Journey journey = service.cancel(SANDBOX, started.holder());

        assertThat(journey.state()).isEqualTo(JourneyState.WITHDRAWN);
        assertThat(journey.step()).isEqualTo(JourneyStep.ENTITLEMENT_CONFIRMED);
        assertThat(journey.withdrawalReason()).contains(WithdrawalReason.CANCELLED);
        assertThat(sessions.journeyOf(SANDBOX, started.holder())).isEmpty();
        assertThat(journeys.findById(SANDBOX, journey.id())).isPresent();
    }

    @Test
    void cancel_completedJourney_onlyClosesSession() {
        Started started = open(service);
        complete(started);

        Journey journey = service.cancel(SANDBOX, started.holder());

        assertThat(journey.state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(journey.step()).isEqualTo(JourneyStep.OFFER_OBTAINED);
        assertThat(stored(started).state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(sessions.journeyOf(SANDBOX, started.holder())).isEmpty();
    }

    @Test
    void withdraw_notEntitled_withdrawsKeepingStep() {
        Started started = open(service);
        service.reach(SANDBOX, started.holder(), JourneyProgress.Step.IDENTIFIED);

        service.withdraw(SANDBOX, started.holder(), JourneyWithdrawal.Cause.NOT_ENTITLED);

        Journey journey = stored(started);
        assertThat(journey.state()).isEqualTo(JourneyState.WITHDRAWN);
        assertThat(journey.step()).isEqualTo(JourneyStep.IDENTIFIED);
        assertThat(journey.withdrawalReason()).contains(WithdrawalReason.NOT_ENTITLED);
        assertThat(sessions.journeyOf(SANDBOX, started.holder())).isEmpty();
        JourneyAuditTrail.Event withdrawn = trail.events().getLast();
        assertThat(withdrawn.kind()).isEqualTo(JourneyAuditTrail.Kind.WITHDRAWN);
        assertThat(withdrawn.step()).isEqualTo(JourneyStep.IDENTIFIED);
        assertThat(withdrawn.withdrawalReason()).contains(WithdrawalReason.NOT_ENTITLED);
    }

    @Test
    void expireInactive_sweptTwice_recordsOneWithdrawnEventPerJourney() {
        Started first = open(service);
        Started second = open(service);
        clock.advance(Duration.ofMinutes(15));

        service.expireInactive(SANDBOX);
        service.expireInactive(SANDBOX);

        for (Started started : List.of(first, second)) {
            assertThat(trail.events())
                    .filteredOn(event -> event.journeyId().equals(started.journey().id())
                            && event.kind() == JourneyAuditTrail.Kind.WITHDRAWN)
                    .hasSize(1);
        }
    }

    @Test
    void expireInactive_moreJourneysThanOneBatch_expiresThemAll() {
        int count = JourneyService.SWEEP_BATCH + 5;
        for (int i = 0; i < count; i++) {
            open(service);
        }
        clock.advance(Duration.ofMinutes(15));

        assertThat(service.expireInactive(SANDBOX)).isEqualTo(count);
        assertThat(sessions.openJourneys(SANDBOX)).isEmpty();
    }

    @Test
    void expireInactive_journeyUsedAfterTheSweepRead_isNotExpired() {
        Started started = open(service);
        clock.advance(Duration.ofMinutes(15));
        // The holder's request renews the activity between the sweep's read and its write.
        Journey used = stored(started);
        used.touch(clock.instant());
        journeys.update(SANDBOX, used, JourneyState.IN_PROGRESS);
        Journey staleRead = Journey.restore(used.id(), JourneyState.IN_PROGRESS, JourneyStep.STARTED, null, null,
                null, used.createdAt(), used.createdAt(), null);
        staleRead.withdraw(WithdrawalReason.EXPIRED, clock.instant());

        assertThat(journeys.updateIfInactive(SANDBOX, staleRead, clock.instant().minus(Journey.INACTIVITY_LIMIT)))
                .isFalse();
        assertThat(journeys.deleteSessionIfClosedOrInactive(SANDBOX, used.id(),
                clock.instant().minus(Journey.INACTIVITY_LIMIT))).isFalse();
        assertThat(stored(started).state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(sessions.journeyOf(SANDBOX, started.holder())).isPresent();
    }

    @Test
    void current_inactiveForFifteenMinutes_withdrawsAsExpiredAndThrowsJourneyClosed() {
        Started started = open(service);
        clock.advance(Duration.ofMinutes(16));

        assertJourneyClosed(() -> service.current(SANDBOX, started.holder()));

        Journey journey = stored(started);
        assertThat(journey.state()).isEqualTo(JourneyState.WITHDRAWN);
        assertThat(journey.withdrawalReason()).contains(WithdrawalReason.EXPIRED);
        assertThat(sessions.journeyOf(SANDBOX, started.holder())).isEmpty();
    }

    @Test
    void current_activityWithinLimit_pushesBackExpiry() {
        Started started = open(service);
        clock.advance(Duration.ofMinutes(14));
        service.current(SANDBOX, started.holder());
        clock.advance(Duration.ofMinutes(14));

        assertThat(service.current(SANDBOX, started.holder()).state()).isEqualTo(JourneyState.IN_PROGRESS);
    }

    @Test
    void current_closedOrUnknownSession_throwsJourneyClosed() {
        Started started = open(service);
        service.cancel(SANDBOX, started.holder());

        assertJourneyClosed(() -> service.current(SANDBOX, started.holder()));
        assertJourneyClosed(() -> service.cancel(SANDBOX, started.holder()));
        assertJourneyClosed(() -> service.current(SANDBOX, Holders.next()));
    }

    @Test
    void withdraw_completedJourney_throwsJourneyClosed() {
        Started started = open(service);
        complete(started);

        assertJourneyClosed(() -> service.withdraw(SANDBOX, started.holder(), JourneyWithdrawal.Cause.NOT_ENTITLED));
    }

    @Test
    void expireInactive_inactiveJourneyInProgress_withdrawsAsExpiredAndKeepsIt() {
        Started inactive = open(service);
        service.reach(SANDBOX, inactive.holder(), JourneyProgress.Step.IDENTIFIED);
        clock.advance(Duration.ofMinutes(10));
        Started active = open(service);
        clock.advance(Duration.ofMinutes(6));

        assertThat(service.expireInactive(SANDBOX)).isEqualTo(1);

        Journey expired = stored(inactive);
        assertThat(expired.state()).isEqualTo(JourneyState.WITHDRAWN);
        assertThat(expired.step()).isEqualTo(JourneyStep.IDENTIFIED);
        assertThat(expired.withdrawalReason()).contains(WithdrawalReason.EXPIRED);
        assertThat(sessions.journeyOf(SANDBOX, inactive.holder())).isEmpty();
        assertThat(stored(active).state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(sessions.journeyOf(SANDBOX, active.holder())).isPresent();
    }

    @Test
    void expireInactive_inactiveCompletedJourney_onlyClosesSession() {
        Started started = open(service);
        complete(started);
        clock.advance(Duration.ofMinutes(15));

        assertThat(service.expireInactive(SANDBOX)).isEqualTo(1);

        assertThat(stored(started).state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(sessions.journeyOf(SANDBOX, started.holder())).isEmpty();
    }

    @Test
    void expireInactive_secondSweep_closesNothing() {
        open(service);
        clock.advance(Duration.ofMinutes(15));
        service.expireInactive(SANDBOX);

        assertThat(service.expireInactive(SANDBOX)).isZero();
    }

    @Test
    void stateChanges_fullJourney_recordEventWithStepReached() {
        Started started = open(service);
        service.reach(SANDBOX, started.holder(), JourneyProgress.Step.IDENTIFIED);
        service.reach(SANDBOX, started.holder(), JourneyProgress.Step.ENTITLEMENT_CONFIRMED);
        service.cancel(SANDBOX, started.holder());

        assertThat(trail.events())
                .extracting(JourneyAuditTrail.Event::kind, JourneyAuditTrail.Event::state, JourneyAuditTrail.Event::step)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(JourneyAuditTrail.Kind.STARTED, JourneyState.IN_PROGRESS, JourneyStep.STARTED),
                        org.assertj.core.groups.Tuple.tuple(JourneyAuditTrail.Kind.STEP_REACHED, JourneyState.IN_PROGRESS, JourneyStep.IDENTIFIED),
                        org.assertj.core.groups.Tuple.tuple(JourneyAuditTrail.Kind.STEP_REACHED, JourneyState.IN_PROGRESS, JourneyStep.ENTITLEMENT_CONFIRMED),
                        org.assertj.core.groups.Tuple.tuple(JourneyAuditTrail.Kind.WITHDRAWN, JourneyState.WITHDRAWN, JourneyStep.ENTITLEMENT_CONFIRMED));
        assertThat(trail.events().getLast().withdrawalReason()).contains(WithdrawalReason.CANCELLED);
        assertThat(trail.events()).allSatisfy(event -> assertThat(event.journeyId()).isEqualTo(started.journey().id()));
    }

    @Test
    void currentAndReach_noStateChange_recordNoEvent() {
        Started started = open(service);
        service.reach(SANDBOX, started.holder(), JourneyProgress.Step.ENTITLEMENT_CONFIRMED);
        int before = trail.events().size();

        service.current(SANDBOX, started.holder());
        service.reach(SANDBOX, started.holder(), JourneyProgress.Step.IDENTIFIED);

        assertThat(trail.events()).hasSize(before);
    }

    @Test
    void expireInactive_inactiveJourney_recordsWithdrawnEventWithExpiredReason() {
        open(service);
        clock.advance(Duration.ofMinutes(15));

        service.expireInactive(SANDBOX);

        assertThat(trail.events().getLast().kind()).isEqualTo(JourneyAuditTrail.Kind.WITHDRAWN);
        assertThat(trail.events().getLast().withdrawalReason()).contains(WithdrawalReason.EXPIRED);
    }

    @Test
    void startAndCancel_auditTrailUnavailable_completeNormally() {
        trail.failFromNowOn();

        Started started = open(service);
        Journey journey = service.cancel(SANDBOX, started.holder());

        assertThat(journey.state()).isEqualTo(JourneyState.WITHDRAWN);
        assertThat(sessions.journeyOf(SANDBOX, started.holder())).isEmpty();
    }

    @Test
    void expireInactive_oneJourneyFails_expiresTheOthers() {
        UUID[] poisoned = new UUID[1];
        InMemoryJourneyRepository failingOnOne = new InMemoryJourneyRepository(sessions) {
            @Override
            public Optional<Journey> findById(TenantId tenant, UUID id) {
                if (id.equals(poisoned[0])) {
                    throw new IllegalStateException("unreadable row");
                }
                return super.findById(tenant, id);
            }
        };
        JourneyService sweeping = new JourneyService(failingOnOne, sessions, trail,
                TransactionOperations.withoutTransaction(),
                TransactionOperations.withoutTransaction(), clock);
        Started bad = open(sweeping);
        Started good = open(sweeping);
        poisoned[0] = bad.journey().id();
        clock.advance(Duration.ofMinutes(15));

        assertThat(sweeping.expireInactive(SANDBOX)).isEqualTo(1);

        assertThat(sessions.journeyOf(SANDBOX, good.holder())).isEmpty();
        assertThat(failingOnOne.findById(SANDBOX, good.journey().id()).orElseThrow().withdrawalReason())
                .contains(WithdrawalReason.EXPIRED);
    }

    @Test
    void requireInProgress_journeyInProgress_passesAndRecordsActivity() {
        Started started = open(service);
        clock.advance(Duration.ofMinutes(10));

        service.requireInProgress(SANDBOX, started.holder());

        assertThat(stored(started).lastActivity()).isEqualTo(clock.instant());
    }

    @Test
    void requireInProgress_completedJourney_isClosedThoughRequireActivePasses() {
        Started started = open(service);
        complete(started);

        service.requireActive(SANDBOX, started.holder());
        assertJourneyClosed(() -> service.requireInProgress(SANDBOX, started.holder()));
    }

    private record Started(Journey journey, HolderRef holder) {
    }

    /** A journey of a new holder. */
    private Started open(JourneyService opening) {
        HolderRef holder = Holders.next();
        return new Started(opening.open(SANDBOX, holder, IDENTITY, null).journey(), holder);
    }

    private void complete(Started started) {
        Journey journey = stored(started);
        journey.complete(clock.instant());
        journeys.update(SANDBOX, journey, JourneyState.IN_PROGRESS);
    }

    private Journey stored(Started started) {
        return journeys.findById(SANDBOX, started.journey().id()).orElseThrow();
    }

    private static void assertJourneyClosed(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(JourneyClosedException.class);
    }
}
