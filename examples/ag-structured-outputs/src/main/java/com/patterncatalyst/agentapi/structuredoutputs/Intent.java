package com.patterncatalyst.agentapi.structuredoutputs;

/**
 * The allowed semantic classes a customer message can fall into. This is the finite set every
 * parsed {@link ExtractedIntent} must land in: a small, closed vocabulary is what makes "shape and
 * class" something a live run can actually check without asserting on the model's own wording.
 */
public enum Intent {
    STATUS_INQUIRY,
    REFUND_REQUEST,
    CANCEL_REQUEST,
    COMPLAINT,
    OTHER
}
