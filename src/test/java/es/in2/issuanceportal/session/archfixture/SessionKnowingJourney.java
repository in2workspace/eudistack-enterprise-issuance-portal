package es.in2.issuanceportal.session.archfixture;

import es.in2.issuanceportal.journey.api.archfixture.PublishedPort;

/** Architecture test fixture: a session class that depends on a module, which the session must never do. */
public class SessionKnowingJourney {

    public void call(PublishedPort journey) {
        journey.doSomething();
    }
}
