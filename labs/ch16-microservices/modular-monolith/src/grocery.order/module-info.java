module grocery.order {
    requires grocery.catalog;                 // synchronous API use: allowed, explicit
    requires grocery.shared;                  // publishes events; does NOT require grocery.inventory
    exports com.grocery.order.api;
}
