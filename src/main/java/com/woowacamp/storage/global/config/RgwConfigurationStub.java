package com.woowacamp.storage.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Profile("test")
@Configuration
public class RgwConfigurationStub {
	@Bean
	public S3Client s3Client() {
		return S3Client.builder()
			.overrideConfiguration(c -> c.addExecutionInterceptor(new FailFastInterceptor()))
			.region(Region.AP_NORTHEAST_2)
			.build();
	}

	@Bean
	public S3Presigner s3Presigner() {
		return S3Presigner.builder()
			.region(Region.AP_NORTHEAST_2)
			.build();
	}

	static class FailFastInterceptor implements ExecutionInterceptor {
		@Override
		public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attrs) {
			throw new IllegalStateException("S3 is disabled in dev profile.");
		}
	}
}
