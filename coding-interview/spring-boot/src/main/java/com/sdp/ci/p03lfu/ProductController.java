package com.sdp.ci.p03lfu;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductController {
    private final ProductService products;

    public ProductController(ProductService products) { this.products = products; }

    @GetMapping("/api/v1/products/{id}")
    public ProductService.Product get(@PathVariable String id) { return products.find(id); }
}
