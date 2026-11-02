package com.stocklab.domain.plain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * M0(무제어)·M3(비관적 락)용 상품 엔티티.
 * 의도하지 않은 낙관적 버전 검사가 들어가지 않도록 @Version을 선언하지 않는다. (계획서 4.3)
 * product 테이블의 version 컬럼은 매핑하지 않으므로 이 엔티티로는 값이 바뀌지 않는다.
 */
@Entity(name = "PlainProduct")
@Table(name = "product")
public class PlainProduct {

    @Id
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private int stock;

    protected PlainProduct() {
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

    /** 읽은 값에서 1을 뺀다. 저장은 JPA 변경 감지로 커밋 시점에 이뤄진다. */
    public void decreaseStock() {
        this.stock -= 1;
    }
}
