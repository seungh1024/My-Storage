package com.woowacamp.storage.global.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

	@Value("${spring.redisson.address}")
	private String redissonAddress;
	@Value("${spring.redisson.lock-watchdog-timeout}")
	private long lockWatchdogTimeout;

	@Bean
	public RedissonClient redissonClient() throws IOException {
		InputStream configStream = getClass().getClassLoader().getResourceAsStream("redisson.yml");
		Config config = Config.fromYAML(configStream);
		config.useSingleServer().setAddress(redissonAddress);
		config.setLockWatchdogTimeout(lockWatchdogTimeout);
		return Redisson.create(config);
	}

	@Bean
	public RedisConnectionFactory redisConnectionFactory() {
		URI uri = URI.create(redissonAddress);

		String host = uri.getHost();
		int port = uri.getPort();

		return new LettuceConnectionFactory(host, port);
	}

	@Bean
	public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
		RedisTemplate<String, String> template = new RedisTemplate<>();
		template.setConnectionFactory(factory);
		template.setDefaultSerializer(new StringRedisSerializer());
		return template;
	}

}
