package com.civicpulse.backend_spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration smoke test. Booting the context here is not trivial — it
 * proves the Flyway migrations apply cleanly AND that every JPA entity
 * still matches the resulting schema (ddl-auto=validate), which is the
 * check that catches entity/migration drift.
 *
 * Requires a reachable database. By default it uses whatever your .env
 * points at — which is the shared Neon database. Prefer aiming it at your
 * own Neon branch via TEST_DB_URL / TEST_DB_USERNAME / TEST_DB_PASSWORD;
 * see src/test/resources/application-test.yaml.
 */
@SpringBootTest
@ActiveProfiles("test")
class BackendSpringApplicationTests {

	@Test
	void contextLoads() {
	}

}
