package es.in2.issuanceportal.authenticsource.domain.config;

/**
 * One attribute of the source translated into one claim of the attestation, generalising the fixed
 * {@code Mapping} of {@code DataAcquisitionProperties} in {@code eudistack-enterprise-backend}.
 *
 * @param attribute the source's name
 * @param claim     the attestation's name
 * @param label     what the Data step shows next to the value; {@code null} keeps it out of the preview
 */
public record AttributeMapping(String attribute, String claim, String label) {

    public AttributeMapping {
        if (attribute == null || attribute.isBlank()) {
            throw new InvalidSourceConfigException("mapping.attribute is required");
        }
        if (claim == null || claim.isBlank()) {
            throw new InvalidSourceConfigException("mapping.claim is required");
        }
        label = label == null || label.isBlank() ? null : label;
    }

    public boolean shown() {
        return label != null;
    }
}
