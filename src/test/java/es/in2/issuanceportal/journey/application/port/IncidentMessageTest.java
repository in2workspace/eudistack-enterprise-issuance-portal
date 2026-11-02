package es.in2.issuanceportal.journey.application.port;

import es.in2.issuanceportal.journey.domain.incident.Incident;
import es.in2.issuanceportal.journey.domain.incident.IncidentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentMessageTest {

    private static final String SUPPORT = "soporte@cgcom-identidad.es";
    /** Built from code points, so the source holds no invisible characters. */
    private static final String RLO = Character.toString(0x202E);
    private static final String ZERO_WIDTH_SPACE = Character.toString(0x200B);
    private static final String BYTE_ORDER_MARK = Character.toString(0xFEFF);
    private static final String LEFT_TO_RIGHT_ISOLATE = Character.toString(0x2066);

    @Test
    @DisplayName("AC-04 — of_validIncident_goesToSupportWithHolderAsReplyTo")
    void of_validIncident_goesToSupportWithHolderAsReplyTo() {
        Incident incident = new Incident("Ana García", "ana@example.org", "600 000 000", IncidentType.ACCESS,
                "No puedo entrar", "Tras identificarme vuelvo al inicio.");

        IncidentMessage message = IncidentMessage.of(incident, SUPPORT);

        assertThat(message.to()).isEqualTo(SUPPORT);
        assertThat(message.replyTo()).isEqualTo("ana@example.org");
        assertThat(message.subject()).isEqualTo("[Incidencia] Problema de acceso: No puedo entrar");
        assertThat(message.body()).contains("Nombre: Ana García", "Correo: ana@example.org",
                "Teléfono: 600 000 000", "Tipo: Problema de acceso", "Asunto: No puedo entrar",
                "Tras identificarme vuelvo al inicio.");
    }

    @Test
    @DisplayName("EC-02 — of_withoutPhone_saysNotGiven")
    void of_withoutPhone_saysNotGiven() {
        Incident incident = new Incident("Ana", "ana@example.org", null, IncidentType.OTHER, "Duda", "Texto");

        assertThat(IncidentMessage.of(incident, SUPPORT).body()).contains("Teléfono: no indicado");
        assertThat(IncidentMessage.of(withPhone(" "), SUPPORT).body()).contains("Teléfono: no indicado");
    }

    @Test
    @DisplayName("EC-03 — of_subjectWithLineBreaks_cannotAddHeaders")
    void of_subjectWithLineBreaks_cannotAddHeaders() {
        Incident incident = new Incident("Ana", "ana@example.org", null, IncidentType.TECHNICAL,
                "Hola\r\nBcc: victim@example.org\n\tX", "Texto");

        String subject = IncidentMessage.of(incident, SUPPORT).subject();

        assertThat(subject).doesNotContain("\r", "\n", "\t")
                .isEqualTo("[Incidencia] Problema técnico: Hola Bcc: victim@example.org X");
    }

    @Test
    @DisplayName("EC-03 — of_unicodeLineSeparatorsInSubject_areRemoved")
    void of_unicodeLineSeparatorsInSubject_areRemoved() {
        Incident incident = new Incident("Ana", "ana@example.org", null, IncidentType.TECHNICAL,
                "Hola\u2028Bcc: x@example.org\u0085Fin\u2029", "Texto");

        assertThat(IncidentMessage.of(incident, SUPPORT).subject())
                .doesNotContain("\u2028", "\u0085", "\u2029")
                .isEqualTo("[Incidencia] Problema técnico: Hola Bcc: x@example.org Fin");
    }

    @Test
    @DisplayName("EC-03 — of_invisibleFormatCharacters_areRemoved")
    void of_invisibleFormatCharacters_areRemoved() {
        Incident incident = new Incident("Ana" + RLO + "txe", "ana@example.org", null, IncidentType.OTHER,
                "Duda" + ZERO_WIDTH_SPACE + BYTE_ORDER_MARK + " urgente" + LEFT_TO_RIGHT_ISOLATE, "Texto");

        IncidentMessage message = IncidentMessage.of(incident, SUPPORT);

        assertThat(message.subject()).isEqualTo("[Incidencia] Otro: Duda urgente");
        assertThat(message.body()).contains("Nombre: Ana txe\n").doesNotContain(RLO, ZERO_WIDTH_SPACE, BYTE_ORDER_MARK);
    }

    @Test
    @DisplayName("EC-03 — of_lineBreakInName_cannotFakeAnotherField")
    void of_lineBreakInName_cannotFakeAnotherField() {
        Incident incident = new Incident("Ana\nCorreo: falso@example.org", "ana@example.org", "600\r\n1",
                IncidentType.OTHER, "Duda", "Texto");

        String body = IncidentMessage.of(incident, SUPPORT).body();

        assertThat(body).contains("Nombre: Ana Correo: falso@example.org\n", "Teléfono: 600 1\n")
                .doesNotContain("\nCorreo: falso@example.org");
    }

    @Test
    @DisplayName("EC-03 — of_htmlInDescription_isKeptAsPlainText")
    void of_htmlInDescription_isKeptAsPlainText() {
        Incident incident = new Incident("Ana", "ana@example.org", null, IncidentType.OTHER, "Duda",
                "<script>alert(1)</script>\nSegunda línea");

        assertThat(IncidentMessage.of(incident, SUPPORT).body()).contains("<script>alert(1)</script>\nSegunda línea");
    }

    @Test
    void toString_messageAndIncident_neverPrintTheContent() {
        Incident incident = new Incident("Ana García", "ana@example.org", "600", IncidentType.OTHER, "Asunto", "Texto");

        assertThat(incident.toString()).doesNotContain("Ana", "ana@example.org", "600", "Asunto", "Texto");
        assertThat(IncidentMessage.of(incident, SUPPORT).toString()).doesNotContain("Ana", "ana@example.org");
    }

    private static Incident withPhone(String phone) {
        return new Incident("Ana", "ana@example.org", phone, IncidentType.OTHER, "Duda", "Texto");
    }
}
