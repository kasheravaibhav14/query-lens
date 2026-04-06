package com.querylens.analysisengine.clickhouse;

import com.querylens.analysisengine.model.MongoQueryLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
@ConditionalOnProperty(name = "query-lens.clickhouse.enabled", havingValue = "true")
public class MongoLogEventRepository {

    private static final String INSERT_SQL = """
            INSERT INTO mongo_log_events
            (tenant_id, timestamp, received_at, service, namespace, duration_millis,
             plan_summary, keys_examined, docs_examined, nreturned, command, db_node)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public MongoLogEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertBatch(List<MongoQueryLog> batch) {
        jdbcTemplate.batchUpdate(INSERT_SQL, batch, batch.size(), (ps, log) -> {
            ps.setObject(1, log.tenantId());
            ps.setTimestamp(2, Timestamp.from(log.timestamp()));
            ps.setTimestamp(3, Timestamp.from(log.receivedAt()));
            ps.setString(4, log.service());
            ps.setString(5, log.namespace());
            ps.setLong(6, log.durationMillis());
            ps.setString(7, log.planSummary());
            ps.setInt(8, log.keysExamined());
            ps.setInt(9, log.docsExamined());
            ps.setInt(10, log.nreturned());
            ps.setString(11, log.command());
            ps.setString(12, log.dbNode());
        });
    }
}
