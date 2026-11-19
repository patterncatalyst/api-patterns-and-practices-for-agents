package com.patterncatalyst.agentapi.agentbehavioureval;

/**
 * The system prompt and the one task the LIVE path drives, held once so {@link LiveRunner} and
 * the committed fixtures that stand in for its recorded output agree on what task was run.
 */
final class Prompts {

    static final String SYSTEM_MESSAGE = """
            You are an operations assistant for a shipping carrier. Use the tools you are
            given to complete the requested task. Call a tool for every lookup or change;
            never guess a value a tool could tell you.
            """;

    static final String CANCEL_AND_REFUND_TASK =
            "Cancel order O-28001 and refund the customer. Reason: the item arrived damaged.";

    private Prompts() {
    }
}
