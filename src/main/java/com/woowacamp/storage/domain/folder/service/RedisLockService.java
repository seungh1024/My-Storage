package com.woowacamp.storage.domain.folder.service;

import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisLockService {
	private final RedissonClient redissonClient;

	private final int takingLockTime = 1;
	private final int keepingLockTime = 10;

	public <T extends RuntimeException> void handleUserRequest(String lockName, Runnable task, T exception) {
		RLock lock = redissonClient.getLock(lockName);
		boolean isLocked = false;

		try {
			isLocked = lock.tryLock(takingLockTime, keepingLockTime, TimeUnit.SECONDS);
			// 락 획득 실패 시 동시 요청이므로 예외 던짐
			if (!isLocked) {
				System.out.println("???");
				throw exception;
			}
			task.run();
		} catch (InterruptedException e) {
			log.error("[RedisLockService] error = {}", e);
			throw new RuntimeException(e);
		} finally {
			if (isLocked) {
				lock.unlock();
			}
		}
	}

}
