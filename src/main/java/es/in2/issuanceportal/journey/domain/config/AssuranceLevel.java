package es.in2.issuanceportal.journey.domain.config;

import java.util.Set;

/**
 * Level of assurance (eIDAS) of the tenant's login (design.md AD-20): the level the interface asks the Verifier for as
 * essential, and the lowest the service accepts in the Access Token. {@code high} when the tenant does not configure it.
 */
public enum AssuranceLevel {

    HIGH("high", "http://eidas.europa.eu/LoA/high"),
    SUBSTANTIAL("substantial", "http://eidas.europa.eu/LoA/substantial");

    private final String value;
    private final String acr;

    AssuranceLevel(String value, String acr) {
        this.value = value;
        this.acr = acr;
    }

    /** The value of {@code assurance_level} in the portal configuration and in {@code GET /config}. */
    public String value() {
        return value;
    }

    /** The {@code acr} values of a login at this level or higher. */
    public Set<String> acceptedAcrValues() {
        return this == HIGH ? Set.of(HIGH.acr) : Set.of(SUBSTANTIAL.acr, HIGH.acr);
    }

    /**
     * The level of {@code assurance_level}: {@code high} without the field. Any other value makes the configuration
     * invalid, so that no token is accepted at a level nobody chose.
     */
    public static AssuranceLevel ofConfig(String value) {
        if (value == null) {
            return HIGH;
        }
        for (AssuranceLevel level : values()) {
            if (level.value.equals(value)) {
                return level;
            }
        }
        throw new InvalidPortalConfigException("assurance_level must be high or substantial");
    }
}
