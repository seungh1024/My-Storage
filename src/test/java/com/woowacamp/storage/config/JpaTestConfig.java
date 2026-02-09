package com.woowacamp.storage.config;

import java.util.Set;

import jakarta.persistence.metamodel.Metamodel;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;

@TestConfiguration
public class JpaTestConfig {

	@Bean
	public JpaMetamodelMappingContext jpaMappingContext() {
		Metamodel metamodel = Mockito.mock(Metamodel.class);
		return new JpaMetamodelMappingContext(Set.of(metamodel));
	}
}
