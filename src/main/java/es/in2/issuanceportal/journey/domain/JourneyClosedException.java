package es.in2.issuanceportal.journey.domain;

/**
 * The journey is no longer open: withdrawn, or completed with its session closed, or changed by someone
 * else first. The API answers {@code journey_closed} (410).
 */
public class JourneyClosedException extends RuntimeException {

    public JourneyClosedException() {
        super("journey_closed");
    }
}
