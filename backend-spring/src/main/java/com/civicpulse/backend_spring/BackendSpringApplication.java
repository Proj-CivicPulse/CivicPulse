package com.civicpulse.backend_spring;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class BackendSpringApplication {

	static {
		// BEFORE Spring, and before anything else touches a clock.
		//
		// This used to live in the @PostConstruct below, which runs far too
		// late: Hibernate has already resolved its default time zone by then,
		// so @CreationTimestamp kept writing the machine's LOCAL wall clock.
		// On an IST laptop every created_at went into the database 5.5 hours
		// ahead of the instant it described, and Wire.timestamp() then stamped
		// a trailing Z on it — reporting times in the future, in UTC, to every
		// client. Nothing failed; the numbers were simply wrong.
		//
		// A static initialiser runs when the class is first loaded, which
		// precedes both SpringApplication.run() here and the test context's
		// bootstrap, so it is the one place that covers every entry point.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

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
	 * Forcing UTC makes the stored value unambiguous, which is what lets
	 * Wire.timestamp() stamp a trailing Z honestly.
	 *
	 * The real enforcement is the static initialiser above — this remains only
	 * as a belt-and-braces re-assertion for any path that somehow loaded a
	 * clock earlier. It is deliberately redundant.
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
