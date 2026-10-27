package es.in2.issuanceportal.identification.archfixture;

import es.in2.issuanceportal.journey.archfixture.JourneyInternal;

/** Architecture test fixture: breaks the module boundary on purpose. */
public class IllegalCaller {

    public void call(JourneyInternal journey) {
        journey.doSomething();
    }
}
