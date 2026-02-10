package co.fanki.domainmcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.spi.JdbiPlugin;
import org.jdbi.v3.jackson2.Jackson2Config;
import org.jdbi.v3.jackson2.Jackson2Plugin;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;

/**
 * Database configuration for JDBI3 with PostgreSQL.
 *
 * <p>Configures JDBI with PostgreSQL plugin, Jackson2 for JSON handling,
 * and SqlObject plugin for declarative SQL.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Configuration
public class DatabaseConfiguration {

    /**
     * Creates and configures the JDBI instance.
     *
     * @param dataSource the data source to use
     * @param plugins list of JDBI plugins to install
     * @param rowMappers list of row mappers to register
     * @param objectMapper Jackson object mapper for JSON serialization
     * @return configured JDBI instance
     */
    @Bean
    public Jdbi jdbi(
            final DataSource dataSource,
            final List<JdbiPlugin> plugins,
            final List<RowMapper<?>> rowMappers,
            final ObjectMapper objectMapper) {

        final Jdbi jdbi = Jdbi.create(dataSource);

        plugins.forEach(jdbi::installPlugin);
        rowMappers.forEach(jdbi::registerRowMapper);

        jdbi.getConfig(Jackson2Config.class).setMapper(objectMapper);

        return jdbi;
    }

    /**
     * Provides the PostgreSQL plugin for JDBI.
     *
     * @return PostgreSQL plugin
     */
    @Bean
    public JdbiPlugin postgresPlugin() {
        return new PostgresPlugin();
    }

    /**
     * Provides the SqlObject plugin for JDBI.
     *
     * @return SqlObject plugin
     */
    @Bean
    public JdbiPlugin sqlObjectPlugin() {
        return new SqlObjectPlugin();
    }

    /**
     * Provides the Jackson2 plugin for JDBI JSON handling.
     *
     * @return Jackson2 plugin
     */
    @Bean
    public JdbiPlugin jackson2Plugin() {
        return new Jackson2Plugin();
    }

}
