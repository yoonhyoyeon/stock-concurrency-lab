package com.stocklab.support;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 애플리케이션 트랜잭션과 별개인 커넥션으로 DB를 직접 조회·조작하는 테스트 보조 도구.
 * DataSource에서 직접 커넥션을 받으므로 스프링 트랜잭션에 참여하지 않는다
 * (조회 직후 훅 안에서 호출해도 서비스 트랜잭션과 다른 트랜잭션이 된다).
 */
public class DbProbe {

    private final DataSource dataSource;

    public DbProbe(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public int stock(long productId) {
        return queryLong("SELECT stock FROM product WHERE id = ?", productId).intValue();
    }

    public long version(long productId) {
        return queryLong("SELECT version FROM product WHERE id = ?", productId);
    }

    public long purchaseLogCount() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM purchase_log");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 다른 트랜잭션이 먼저 커밋한 상황을 만든다: 버전만 1 올리고 즉시 커밋(autocommit). */
    public void bumpVersionInOtherTransaction(long productId) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            try (PreparedStatement ps = c.prepareStatement("UPDATE product SET version = version + 1 WHERE id = ?")) {
                ps.setLong(1, productId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 별도 트랜잭션에서 SELECT ... FOR UPDATE로 행 락을 잡는다. 반환된 객체로 커밋·롤백할 때까지 유지된다. */
    public RowLock lockRow(long productId) {
        try {
            Connection c = dataSource.getConnection();
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT stock FROM product WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, productId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                }
            }
            return new RowLock(c, productId);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Long queryLong(String sql, long productId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("no row for product " + productId);
                }
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class RowLock implements AutoCloseable {

        private final Connection connection;
        private final long productId;

        private RowLock(Connection connection, long productId) {
            this.connection = connection;
            this.productId = productId;
        }

        /** 락을 보유한 채 재고를 1 줄인다(아직 커밋하지 않음). */
        public void decrementStock() {
            try (PreparedStatement ps = connection.prepareStatement("UPDATE product SET stock = stock - 1 WHERE id = ?")) {
                ps.setLong(1, productId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        public void commit() {
            try {
                connection.commit();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() {
            try {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
                connection.close();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
