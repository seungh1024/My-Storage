package com.woowacamp.storage.domain.folder.service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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

	public void handleUserRequest(String lockName, Runnable task, RuntimeException exception) {
		RLock lock = redissonClient.getLock(lockName);
		boolean isLocked = false;

		try {
			isLocked = lock.tryLock(takingLockTime, keepingLockTime, TimeUnit.SECONDS);
			// 락 획득 실패 시 동시 요청이므로 예외 던짐
			if (!isLocked) {
				throw exception;
			}
			task.run();
		} catch (InterruptedException e) {
			log.error("[RedisLockService] error = {}", e);
			throw new RuntimeException(e);
		} finally {
			if (isLocked) {
				try {
					lock.unlock();
				} catch (IllegalMonitorStateException e) {
					// 락 소유 시간동안 비즈니스 로직 처리를 하지 못한 경우 예외 처리
					// 여기서 throw를 하면 비즈니스 로직이 정상적으로 처리됐어도 예외 응답을 받게 됨.
					// 메일 같은 것으로 락 소유시간이 짧은 것 같다는 알림을 전송하는게 좋다고 생각
					log.error("Failed to do service: " + e.getMessage(), e);
				}
			}
		}
	}

	public <T> T handleUserRequest(String lockName, Supplier<T> task, RuntimeException exception) {
		RLock lock = redissonClient.getLock(lockName);
		boolean isLocked = false;
		T result = null;

		try {
			isLocked = lock.tryLock(takingLockTime, keepingLockTime, TimeUnit.SECONDS);
			if (!isLocked) {
				throw exception;
			}
			lock.lock();

			result = task.get();
		} catch (InterruptedException e) {
			log.error("[RedisLockService] error = {}", e);
			throw new RuntimeException(e);
		} finally {
			if (isLocked) {
				try {
					lock.unlock();
				} catch (IllegalMonitorStateException e) {
					log.error("Failed to do service: " + e.getMessage(), e);
				}
			}
		}

		return result;
	}

}
