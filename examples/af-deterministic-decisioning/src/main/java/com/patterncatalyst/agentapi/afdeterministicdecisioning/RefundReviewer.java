package com.patterncatalyst.agentapi.afdeterministicdecisioning;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;

/**
 * The deterministic gate this appendix is about, rebuilt as Apache Drools (plain library, no
 * Kogito, no DMN) over the exact same refund-review decision ch26-decision-rule-review reaches
 * with a hand-rolled JSON-rule evaluator: the same proposal, judged against the same committed
 * rule, reaches the same verdict every time, independent of the model that proposed the refund.
 *
 * <p>{@code review.rule.resource} (demo code, the Drools analogue of ch26's
 * {@code review.rule.path}) names the classpath DRL resource read once at startup. The committed
 * ruleset is compiled with {@link KieHelper} against an explicit
 * {@link org.kie.api.builder.KieServices#getResources() classpath resource}, never kmodule
 * classpath scanning: a fast-jar's classloader layout hides resources from that scan. Pointed at
 * {@code rules/refund-review-weakened.drl} (the same ruleset with the amount-ceiling rule
 * removed), the exact same over-limit proposal is approved instead of rejected, proving that rule
 * is exactly what caught it.
 */
@ApplicationScoped
public class RefundReviewer {

    private static final Logger LOG = Logger.getLogger(RefundReviewer.class);

    @ConfigProperty(name = "review.rule.resource")
    String ruleResource;

    private KieBase kieBase;

    @PostConstruct
    void loadRules() {
        kieBase = loadKieBase(ruleResource);
        LOG.infof("decision rule compiled: resource=%s", ruleResource);
    }

    public Verdict evaluate(RefundProposal proposal) {
        return evaluate(kieBase, proposal);
    }

    /**
     * Compiles the named classpath DRL resource into a {@link KieBase} with {@link KieHelper}.
     * Package-visible so the unit-level weakened-rule test can load an arbitrary rule resource
     * without going through CDI or a second configured bean.
     */
    static KieBase loadKieBase(String resourcePath) {
        KieHelper helper = new KieHelper();
        helper.addResource(KieServices.get().getResources().newClassPathResource(resourcePath), ResourceType.DRL);
        return helper.build();
    }

    /**
     * Fires the named {@link KieBase}'s rules against one proposal and reads the verdict back off
     * the fact Drools updated. Package-visible for the same reason as {@link #loadKieBase}.
     */
    static Verdict evaluate(KieBase kieBase, RefundProposal proposal) {
        KieSession session = kieBase.newKieSession();
        try {
            Verdict verdict = new Verdict();
            session.insert(proposal);
            session.insert(verdict);
            session.fireAllRules();
            return verdict;
        } finally {
            session.dispose();
        }
    }
}
