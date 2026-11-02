package es.in2.issuanceportal.journey.domain.config;

/** Whether the tenant uses onboarding tickets, and where its Wallet Unit Service is (design.md §3.4). */
public record Onboarding(boolean enabled, String walletUnitServiceUrl) {

    public static final Onboarding DISABLED = new Onboarding(false, null);

    public Onboarding {
        ConfigValues.requireWebUrl(walletUnitServiceUrl, "onboarding.wallet_unit_service_url");
    }
}
