package com.civicpulse.backend_spring;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class BackendSpringApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendSpringApplication.class, args);
	}

	/**
	 * Pin the JVM to UTC.
	 *
	 * Entities use LocalDateTime over zoneless TIMESTAMP(6) columns, and
	 * Hibernate's @CreationTimestamp writes the JVM's local wall-clock time.
	 * On a machine set to IST that stores 10:15 for an event that happened at
	 * 04:45 UTC, and the value carries nothing to say which it meant — so the
	 * same row reads differently depending on where the server runs.
	 *
	 * Forcing UTC here makes the stored value unambiguous, which is what lets
	 * Wire.timestamp() stamp a trailing Z honestly.
	 *
	 * The durable fix is TIMESTAMPTZ columns mapped to Instant. That is a
	 * migration worth doing while the data is still disposable, and it is
	 * tracked as a follow-up rather than done here.
	 */
	@PostConstruct
	void forceUtcDefaultTimeZone() {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

}
