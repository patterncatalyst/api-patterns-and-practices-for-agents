package com.patterncatalyst.agentapi.whyagentapis;

/**
 * The system prompt and the task, held once so the good and bad contract runs differ only in
 * which tools each {@link GoodContractAssistant} / {@link BadContractAssistant} can call.
 */
final class Prompts {

    static final String SYSTEM_MESSAGE = """
            You are an operations assistant for a shipping carrier. Use the tools you are
            given to complete the requested task. Call a tool for every lookup or change;
            never guess a value a tool could tell you.
            """;

    static final String CANCEL_AND_REFUND_TASK =
            "Cancel order O-1007 and refund the customer. Reason: the customer changed their mind.";

    private Prompts() {
    }
}
