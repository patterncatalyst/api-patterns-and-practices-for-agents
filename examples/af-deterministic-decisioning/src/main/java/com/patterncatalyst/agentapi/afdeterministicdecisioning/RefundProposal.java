package com.patterncatalyst.agentapi.afdeterministicdecisioning;

import java.math.BigDecimal;

/**
 * The Drools fact an agent's refund proposal becomes: the proposal's own fields
 * ({@code refundAmount}, {@code reason}) plus the order facts {@code refund-review.drl} needs to
 * judge it ({@code orderStatus}, {@code orderPaidAmount}), the same shape ch26's
 * {@code RefundProposalRun} carries for its hand-rolled JSON-rule evaluator. A plain mutable
 * JavaBean, not a record: Drools inserts this into working memory and reads it back through its
 * getters, which it compiles constraints against at rule-build time.
 *
 * <p>{@code orderStatus} and {@code reason} are plain strings here, not the domain model's
 * {@code OrderStatus} enum: the committed DRL compares them against literal string sets
 * ({@code "PAID"}, {@code "SHIPPED"}, ...), so a caller can name the keyword a clause checks for
 * without a second, Drools-aware mapping of the shared enum's values.
 */
public class RefundProposal {

    private String orderId;
    private String customerId;
    private String orderStatus;
    private BigDecimal orderPaidAmount;
    private BigDecimal refundAmount;
    private String reason;

    public RefundProposal() {
    }

    public RefundProposal(String orderId, String customerId, String orderStatus,
            BigDecimal orderPaidAmount, BigDecimal refundAmount, String reason) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.orderStatus = orderStatus;
        this.orderPaidAmount = orderPaidAmount;
        this.refundAmount = refundAmount;
        this.reason = reason;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(String orderStatus) {
        this.orderStatus = orderStatus;
    }

    public BigDecimal getOrderPaidAmount() {
        return orderPaidAmount;
    }

    public void setOrderPaidAmount(BigDecimal orderPaidAmount) {
        this.orderPaidAmount = orderPaidAmount;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public void setRefundAmount(BigDecimal refundAmount) {
        this.refundAmount = refundAmount;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
