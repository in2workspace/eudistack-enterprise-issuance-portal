package es.in2.issuanceportal.authenticsource.domain.config;

import java.util.Arrays;
import java.util.Optional;

/** How the HTTP adapter authenticates to the tenant's source (design.md AD-2, AD-4). */
public enum SourceAuth {

    /** The service's own system token for the tenant. */
    SYSTEM_TOKEN("system_token"),
    /** A credential kept in SSM Parameter Store, sent as a bearer token. */
    SECRET("secret");

    private final String code;

    SourceAuth(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<SourceAuth> fromCode(String code) {
        return Arrays.stream(values()).filter(auth -> auth.code.equals(code)).findFirst();
    }
}
