package com.stocklab.domain.versioned;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VersionedProductRepository extends JpaRepository<VersionedProduct, Long> {
}
