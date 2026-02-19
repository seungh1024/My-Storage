package com.woowacamp.storage.config;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import jakarta.persistence.metamodel.Metamodel;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;

@TestConfiguration
public class JpaTestConfig {

	@Bean
	public JpaMetamodelMappingContext jpaMappingContext() {
		Metamodel metamodel = Mockito.mock(Metamodel.class);
		return new JpaMetamodelMappingContext(Set.of(metamodel));
	}

	@Bean
	public Clock appClock() {
		return Clock.systemUTC();
	}

	@Bean(name = "auditingDateTimeProvider")
	public DateTimeProvider auditingDateTimeProvider(Clock appClock) {
		return () -> Optional.of(LocalDateTime.now(appClock));
	}
}
