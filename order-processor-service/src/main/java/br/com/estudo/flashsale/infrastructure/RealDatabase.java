package br.com.estudo.flashsale.infrastructure;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.sql.*;
import java.nio.charset.StandardCharsets;

/** O pool inativo nunca é acessado no modo mock. */
@ApplicationScoped
public class RealDatabase {
    @Inject Instance<AgroalDataSource> pool;
    private volatile boolean initialized;

    public Connection connection() throws SQLException {
        return pool.get().getConnection();
    }

    public synchronized void initialize() {
        if (initialized) return;
        try (var connection = connection();
             var input = getClass().getResourceAsStream("/db/schema.sql")) {
            if (input == null) throw new IllegalStateException("Schema ausente");
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            connection.commit();
            initialized = true;
        } catch (Exception error) {
            throw new IllegalStateException("Não foi possível inicializar o banco", error);
        }
    }

    public boolean ready() {
        try (var connection = connection()) {
            return initialized && connection.isValid(2);
        } catch (Exception unavailable) { return false; }
    }
}

