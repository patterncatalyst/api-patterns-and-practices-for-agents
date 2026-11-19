package com.patterncatalyst.agentapi.domainsplitprogressivediscovery;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * The full order-to-delivery operation set this example's two servers could expose: 20 entries,
 * 10 per domain, only 4 of them wired up as live {@code @Tool} methods ({@link OrderDomainTools},
 * {@link ShippingDomainTools}). The other 16 are catalog-only: named, described and fetchable by
 * {@link CatalogResource}, discoverable by {@link CatalogSearchTools#searchTools}, but never a
 * live tool a client's own {@code tools/list} advertises. Every operation stays inside the Order
 * or Shipment entity and the orders/shipping schemas; none reaches into inventory, payment or
 * notification, which this example's two servers do not own.
 */
@ApplicationScoped
public class OperationCatalog {

    private static final List<CatalogEntry> ENTRIES = List.of(
            new CatalogEntry("order_status_get", "order", "tool", "order_status_get",
                    "Get this order's own lifecycle status, customer, item and amount from the order schema only."),
            new CatalogEntry("order_cancel_eligibility_get", "order", "tool", "order_cancel_eligibility_get",
                    "Get whether this order can still be cancelled right now, derived from its current lifecycle status."),
            new CatalogEntry("order_search_get", "order", "catalog", null,
                    "Find orders by lifecycle stage and item SKU in one call instead of paging through every row."),
            new CatalogEntry("order_batch_get", "order", "catalog", null,
                    "Get several orders by id in a single call, separating hits from misses."),
            new CatalogEntry("order_count_get", "order", "catalog", null,
                    "Count orders matching a filter without returning any order records."),
            new CatalogEntry("order_place_post", "order", "catalog", null,
                    "Place a new order for a customer and an item SKU."),
            new CatalogEntry("order_cancel_post", "order", "catalog", null,
                    "Cancel an order that has not shipped yet."),
            new CatalogEntry("order_refund_post", "order", "catalog", null,
                    "Refund the payment tied to a cancelled order."),
            new CatalogEntry("order_history_get", "order", "catalog", null,
                    "List every lifecycle change this order has gone through, oldest first."),
            new CatalogEntry("order_customer_orders_get", "order", "catalog", null,
                    "List every order a given customer has ever placed."),

            new CatalogEntry("shipping_track_get", "shipping", "tool", "shipping_track_get",
                    "Track this shipment: its carrier, tracking number and lifecycle status, from the shipping schema only."),
            new CatalogEntry("shipping_eta_get", "shipping", "tool", "shipping_eta_get",
                    "Get a plain-language estimated delivery window computed from the shipment's lifecycle status."),
            new CatalogEntry("shipping_schedule_post", "shipping", "catalog", null,
                    "Schedule a shipment for an order that has been paid."),
            new CatalogEntry("shipping_reschedule_post", "shipping", "catalog", null,
                    "Change the scheduled pickup window for a shipment that has not shipped yet."),
            new CatalogEntry("shipping_cancel_post", "shipping", "catalog", null,
                    "Cancel a shipment that has not yet moved to in-transit."),
            new CatalogEntry("shipping_carrier_switch_post", "shipping", "catalog", null,
                    "Switch a pending shipment from one carrier to the other before pickup."),
            new CatalogEntry("shipping_history_get", "shipping", "catalog", null,
                    "List every lifecycle change this shipment has gone through, oldest first."),
            new CatalogEntry("shipping_label_get", "shipping", "catalog", null,
                    "Fetch the reference to this shipment's printable label document."),
            new CatalogEntry("shipping_proof_of_delivery_get", "shipping", "catalog", null,
                    "Fetch the reference to this shipment's proof-of-delivery document."),
            new CatalogEntry("shipping_carrier_info_get", "shipping", "catalog", null,
                    "Get service-level information for a named carrier, FASTLINE road or AIRCO air."));

    public List<CatalogEntry> all() {
        return ENTRIES;
    }

    public List<CatalogEntry> byDomain(String domain) {
        return ENTRIES.stream().filter(e -> e.domain().equals(domain)).toList();
    }

    public Optional<CatalogEntry> find(String id) {
        return ENTRIES.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    public List<CatalogEntry> search(String query) {
        return ENTRIES.stream().filter(e -> e.matches(query)).toList();
    }
}
