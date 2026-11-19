package com.patterncatalyst.agentapi.decisionrulereview;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * The trigger: {@code POST /review} with a recorded agent run (a refund proposal plus the order's
 * own facts) as the JSON body, the same Camel platform-http convention ch24's
 * {@code ConsumeOrderStatusRoute} uses. No REST framework. The body is a recorded proposal, either
 * one of the committed fixtures under {@code src/main/resources/fixtures/} (replayed by
 * {@code verify.sh} and the Quarkus test) or any caller's own JSON of the same shape: either way,
 * {@link DecisionRuleReviewer}'s verdict is what gets recorded, never a model's reply, since no
 * model is ever called on this path.
 */
@ApplicationScoped
public class ReviewRoute extends RouteBuilder {

    @Inject
    ReviewBean reviewBean;

    @Override
    public void configure() {
        onException(IllegalArgumentException.class)
                .handled(true)
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
                .setHeader(Exchange.CONTENT_TYPE, constant("text/plain"))
                .setBody(simple("rejected: ${exception.message}"));

        from("platform-http:/review?httpMethodRestrict=POST")
                .routeId("review-refund-proposal")
                .unmarshal().json(RefundProposalRun.class)
                .process(exchange -> {
                    RefundProposalRun run = exchange.getIn().getBody(RefundProposalRun.class);
                    exchange.getMessage().setBody(reviewBean.review(run));
                })
                .marshal().json();
    }
}
