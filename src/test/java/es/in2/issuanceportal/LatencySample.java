package es.in2.issuanceportal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Latencies of one operation measured at the HTTP boundary of the service, for the performance tests (EUD-277
 * NFR-1, EUD-359 NFR-2): p50, p95 and maximum in milliseconds, printed so a run leaves the figures behind.
 */
public final class LatencySample {

    private final String operation;
    private final List<Long> nanos = new ArrayList<>();

    public LatencySample(String operation) {
        this.operation = operation;
    }

    /** One timed call. */
    public void time(ThrowingRunnable call) throws Exception {
        long start = System.nanoTime();
        call.run();
        nanos.add(System.nanoTime() - start);
    }

    /** The p95 in milliseconds, after printing p50, p95 and the maximum. */
    public double p95Millis() {
        List<Long> sorted = new ArrayList<>(nanos);
        sorted.sort(Comparator.naturalOrder());
        int size = sorted.size();
        double p50 = sorted.get(size / 2 - 1) / 1_000_000.0;
        double p95 = sorted.get((int) Math.ceil(size * 0.95) - 1) / 1_000_000.0;
        double max = sorted.getLast() / 1_000_000.0;
        System.out.printf("NFR-2 %s over %d requests: p50 = %.1f ms, p95 = %.1f ms, max = %.1f ms%n",
                operation, size, p50, p95, max);
        return p95;
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
