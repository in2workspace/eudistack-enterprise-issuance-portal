package es.in2.issuanceportal.journey.infrastructure.web;

import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.journey.api.JourneyProgress;
import es.in2.issuanceportal.journey.api.JourneyWithdrawal;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.tenant.TenantId;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The journey API end to end, against PostgreSQL: the scenarios of ac.md. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class JourneyApiIT {

    private static final String CONTEXT = "/issuance-portal";
    private static final String JOURNEY = CONTEXT + "/api/v1/journey";
    private static final String SANDBOX_HOST = "sandbox.127.0.0.1.nip.io";
    private static final TenantId SANDBOX = new TenantId("sandbox");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JourneySessions sessions;
    @Autowired
    private JourneyProgress progress;
    @Autowired
    private JourneyWithdrawal withdrawal;

    @Test
    @DisplayName("AC-01 — postJourney_noCookie_createsJourneyWithSessionAndCookie")
    void postJourney_noCookie_createsJourneyWithSessionAndCookie() throws Exception {
        MvcResult result = mvc.perform(post(JOURNEY, SANDBOX_HOST))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("in_progress"))
                .andExpect(jsonPath("$.step").value("started"))
                .andExpect(jsonPath("$.withdrawal_reason").doesNotExist())
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith("journey_session=")
                .contains("Path=/issuance-portal", "Secure", "HttpOnly", "SameSite=Lax")
                .doesNotContain("Domain");
        SessionId session = sessionOf(result);
        UUID journeyId = sessions.journeyOf(SANDBOX, session).orElseThrow();
        assertThat(journeyRow(journeyId)).containsEntry("state", "in_progress");
        assertThat(jdbc.sql("SELECT session_id_hash FROM \"sandbox_issuance_portal\".journey_session WHERE journey_id = :j")
                .param("j", journeyId).query(String.class).single()).isEqualTo(session.hash());
    }

    @Test
    @DisplayName("AC-02 — getJourney_journeyInProgress_returnsStateAndStep")
    void getJourney_journeyInProgress_returnsStateAndStep() throws Exception {
        SessionId session = start();

        mvc.perform(withSession(get(JOURNEY, SANDBOX_HOST), session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("in_progress"))
                .andExpect(jsonPath("$.step").value("started"));
    }

    @Test
    @DisplayName("AC-03 — postWithdrawal_atDataStep_withdrawsWithStepAndDeletesPersonalData")
    void postWithdrawal_atDataStep_withdrawsWithStepAndDeletesPersonalData() throws Exception {
        SessionId session = start();
        UUID journeyId = sessions.journeyOf(SANDBOX, session).orElseThrow();
        sessions.write(SANDBOX, session, Section.IDENTITY, "{\"givenName\":\"Ana\"}");
        progress.reach(SANDBOX, session, JourneyProgress.Step.ENTITLEMENT_CONFIRMED);

        MvcResult result = mvc.perform(withSession(post(JOURNEY + "/withdrawal", SANDBOX_HOST), session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("withdrawn"))
                .andExpect(jsonPath("$.step").value("entitlement_confirmed"))
                .andExpect(jsonPath("$.withdrawal_reason").value("cancelled"))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
        assertThat(sessionRows(journeyId)).isZero();
        assertThat(journeyRow(journeyId)).containsEntry("state", "withdrawn").containsKey("ended_at");
        assertThat(journeyRow(journeyId).get("ended_at")).isNotNull();
    }

    @Test
    @DisplayName("AC-06 — withdrawalPort_notEntitled_withdrawsKeepingStep")
    void withdrawalPort_notEntitled_withdrawsKeepingStep() throws Exception {
        SessionId session = start();
        UUID journeyId = sessions.journeyOf(SANDBOX, session).orElseThrow();
        progress.reach(SANDBOX, session, JourneyProgress.Step.IDENTIFIED);

        withdrawal.withdraw(SANDBOX, session, JourneyWithdrawal.Cause.NOT_ENTITLED);

        assertThat(journeyRow(journeyId))
                .containsEntry("state", "withdrawn")
                .containsEntry("step", "identified")
                .containsEntry("withdrawal_reason", "not_entitled");
        assertThat(sessionRows(journeyId)).isZero();
    }

    @Test
    @DisplayName("EC-01 — postJourney_previousJourneyInBrowser_withdrawsItAsCancelled")
    void postJourney_previousJourneyInBrowser_withdrawsItAsCancelled() throws Exception {
        SessionId previous = start();
        UUID previousJourney = sessions.journeyOf(SANDBOX, previous).orElseThrow();

        MvcResult result = mvc.perform(withSession(post(JOURNEY, SANDBOX_HOST), previous))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(sessionOf(result)).isNotEqualTo(previous);
        assertThat(journeyRow(previousJourney))
                .containsEntry("state", "withdrawn")
                .containsEntry("withdrawal_reason", "cancelled");
        assertThat(sessionRows(previousJourney)).isZero();
    }

    @Test
    @DisplayName("EC-02 — postWithdrawal_completedJourney_onlyClosesSession")
    void postWithdrawal_completedJourney_onlyClosesSession() throws Exception {
        SessionId session = start();
        UUID journeyId = sessions.journeyOf(SANDBOX, session).orElseThrow();
        jdbc.sql("UPDATE \"sandbox_issuance_portal\".journey SET state = 'completed', step = 'offer_obtained', "
                + "ended_at = now() WHERE id = :id").param("id", journeyId).update();

        mvc.perform(withSession(post(JOURNEY + "/withdrawal", SANDBOX_HOST), session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.step").value("offer_obtained"));

        assertThat(journeyRow(journeyId)).containsEntry("state", "completed");
        assertThat(sessionRows(journeyId)).isZero();
    }

    @Test
    @DisplayName("EC-03 — getJourney_staleSessionBeforeExpiryJob_returns410AndWithdrawsAsExpired")
    void getJourney_staleSessionBeforeExpiryJob_returns410AndWithdrawsAsExpired() throws Exception {
        SessionId session = start();
        UUID journeyId = sessions.journeyOf(SANDBOX, session).orElseThrow();
        jdbc.sql("UPDATE \"sandbox_issuance_portal\".journey SET last_activity = now() - interval '16 minutes' "
                + "WHERE id = :id").param("id", journeyId).update();

        mvc.perform(withSession(get(JOURNEY, SANDBOX_HOST), session))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));

        assertThat(journeyRow(journeyId))
                .containsEntry("state", "withdrawn")
                .containsEntry("withdrawal_reason", "expired");
        assertThat(sessionRows(journeyId)).isZero();
    }

    @Test
    @DisplayName("EC-06 — getJourney_cookieOfAnotherTenant_returns410")
    void getJourney_cookieOfAnotherTenant_returns410() throws Exception {
        MvcResult kpmg = mvc.perform(post(JOURNEY, "kpmg.127.0.0.1.nip.io")).andReturn();
        SessionId session = sessionOf(kpmg);

        mvc.perform(withSession(get(JOURNEY, SANDBOX_HOST), session))
                .andExpect(status().isGone());
        mvc.perform(withSession(get(JOURNEY, "kpmg.127.0.0.1.nip.io"), session))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ES-01 — getAndWithdraw_withdrawnJourney_return410")
    void getAndWithdraw_withdrawnJourney_return410() throws Exception {
        SessionId session = start();
        mvc.perform(withSession(post(JOURNEY + "/withdrawal", SANDBOX_HOST), session)).andExpect(status().isOk());

        mvc.perform(withSession(get(JOURNEY, SANDBOX_HOST), session))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));
        mvc.perform(withSession(post(JOURNEY + "/withdrawal", SANDBOX_HOST), session))
                .andExpect(status().isGone());
    }

    @Test
    @DisplayName("ES-02 — getAndWithdraw_noOrUnknownCookie_return410")
    void getAndWithdraw_noOrUnknownCookie_return410() throws Exception {
        mvc.perform(get(JOURNEY, SANDBOX_HOST)).andExpect(status().isGone());
        mvc.perform(withSession(get(JOURNEY, SANDBOX_HOST), SessionId.generate())).andExpect(status().isGone());
        mvc.perform(post(JOURNEY + "/withdrawal", SANDBOX_HOST)
                        .cookie(new Cookie("journey_session", "not-a-session")))
                .andExpect(status().isGone());
    }

    @Test
    @DisplayName("ES-03 — postJourney_withoutRequestedWithHeader_returns403AndChangesNothing")
    void postJourney_withoutRequestedWithHeader_returns403AndChangesNothing() throws Exception {
        long before = journeyCount();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(JOURNEY)
                        .contextPath(CONTEXT).with(host(SANDBOX_HOST)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("request_not_allowed"));

        assertThat(journeyCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("ES-03 — postJourney_encodedPathWithoutHeader_returns403")
    void postJourney_encodedPathWithoutHeader_returns403() throws Exception {
        long before = journeyCount();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(java.net.URI.create(CONTEXT + "/%61pi/v1/journey"))
                        .contextPath(CONTEXT).with(host(SANDBOX_HOST)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("request_not_allowed"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(java.net.URI.create(CONTEXT + "/api;x/v1/journey"))
                        .contextPath(CONTEXT).with(host(SANDBOX_HOST)))
                .andExpect(status().isForbidden());

        assertThat(journeyCount()).isEqualTo(before);
    }

    @Test
    void postJourney_unknownTenant_returns404() throws Exception {
        mvc.perform(post(JOURNEY, "altia.127.0.0.1.nip.io"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("tenant_not_found"));
    }

    private SessionId start() throws Exception {
        return sessionOf(mvc.perform(post(JOURNEY, SANDBOX_HOST)).andExpect(status().isCreated()).andReturn());
    }

    private static SessionId sessionOf(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("journey_session");
        return SessionId.parse(cookie.getValue()).orElseThrow();
    }

    /** A request to the service as nginx forwards it, with the state-changing header the interface sends. */
    private static MockHttpServletRequestBuilder post(String path, String host) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contextPath(CONTEXT)
                .header("X-Requested-With", "issuance-portal")
                .with(host(host));
    }

    private static MockHttpServletRequestBuilder get(String path, String host) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .contextPath(CONTEXT)
                .with(host(host));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor host(String host) {
        return request -> {
            request.setServerName(host);
            return request;
        };
    }

    private static MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder request, SessionId session) {
        return request.cookie(new Cookie("journey_session", session.value()));
    }

    private Map<String, Object> journeyRow(UUID id) {
        return jdbc.sql("SELECT state, step, withdrawal_reason, ended_at FROM \"sandbox_issuance_portal\".journey WHERE id = :id")
                .param("id", id).query().singleRow();
    }

    private long sessionRows(UUID journeyId) {
        return jdbc.sql("SELECT count(*) FROM \"sandbox_issuance_portal\".journey_session WHERE journey_id = :j")
                .param("j", journeyId).query(Long.class).single();
    }

    private long journeyCount() {
        return jdbc.sql("SELECT count(*) FROM \"sandbox_issuance_portal\".journey").query(Long.class).single();
    }
}
