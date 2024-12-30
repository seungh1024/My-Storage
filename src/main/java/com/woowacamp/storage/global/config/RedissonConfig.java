package com.woowacamp.storage.global.config;

import java.io.IOException;
import java.io.InputStream;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

	@Value("${spring.redisson.address}")
	private String redissonAddress;

	@Bean
	public RedissonClient redissonClient() throws IOException {
		InputStream configStream = getClass().getClassLoader().getResourceAsStream("redisson.yml");
		Config config = Config.fromYAML(configStream);
		config.useSingleServer().setAddress(redissonAddress);
		return Redisson.create(config);
	}
}
