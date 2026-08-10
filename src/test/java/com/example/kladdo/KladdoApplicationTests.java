package com.example.kladdo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test: the whole application context wires up.
 *
 * <p>Runs on the {@code test} profile (in-memory H2, no scheduler, no demo seed). Without it this test used
 * the dev profile's file-based database, which the running dev app holds an exclusive lock on — so
 * {@code mvnw test} failed at startup with "Database may be already in use" unless you stopped the app
 * first. Sharing the profile with the integration tests also means it reuses their already-built context
 * instead of paying for a second one.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class KladdoApplicationTests {

	@Test
	void contextLoads() {
	}

}
