package com.stocklab.domain.plain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PlainProductRepository extends JpaRepository<PlainProduct, Long> {

    /**
     * M3: 쓰기 락을 획득한 조회. MySQL에서는 SELECT ... FOR UPDATE로 실행되며
     * 락은 커밋 또는 롤백까지 유지된다. 대기 상한은 JPA 힌트가 아니라
     * MySQL innodb_lock_wait_timeout(3초)으로만 적용한다. (계획서 4.4)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PlainProduct p where p.id = :id")
    Optional<PlainProduct> findByIdForUpdate(@Param("id") Long id);
}
