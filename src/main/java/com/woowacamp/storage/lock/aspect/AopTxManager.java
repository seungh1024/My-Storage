package com.woowacamp.storage.lock.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AopTxManager {
	/**
	 * commit 후 락 해제를 위해 트랜잭션 별도 적용
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Object proceed(ProceedingJoinPoint pjp) throws Throwable {
		return pjp.proceed();
	}
}
