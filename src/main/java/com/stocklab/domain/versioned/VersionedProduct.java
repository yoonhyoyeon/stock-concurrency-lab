package com.stocklab.domain.versioned;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * M1·M2(낙관적 락)용 상품 엔티티. @Version으로 버전 검사 UPDATE를 수행한다.
 * 예상 SQL: UPDATE product SET name=?, stock=?, version=? WHERE id=? AND version=?
 */
@Entity(name = "VersionedProduct")
@Table(name = "product")
public class VersionedProduct {

    @Id
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private int stock;

    @Version
    @Column(nullable = false)
    private Long version;

    protected VersionedProduct() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getStock() {
        return stock;
    }

    public Long getVersion() {
        return version;
    }

    public void decreaseStock() {
        this.stock -= 1;
    }
}
