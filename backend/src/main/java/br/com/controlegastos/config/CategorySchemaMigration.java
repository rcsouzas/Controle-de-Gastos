package br.com.controlegastos.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CategorySchemaMigration implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    public CategorySchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("ALTER TABLE transactions ALTER COLUMN category SET DATA TYPE VARCHAR(24)");
        jdbcTemplate.execute("ALTER TABLE recurring_transactions ALTER COLUMN category SET DATA TYPE VARCHAR(24)");
    }
}
