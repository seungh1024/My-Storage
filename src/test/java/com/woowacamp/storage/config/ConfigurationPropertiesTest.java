package com.woowacamp.storage.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import com.woowacamp.storage.container.ContainerBaseConfig;

import static org.assertj.core.api.Assertions.*;

/**
 * 테스트 환경의 프로퍼티 설정이 올바르게 로드되는지 검증하는 테스트
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ConfigurationPropertiesTest extends ContainerBaseConfig {

	@Autowired
	private Environment env;

	@Test
	void requiredPropertiesArePresent() {
		// FolderService에서 필요로 하는 프로퍼티들 검증
		assertThat(env.getProperty("constant.retryCnt")).isNotNull();
		assertThat(env.getProperty("constant.batchSize")).isNotNull();
		assertThat(env.getProperty("folder.path.maxLength")).isNotNull();
	}

	@Test
	void testContainerPropertiesAreInjected() {
		// TestContainer에서 동적으로 주입된 프로퍼티들 검증
		assertThat(env.getProperty("spring.datasource.url")).contains("jdbc:mysql");
		assertThat(env.getProperty("spring.redis.host")).isNotNull();
		assertThat(env.getProperty("spring.redis.port")).isNotNull();
		assertThat(env.getProperty("spring.redisson.address")).contains("redis://");
		assertThat(env.getProperty("spring.rabbitmq.addresses")).isNotNull();
	}
}