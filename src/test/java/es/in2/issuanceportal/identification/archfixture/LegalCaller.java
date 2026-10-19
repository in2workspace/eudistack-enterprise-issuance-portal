package es.in2.issuanceportal.identification.archfixture;

import es.in2.issuanceportal.journey.api.archfixture.PublishedPort;

/** Architecture test fixture: uses another module through its published package. */
public class LegalCaller {

    public void call(PublishedPort journey) {
        journey.doSomething();
    }
}
