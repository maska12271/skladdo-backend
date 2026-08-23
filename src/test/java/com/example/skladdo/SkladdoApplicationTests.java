package com.example.skladdo;

import com.example.skladdo.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Smoke test: the whole application context wires up.
 *
 * <p>Runs on the {@code test} profile (Testcontainers Postgres, no scheduler, no demo seed). Sharing the
 * profile and Testcontainers config with the integration tests means it reuses their already-built context
 * instead of paying for a second one.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SkladdoApplicationTests {

	/** Points app.storage.s3.* at TestcontainersConfiguration.LOCALSTACK (see its Javadoc for why). */
	@DynamicPropertySource
	static void s3Properties(DynamicPropertyRegistry registry) {
		registry.add("app.storage.s3.endpoint-override",
				() -> TestcontainersConfiguration.LOCALSTACK.getEndpoint().toString());
		registry.add("app.storage.s3.region", TestcontainersConfiguration.LOCALSTACK::getRegion);
		registry.add("app.storage.s3.access-key", TestcontainersConfiguration.LOCALSTACK::getAccessKey);
		registry.add("app.storage.s3.secret-key", TestcontainersConfiguration.LOCALSTACK::getSecretKey);
		registry.add("app.storage.s3.path-style-access", () -> "true");
	}

	@Test
	void contextLoads() {
	}

}
