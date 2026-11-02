package es.in2.issuanceportal.journey.domain.config;

/** Support details of the tenant, as shown to the holder. Every field is optional in the contract. */
public record Support(String email, String phone, String schedule, String responseTime) {

    public Support {
        ConfigValues.requireEmail(email, "support.email");
    }
}
