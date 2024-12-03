package com.woowacamp.storage.global.background;

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
public class BackgroundMetadataManager {
	@Value("${folder.update.threadCount}")
	private int threadCount;

	@Value("${folder.update.queueSize}")
	private int queueSize;

	@Value("${folder.update.threadName}")
	private String threadName;

	@Bean
	public Executor metadataThreadPoolExecutor() {
		ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
		threadPoolTaskExecutor.setCorePoolSize(threadCount);
		threadPoolTaskExecutor.setMaxPoolSize(threadCount);
		threadPoolTaskExecutor.setQueueCapacity(queueSize);
		threadPoolTaskExecutor.setThreadNamePrefix(threadName);

		// 작업 중간에 예외 던져서 멈추지 않도록 처리
		threadPoolTaskExecutor.setRejectedExecutionHandler((r, executor) -> {
			try {
				executor.getQueue().put(r);
			} catch (InterruptedException e) {
				log.error(e.toString(), e);
				Thread.currentThread().interrupt();
			}
		});
		threadPoolTaskExecutor.initialize();

		return threadPoolTaskExecutor;
	}
}
