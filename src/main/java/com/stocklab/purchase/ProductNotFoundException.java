package com.stocklab.purchase;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(long productId) {
        super("product not found: " + productId);
    }
}
