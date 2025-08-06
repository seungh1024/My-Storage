package com.woowacamp.storage.global.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.message.util.JsonSerializer;
import com.woowacamp.storage.global.annotation.FolderListCache;
import com.woowacamp.storage.global.util.CacheUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Order(1)
@Aspect
@Component
@RequiredArgsConstructor
public class CacheAspect {
	private final RedisTemplate<String, String> redisTemplate;
	private final JsonSerializer jsonSerializer;

	@Around("@annotation(folderListCache) && args(folderId, ..)")
	public Object cacheAround(ProceedingJoinPoint joinPoint, FolderListCache folderListCache, Long folderId) throws
		Throwable {
		String key = CacheUtil.generateKey(folderId);

		String cacheData = redisTemplate.opsForValue().get(key);
		if (cacheData != null) {
			Class<?> type = ((MethodSignature)joinPoint.getSignature()).getMethod().getReturnType();
			Object dto = jsonSerializer.deserialize(cacheData, type);
			return dto;
		}

		return joinPoint.proceed();
	}
}
