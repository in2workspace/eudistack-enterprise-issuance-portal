package es.in2.issuanceportal.journey.application.archfixture;

import es.in2.issuanceportal.journey.infrastructure.archfixture.SomeAdapter;

/** Architecture test fixture: an application service that reaches into infrastructure. */
public class ApplicationUsingInfrastructure {

    public void call(SomeAdapter adapter) {
        adapter.doSomething();
    }
}
