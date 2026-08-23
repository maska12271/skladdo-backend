package com.example.skladdo.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Supplies the Postgres and LocalStack (S3) instances the test suite runs against.
 *
 * <p>The Postgres container is a Spring bean so it's tied to the (cached) {@code ApplicationContext}
 * lifecycle: every {@code @SpringBootTest} class that imports this config and shares the same context
 * configuration reuses the one running container instead of each starting its own. There's no equivalent
 * {@code @ServiceConnection} support for S3 without Spring Cloud AWS (not a dependency here), so
 * {@link #LOCALSTACK} is instead a plain static field, started once per JVM: its connection details need
 * to reach the Spring {@code Environment} via {@code @DynamicPropertySource} - see the test classes that
 * reference it - which runs before any {@code ApplicationContext} (and so any {@code @Bean}) exists.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3"))
                    .withServices(LocalStackContainer.Service.S3);

    static {
        LOCALSTACK.start();
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));
    }
}
