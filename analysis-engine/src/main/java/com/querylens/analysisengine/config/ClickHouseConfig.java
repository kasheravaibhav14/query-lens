package com.querylens.analysisengine.config;

import com.clickhouse.jdbc.ClickHouseDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Properties;

// Activate by setting query-lens.clickhouse.enabled=true
// URL format: jdbc:clickhouse://host:8123/database
@Configuration
@ConditionalOnProperty(name = "query-lens.clickhouse.enabled", havingValue = "true")
public class ClickHouseConfig {

    @Bean
    public DataSource clickHouseDataSource(
            @Value("${query-lens.clickhouse.url}") String url,
            @Value("${query-lens.clickhouse.username:default}") String username,
            @Value("${query-lens.clickhouse.password:}") String password) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        return new ClickHouseDataSource(url, props);
    }

    @Bean
    public JdbcTemplate clickHouseJdbcTemplate(DataSource clickHouseDataSource) {
        return new JdbcTemplate(clickHouseDataSource);
    }
}
