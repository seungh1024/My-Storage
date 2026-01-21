package com.woowacamp.storage.domain.message.config;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import lombok.extern.slf4j.Slf4j;

/**
 * 파일 용량 계산 후처리 작업을 하는 스레드 풀 정의
 */
@Configuration
@Slf4j
public class MessageConfig {
	@Value("${folder.message.threadCount}")
	private int threadCount;

	@Value("${folder.message.queueSize}")
	private int queueSize;

	@Value("${folder.message.threadName}")
	private String threadName;

	@Bean(name = "SEND_MESSAGE_EXECUTOR")
	public Executor sendMessageExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(threadCount);
		executor.setMaxPoolSize(threadCount);
		executor.setQueueCapacity(queueSize);
		executor.setThreadNamePrefix(threadName);
		executor.initialize();

		return executor;
	}
}
