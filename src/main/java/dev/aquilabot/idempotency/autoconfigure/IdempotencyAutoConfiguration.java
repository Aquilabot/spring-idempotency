package dev.aquilabot.idempotency.autoconfigure;

import dev.aquilabot.idempotency.IdempotencyProperties;
import dev.aquilabot.idempotency.IdempotencyScopeResolver;
import dev.aquilabot.idempotency.store.IdempotencyStore;
import dev.aquilabot.idempotency.store.InMemoryIdempotencyStore;
import dev.aquilabot.idempotency.store.JdbcIdempotencyStore;
import dev.aquilabot.idempotency.web.IdempotencyFilter;
import java.time.Clock;
import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Registers the {@link IdempotencyFilter} and a store. Define your own {@link IdempotencyStore},
 * {@link IdempotencyScopeResolver} or {@link Clock} bean to replace the defaults.
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnBooleanProperty(name = "idempotency.enabled", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    private static final Log log = LogFactory.getLog(IdempotencyAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    IdempotencyScopeResolver idempotencyScopeResolver() {
        return IdempotencyScopeResolver.principalName();
    }

    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    @ConditionalOnProperty(name = "idempotency.store", havingValue = "memory", matchIfMissing = true)
    InMemoryIdempotencyStore inMemoryIdempotencyStore() {
        log.info("Using the in-memory idempotency store. Keys are not shared between instances."
                + " Set idempotency.store=jdbc for a shared store.");
        return new InMemoryIdempotencyStore();
    }

    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(IdempotencyStore store,
            IdempotencyScopeResolver scopeResolver, IdempotencyProperties properties, ObjectProvider<Clock> clock,
            ObjectProvider<RequestMappingHandlerMapping> handlerMappings) {
        IdempotencyFilter filter = new IdempotencyFilter(store, scopeResolver, properties,
                clock.getIfUnique(Clock::systemUTC), () -> handlerMappings.orderedStream().toList());
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("idempotencyFilter");
        registration.setOrder(properties.getFilterOrder());
        return registration;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JdbcTemplate.class)
    @ConditionalOnProperty(name = "idempotency.store", havingValue = "jdbc")
    static class JdbcStoreConfiguration {

        @Bean
        @ConditionalOnMissingBean(IdempotencyStore.class)
        @ConditionalOnSingleCandidate(DataSource.class)
        JdbcIdempotencyStore jdbcIdempotencyStore(DataSource dataSource, IdempotencyProperties properties) {
            JdbcIdempotencyStore store = new JdbcIdempotencyStore(dataSource, properties.getJdbc().getTableName());
            if (properties.getJdbc().isInitializeSchema()) {
                store.createSchema();
            }
            return store;
        }
    }
}
