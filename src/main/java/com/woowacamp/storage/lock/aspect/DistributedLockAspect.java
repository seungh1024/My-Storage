package com.woowacamp.storage.lock.aspect;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationContext;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import com.woowacamp.storage.global.error.ErrorCode;
import com.woowacamp.storage.global.util.StorageStringUtil;
import com.woowacamp.storage.lock.annotation.DistributedLock;
import com.woowacamp.storage.lock.util.CustomOrdered;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Aspect
@Component
@RequiredArgsConstructor
@Order(CustomOrdered.FIRST)
@Slf4j
public class DistributedLockAspect {

	private final RedissonClient redissonClient;
	private final ExpressionParser parser = new SpelExpressionParser(); // SpEL(Spring Expression Language) 표현식 파서
	private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer(); // 메서드와 생성자의 파라미터 이름을 찾기 위한 인터페이스
	private final AopTxManager aopTxManager;
	private final ApplicationContext applicationContext;

	@Around("@annotation(ann)")
	public Object around(ProceedingJoinPoint pjp, DistributedLock ann) throws Throwable {
		// 호출되는 메서드 정보 가져오기 (파라미터 이름/타입 등을 쓰려고)
		Method method = ((MethodSignature) pjp.getSignature()).getMethod();

		// SpEL에서 #folderId 같은 변수를 쓰려면, 파라미터를 컨텍스트에 넣어야 함
		EvaluationContext ctx = buildContext(method, pjp.getArgs());

		// 멀티락으로 받을 수 있으니 항상 리스트 형태로
		List<String> lockKeys = evalToStringList(ctx, ann.keys()).stream()
			.filter(s -> s != null && !s.isBlank())
			.distinct()
			.sorted() // 멀티락은 항상 정렬(락 순서 고정)하는 게 안전
			.toList();

		if (lockKeys.isEmpty()) {
			throw ErrorCode.LOCK_KEY_EXPRESSION_EXCEPTION.baseException("키 값이 존재하지 않습니다.");
		}

		RLock lock = getRLock(lockKeys);

		boolean acquired = false;
		try {
			// - leaseTime < 0: watchdog(자동 연장)
			// - leaseTime >=0: 지정한 시간 후 자동 해제
			if (ann.leaseTime() < 0) {
				acquired = lock.tryLock(ann.waitTime(), ann.unit());
			} else {
				acquired = lock.tryLock(ann.waitTime(), ann.leaseTime(), ann.unit());
			}

			if (!acquired) {
				throw ErrorCode.FOLDER_LOCK_CONFLICT.baseException(StorageStringUtil.format("락 획득 실패: {}",lockKeys));
			}


			// 실제 메서드 호출(그리고 안쪽 Aspect, @Transactional 등이 이어서 실행됨)
			return aopTxManager.proceed(pjp);

		}catch (InterruptedException e) {
			log.error("[RedisLockService] error = {}", e);
			throw new RuntimeException(e);
		}finally{
			try {
				lock.unlock();
			} catch (IllegalMonitorStateException e) {
				log.error("[Redisson Lock Already Unlocked] Service name: {}, lock keys: {}",method.getName(),lockKeys);
			}
		}
	}

	/**
	 * 키 개수에 따라 단일 락 또는 MultiLock 생성
	 */
	private RLock getRLock(List<String> keys) {
		if (keys.size() == 1) {
			return redissonClient.getLock(keys.get(0));
		}
		RLock[] locks = keys.stream().map(redissonClient::getLock).toArray(RLock[]::new);
		return redissonClient.getMultiLock(locks);
	}

	/**
	 * SpEL 컨텍스트에 메서드 파라미터를 #이름 형태로 바인딩
	 */
	private EvaluationContext buildContext(Method method, Object[] args) {
		StandardEvaluationContext ctx = new StandardEvaluationContext();
		ctx.setBeanResolver(new BeanFactoryResolver(applicationContext));
		String[] paramNames = nameDiscoverer.getParameterNames(method);

		// paramNames가 null이면(#folderId를 못 씀) -> #p0 같은 방식으로 접근해야 함
		if (paramNames != null) {
			for (int i = 0; i < paramNames.length; i++) {
				ctx.setVariable(paramNames[i], args[i]);
			}
		}
		return ctx;
	}

	/**
	 * SpEL 평가 결과(Object)를 List<String>으로 통일
	 */
	private List<String> evalToStringList(EvaluationContext ctx, String spel) {
		Object v = parser.parseExpression(spel).getValue(ctx);

		if (v == null) return List.of();
		if (v instanceof String s) return List.of(s);
		if (v instanceof String[] arr) return Arrays.asList(arr);
		if (v instanceof Collection<?> col) return col.stream().map(String::valueOf).toList();

		throw ErrorCode.LOCK_KEY_EXPRESSION_EXCEPTION.baseException(StorageStringUtil.format("Failed to evaluate lock keys SpEL. SpEL: {}",spel));
	}

}
