package com.grocery.catalog.api;

import com.grocery.catalog.internal.ProductRepository;

public final class CatalogApi {
    private final ProductRepository repo = new ProductRepository();
    public record ProductView(String sku, String name, long pricePaise) { }
    public ProductView product(String sku) { var p = repo.find(sku); return new ProductView(p.sku(), p.name(), p.pricePaise()); }
}
