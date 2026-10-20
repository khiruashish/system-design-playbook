package com.grocery.shared.events;

/** A published event is part of the module's API, so it lives in a shared, exported package. */
public record OrderPlaced(String orderId, String sku, int qty) { }
