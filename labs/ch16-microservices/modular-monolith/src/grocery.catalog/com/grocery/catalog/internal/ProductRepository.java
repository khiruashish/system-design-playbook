package com.grocery.catalog.internal;

import java.util.Map;

/** Internal: tables, JPA entities, caches. Invisible outside the module. */
public final class ProductRepository {
    public record Product(String sku, String name, long pricePaise, String supplierCostPaise) { }
    private final Map<String, Product> products = Map.of("ATTA-5KG", new Product("ATTA-5KG", "Whole wheat atta 5 kg", 32000, "27150"));
    public Product find(String sku) { return products.get(sku); }
}
