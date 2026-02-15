package com.woowacamp.storage.global.config;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;

@Configuration
public class TimeConfig {

	@Bean
	public Clock appClock() {
		return Clock.systemUTC();
	}

	@Bean(name = "auditingDateTimeProvider")
	public DateTimeProvider auditingDateTimeProvider(Clock appClock) {
		return () -> Optional.of(LocalDateTime.now(appClock));
	}
}
