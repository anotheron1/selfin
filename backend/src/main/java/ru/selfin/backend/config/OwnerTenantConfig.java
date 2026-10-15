package ru.selfin.backend.config;

import org.hibernate.cfg.MultiTenancySettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.selfin.backend.service.OwnerContext;

import java.util.UUID;

/**
 * Второй слой изоляции (ANO-147): арендатор сессии Hibernate — владелец из {@link OwnerContext}. По полю
 * {@code @TenantId} каждой сущности Hibernate добавляет {@code user_id = владелец} ко всем её запросам, загрузке
 * по id и ленивым связям, а при вставке ставит владельца сам. Держит изоляцию и там, где RLS не действует.
 *
 * <p>Арендатор фиксируется при открытии сессии. В веб-запросе её открывает open-in-view в {@code DispatcherServlet}
 * — после {@link OwnerFilter}, который владельца уже поставил.
 *
 * <p>Без владельца — {@link #NOBODY}, а не {@code null}: сессию без владельца открывают и Spring Data на старте,
 * проверяя запросы, и open-in-view на запросах без входа — {@code /version}, {@code /error}. На {@code null}
 * Hibernate бросает исключение, и приложение не стартовало бы.
 *
 * <p>Спека: docs/superpowers/specs/2026-10-15-tenant-id-second-layer-design.md
 */
@Configuration
class OwnerTenantConfig {

    /** Никто: как у RLS — ни строки, а запись упрётся во внешний ключ на {@code users}. */
    static final UUID NOBODY = new UUID(0, 0);

    @Bean
    HibernatePropertiesCustomizer ownerTenant() {
        return properties -> properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER,
                new CurrentTenantIdentifierResolver<UUID>() {
                    @Override
                    public UUID resolveCurrentTenantIdentifier() {
                        UUID owner = OwnerContext.get();
                        return owner != null ? owner : NOBODY;
                    }

                    @Override
                    public boolean validateExistingCurrentSessions() {
                        return true;
                    }
                });
    }
}
