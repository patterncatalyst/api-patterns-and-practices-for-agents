package com.patterncatalyst.agentapi.whyagentapis;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * Drives the same task against the bad contract: three granular tools, so the agent must look
 * the order up before it can act on it. Same system message and model as
 * {@link GoodContractAssistant}; only the registered tools differ.
 */
@RegisterAiService(tools = BadOrderOpsTools.class)
public interface BadContractAssistant {

    @SystemMessage(Prompts.SYSTEM_MESSAGE)
    Result<String> run(String task);
}
