package com.isums.contractservice.configurations;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

@Configuration
public class FlywayMigrationConfig implements EnvironmentAware {
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Bean(initMethod = "migrate")
    @ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
    public Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(environment.getProperty("spring.flyway.locations", "classpath:db/migration"))
                .baselineOnMigrate(environment.getProperty("spring.flyway.baseline-on-migrate", Boolean.class, true))
                .baselineVersion(environment.getProperty("spring.flyway.baseline-version", "1"))
                .validateOnMigrate(environment.getProperty("spring.flyway.validate-on-migrate", Boolean.class, true))
                .load();
    }

    @Bean
    public static BeanFactoryPostProcessor entityManagerFactoryDependsOnFlyway() {
        return beanFactory -> {
            if (!beanFactory.containsBeanDefinition("entityManagerFactory")
                    || !beanFactory.containsBeanDefinition("flyway")) {
                return;
            }
            BeanDefinition definition = beanFactory.getBeanDefinition("entityManagerFactory");
            definition.setDependsOn(appendDependsOn(definition.getDependsOn(), "flyway"));
        };
    }

    private static String[] appendDependsOn(String[] existing, String dependency) {
        if (existing == null || existing.length == 0) {
            return new String[]{dependency};
        }
        for (String item : existing) {
            if (dependency.equals(item)) return existing;
        }
        String[] next = new String[existing.length + 1];
        System.arraycopy(existing, 0, next, 0, existing.length);
        next[existing.length] = dependency;
        return next;
    }
}
