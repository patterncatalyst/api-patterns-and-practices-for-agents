package com.patterncatalyst.agentapi.mcpfacade;

/** The lean, instructive JSON an agent caller gets back from a tool call: a decimal amount and a
 * readable status, never the proto3 wire shape (int64 cents, an enum ordinal). */
record PaymentView(String orderId, String amount, String status) {
}
