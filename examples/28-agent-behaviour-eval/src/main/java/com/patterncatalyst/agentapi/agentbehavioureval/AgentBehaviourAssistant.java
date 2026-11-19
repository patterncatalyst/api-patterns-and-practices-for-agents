package com.patterncatalyst.agentapi.agentbehavioureval;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * The LIVE path only (see README): a local langchain4j AiService backed directly by {@code
 * quarkus-langchain4j-ollama}, never the Camel {@code langchain4j-agent} component. {@code mvn -B
 * verify} never reaches this interface; {@code agent.eval.model-source=fixture}, forced in the
 * {@code %test} profile, routes scoring to the committed transcripts instead (see {@link
 * EvalRoute}).
 *
 * <p>The method returns {@link Result}, not a bare {@code String}, so {@link LiveRunner} can read
 * {@link Result#toolExecutions()}: the captured tool-call sequence this chapter's evaluator scores,
 * never the model's own prose.
 */
@RegisterAiService(tools = CancelAndRefundTools.class)
public interface AgentBehaviourAssistant {

    @SystemMessage(Prompts.SYSTEM_MESSAGE)
    Result<String> run(String task);
}
