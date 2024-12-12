package com.woowacamp.storage.global.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

	@Bean
	public RedissonClient redissonClient() throws IOException {
		InputStream configStream = getClass().getClassLoader().getResourceAsStream("redisson.yml");
		Config config = Config.fromYAML(configStream);
		return Redisson.create(config);
	}
}
