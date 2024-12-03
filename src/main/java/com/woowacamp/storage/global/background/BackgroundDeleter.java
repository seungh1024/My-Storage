package com.woowacamp.storage.global.background;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class BackgroundDeleter {
	@Value("${folder.delete.threadCount}")
	private int threadCount;

	@Value("${folder.delete.queueSize}")
	private int queueSize;

	@Value("${folder.delete.threadName}")
	private String deleteThreadName;

	@Value("${folder.search.threadName}")
	private String searchThreadName;

	@Bean
	public Executor deleteThreadPoolExecutor() {
		ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
		threadPoolTaskExecutor.setCorePoolSize(threadCount);
		threadPoolTaskExecutor.setMaxPoolSize(threadCount);
		threadPoolTaskExecutor.setQueueCapacity(queueSize);
		threadPoolTaskExecutor.setThreadNamePrefix(deleteThreadName);

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

	@Bean
	public Executor searchThreadPoolExecutor() {
		ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
		threadPoolTaskExecutor.setCorePoolSize(threadCount);
		threadPoolTaskExecutor.setMaxPoolSize(threadCount);
		threadPoolTaskExecutor.setQueueCapacity(queueSize);
		threadPoolTaskExecutor.setThreadNamePrefix(searchThreadName);

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
