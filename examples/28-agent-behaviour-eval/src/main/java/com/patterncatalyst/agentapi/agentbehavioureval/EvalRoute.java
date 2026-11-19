package com.patterncatalyst.agentapi.agentbehavioureval;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Three Camel platform-http triggers, the same convention 26-decision-rule-review's {@code
 * ReviewRoute} uses. No REST framework.
 *
 * <p>{@code POST /eval} scores whatever JSON-lines transcript text is posted as the body: the
 * committed fixtures, or a caller's own mutated copy of one, the shape verify.sh's mutation
 * witness uses.
 *
 * <p>{@code GET /eval/run/good} is the MODEL=off reconciliation point: {@code
 * agent.eval.model-source} (demo code, like 24-langchain4j-programmatic-consumer's {@code
 * agent.consumer.model-source}) picks the path. {@code fixture}, the default everywhere and forced
 * again in the {@code %test} profile, replays the committed {@code good-run.jsonl}, so {@code mvn
 * -B verify} and the default verify.sh lane never need Ollama. {@code ollama}, set only by
 * verify.sh's LIVE=1 lane, drives the real model through {@link LiveRunner} instead and scores
 * whatever it actually did.
 *
 * <p>{@code GET /eval/run/regressed} always replays the committed {@code regressed-run.jsonl}: a
 * regression is a hand-recorded transcript standing in for a bad run, never something the live
 * model is asked to reproduce.
 */
@ApplicationScoped
public class EvalRoute extends RouteBuilder {

    @Inject
    EvalBean evalBean;

    @Inject
    LiveRunner liveRunner;

    @ConfigProperty(name = "agent.eval.model-source", defaultValue = "fixture")
    String modelSource;

    @Override
    public void configure() {
        onException(IllegalArgumentException.class)
                .handled(true)
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
                .setHeader(Exchange.CONTENT_TYPE, constant("text/plain"))
                .setBody(simple("rejected: ${exception.message}"));

        from("platform-http:/eval?httpMethodRestrict=POST")
                .routeId("evaluate-submitted-transcript")
                .process(exchange -> {
                    String body = exchange.getIn().getBody(String.class);
                    Transcript transcript = TranscriptReader.read(body);
                    exchange.getMessage().setBody(evalBean.evaluateAndRecord(transcript));
                })
                .marshal().json();

        from("platform-http:/eval/run/good?httpMethodRestrict=GET")
                .routeId("evaluate-good-run")
                .process(exchange -> {
                    Transcript transcript = "ollama".equals(modelSource)
                            ? liveRunner.run()
                            : FixtureTranscripts.read("good-run.jsonl");
                    exchange.getMessage().setBody(evalBean.evaluateAndRecord(transcript));
                })
                .marshal().json();

        from("platform-http:/eval/run/regressed?httpMethodRestrict=GET")
                .routeId("evaluate-regressed-run")
                .process(exchange -> exchange.getMessage().setBody(
                        evalBean.evaluateAndRecord(FixtureTranscripts.read("regressed-run.jsonl"))))
                .marshal().json();
    }
}
