package com.woowacamp.storage.domain.folder.service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.WriteRedisConnectionException;
import org.springframework.stereotype.Service;

import com.woowacamp.storage.global.config.RedissonManager;
import com.woowacamp.storage.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisLockService {
	private final RedissonClient redissonClient;
	private final RedissonManager redissonManager;

	private final int takingLockTime = 1;
	private final int keepingLockTime = 10;

	// TODO 이동 시 락 2개 획득하는 로직은 별도로 처리해야 할듯함.
	//  1번 락 획득 후 DB에 뭔구 문제로 시간이 지체되고 2번 락을 획득하면 그 텀이 생김.
	//  그럼 그 텀이 만약 DB 타임아웃에 가깝게 되어 몇 초가 지나고, 1번 락이 그 몇 초때문에 해제되면 다른 이동 작업이 락을 획득하여 이동이 가능함.
	//  그럼 또 순환 문제가 생길 수도 있음. 락을 획득할 땐 조금 손해볼 수 있어도 한 번에 획득하는 것이 확실한 것 같음
	public void handleUserRequest(String lockName, Runnable task, RuntimeException exception, Runnable rollback) {
		// RLock lock = redissonClient.getLock(lockName);
		RLock lock = redissonManager.getCurrentClient().getLock(lockName);

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
					log.error("Failed to do service: " + e.getMessage(), e);
					rollback.run();
					log.info("[ROLLBACK SUCCESS]");
					throw ErrorCode.REDIS_LOCK_TIME_OVER.baseException();
				} catch (WriteRedisConnectionException e) {
					log.error("Redisson Client Disconnected: " + e.getMessage(), e);
					rollback.run();
					log.info("[ROLLBACK SUCCESS]");
					throw ErrorCode.REDIS_DISCONNECTED_ERROR.baseException();
				}
			}
		}
	}

	public <T> T handleUserRequest(String lockName, Supplier<T> task, RuntimeException exception) {
		// RLock lock = redissonClient.getLock(lockName);
		RLock lock = redissonManager.getCurrentClient().getLock(lockName);
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
