package com.patterncatalyst.agentapi.afdeterministicdecisioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;

/**
 * De-risking spike (see _docs/af-deterministic-decisioning.md and the module README): loads a
 * trivial DRL from an explicit classpath resource (not kmodule classpath scanning, which the
 * fast-jar classloader layout hides) with {@link KieHelper}, builds a {@link KieBase}, fires one
 * rule, and reads the result back off the fact. Proves runtime DRL compilation with
 * org.drools:drools-engine:10.2.0 and org.drools:drools-mvel:10.2.0 works on this JDK before any
 * of the real refund-review rules are written. Not part of the shipped example; the real fact
 * model and rule set live in {@code rules/refund-review.drl} and are exercised by
 * {@code RefundReviewTest}.
 */
class DroolsSpikeTest {

    @Test
    void aTrivialDrlRuleCompilesAndFiresAtRuntime() {
        KieHelper helper = new KieHelper();
        helper.addResource(
                org.kie.api.KieServices.get().getResources().newClassPathResource("rules/spike.drl"),
                org.kie.api.io.ResourceType.DRL);

        KieBase kieBase = helper.build();
        KieSession session = kieBase.newKieSession();
        try {
            SpikeFact fact = new SpikeFact();
            session.insert(fact);
            int fired = session.fireAllRules();

            assertEquals(1, fired, "exactly one rule should have fired");
            assertTrue(fact.isFired(), "the rule's consequence should have flipped the fact");
        } finally {
            session.dispose();
        }
    }
}
