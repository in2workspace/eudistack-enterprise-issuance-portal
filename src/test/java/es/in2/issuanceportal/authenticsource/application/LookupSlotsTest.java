package es.in2.issuanceportal.authenticsource.application;

import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LookupSlotsTest {

    private static final TenantId CGCOM = new TenantId("cgcom");
    private static final TenantId SANDBOX = new TenantId("sandbox");

    @Test
    void tryAcquire_tenantOutOfSlots_otherTenantsStillGetOne() {
        LookupSlots slots = new LookupSlots(10, 2);
        slots.tryAcquire(CGCOM).orElseThrow();
        slots.tryAcquire(CGCOM).orElseThrow();

        assertThat(slots.tryAcquire(CGCOM)).isEmpty();
        assertThat(slots.tryAcquire(SANDBOX)).isPresent();
    }

    @Test
    void tryAcquire_instanceOutOfSlots_noTenantGetsOne() {
        LookupSlots slots = new LookupSlots(2, 2);
        slots.tryAcquire(CGCOM).orElseThrow();
        slots.tryAcquire(SANDBOX).orElseThrow();

        assertThat(slots.tryAcquire(CGCOM)).isEmpty();
        assertThat(slots.tryAcquire(SANDBOX)).isEmpty();
    }

    @Test
    void tryAcquire_instanceOutOfSlots_keepsTheTenantSlotFree() {
        LookupSlots slots = new LookupSlots(1, 1);
        LookupSlots.Slot held = slots.tryAcquire(SANDBOX).orElseThrow();
        assertThat(slots.tryAcquire(CGCOM)).isEmpty();

        held.release();

        assertThat(slots.tryAcquire(CGCOM)).isPresent();
    }

    @Test
    void abandon_beforeTheSearchStarts_givesTheSlotBackAndTheSearchMustNotRun() {
        LookupSlots slots = new LookupSlots(1, 1);
        LookupSlots.Slot slot = slots.tryAcquire(CGCOM).orElseThrow();

        slot.abandon();

        assertThat(slot.start()).isFalse();
        assertThat(slots.tryAcquire(CGCOM)).isPresent();
    }

    @Test
    void abandon_afterTheSearchStarted_keepsTheSlotUntilTheSearchEnds() {
        LookupSlots slots = new LookupSlots(1, 1);
        LookupSlots.Slot slot = slots.tryAcquire(CGCOM).orElseThrow();
        assertThat(slot.start()).isTrue();

        slot.abandon();
        assertThat(slots.tryAcquire(CGCOM)).isEmpty();

        slot.release();
        assertThat(slots.tryAcquire(CGCOM)).isPresent();
    }

    @Test
    void release_twice_givesTheSlotBackOnce() {
        LookupSlots slots = new LookupSlots(1, 1);
        LookupSlots.Slot slot = slots.tryAcquire(CGCOM).orElseThrow();

        slot.release();
        slot.release();

        assertThat(slots.tryAcquire(CGCOM)).isPresent();
        assertThat(slots.tryAcquire(CGCOM)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "1, 0", "-1, 20"})
    void constructor_limitBelowOne_doesNotStart(int perInstance, int perTenant) {
        assertThatThrownBy(() -> new LookupSlots(perInstance, perTenant))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
