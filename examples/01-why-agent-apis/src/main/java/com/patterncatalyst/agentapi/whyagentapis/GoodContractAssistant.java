package com.patterncatalyst.agentapi.whyagentapis;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * Drives the task against the good contract: one intent tool, so the task always resolves in a
 * single tool call. A local langchain4j AiService backed directly by quarkus-langchain4j-ollama
 * (not the Camel langchain4j-agent component).
 */
@RegisterAiService(tools = GoodOrderOpsTools.class)
public interface GoodContractAssistant {

    @SystemMessage(Prompts.SYSTEM_MESSAGE)
    Result<String> run(String task);
}
