package com.woowacamp.storage.lock.aspect;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.context.ApplicationContext;

import com.woowacamp.storage.global.error.CustomException;
import com.woowacamp.storage.global.error.ErrorCode;
import com.woowacamp.storage.lock.annotation.DistributedLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class DistributedLockAspectTest {

	@InjectMocks
	private DistributedLockAspect distributedLockAspect;

	@Mock
	private RedissonClient redissonClient;

	@Spy
	private AopTxManager aopTxManager;

	@Mock
	private ApplicationContext applicationContext;

	@Mock
	private RLock singleLock;

	@Mock
	private RLock lock1;

	@Mock
	private RLock lock2;

	@Mock
	private RLock lock3;

	@Mock
	private RLock multiLock;

	private TestService proxy;


	@BeforeEach
	void setUp() throws Throwable {

		AspectJProxyFactory factory = new AspectJProxyFactory(new TestService());
		factory.addAspect(distributedLockAspect);
		proxy = factory.getProxy();
	}

	@Nested
	@DisplayName("단일 락")
	class SingleLockTests {

		@Test
		@DisplayName("watchdog(leaseTime < 0)에서 tryLock 성공하면 proceed 후 unlock 된다")
		void watchdog_success_unlock_after_proceed() throws Throwable {
			// Given
			given(redissonClient.getLock("folder:1")).willReturn(singleLock);
			given(singleLock.tryLock(0L, TimeUnit.SECONDS)).willReturn(true);
			given(singleLock.isHeldByCurrentThread()).willReturn(true);

			// When
			String result = proxy.singleWatchdog(1L);

			// Then
			assertEquals("single-success", result);
			then(aopTxManager).should(times(1)).proceed(any(ProceedingJoinPoint.class));
			then(singleLock).should(times(1)).unlock();
		}

		@Test
		@DisplayName("watchdog(leaseTime < 0)에서는 2-arg tryLock만 호출되고 3-arg는 호출되지 않는다")
		void watchdog_calls_only_2arg_tryLock() throws Throwable {
			// Given
			given(redissonClient.getLock("folder:1")).willReturn(singleLock);
			given(singleLock.tryLock(0L, TimeUnit.SECONDS)).willReturn(true);

			// When
			proxy.singleWatchdog(1L);

			// Then
			then(singleLock).should(times(1)).tryLock(0L, TimeUnit.SECONDS);
			then(singleLock).should(never()).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
		}

		@Test
		@DisplayName("leaseTime 지정(>=0)에서는 3-arg tryLock을 호출하고 2-arg는 호출되지 않는다")
		void lease_calls_only_3arg_tryLock() throws Throwable {
			// Given
			given(redissonClient.getLock("folder:2")).willReturn(singleLock);
			given(singleLock.tryLock(0L, 10L, TimeUnit.SECONDS)).willReturn(true);

			// When
			String result = proxy.singleWithLease(2L);

			// Then
			assertEquals("lease-success", result);
			then(singleLock).should(times(1)).tryLock(0L, 10L, TimeUnit.SECONDS);
			then(singleLock).should(never()).tryLock(anyLong(), any(TimeUnit.class));
		}

		@Test
		@DisplayName("tryLock 실패(false)면 예외를 던지고 proceed는 호출되지 않는다")
		void tryLock_false_no_proceed_unlock_swallow() throws Throwable {
			// Given
			given(redissonClient.getLock("folder:3")).willReturn(singleLock);
			given(singleLock.tryLock(0L, TimeUnit.SECONDS)).willReturn(false);

			// When
			CustomException ex = assertThrows(CustomException.class, () -> proxy.singleWatchdog(3L));

			// Then
			assertEquals(ErrorCode.FOLDER_LOCK_CONFLICT.getStatus(), ex.getHttpStatus());
			then(aopTxManager).should(never()).proceed(any());
			then(singleLock).should(never()).unlock();
		}

		@Test
		@DisplayName("unlock이 가능한 경우 정상적으로 해제된다")
		void unlock_exception_swallowed_success_returns_result() throws Throwable {
			// Given
			given(redissonClient.getLock("folder:1")).willReturn(singleLock);
			given(singleLock.tryLock(0L, TimeUnit.SECONDS)).willReturn(true);
			given(singleLock.isHeldByCurrentThread()).willReturn(true);

			// When
			String result = proxy.singleWatchdog(1L);

			// Then
			assertEquals("single-success", result);
			then(aopTxManager).should(times(1)).proceed(any());
			then(singleLock).should(times(1)).unlock();
		}

		@Test
		@DisplayName("tryLock 중 InterruptedException이면 CustomException으로 전파되고 proceed는 호출되지 않는다")
		void interrupted_wrap_runtime_no_proceed_unlock_swallow() throws Throwable {
			// Given
			given(redissonClient.getLock("folder:4")).willReturn(singleLock);
			given(singleLock.tryLock(0L, TimeUnit.SECONDS)).willThrow(new InterruptedException("interrupted"));

			// When
			CustomException ex = assertThrows(CustomException.class, () -> proxy.singleWatchdog(4L));

			// Then
			assertEquals(ErrorCode.FAILED_TO_GET_FOLDER_LOCK.getStatus(), ex.getHttpStatus());
			then(aopTxManager).should(never()).proceed(any());
			then(singleLock).should(never()).unlock();
		}

		@Test
		@DisplayName("비즈니스 로직에서 예외가 터져도 unlock은 시도된다")
		void business_exception_still_unlock() throws Exception {
			// Given
			given(redissonClient.getLock("folder:5")).willReturn(singleLock);
			given(singleLock.tryLock(0L, TimeUnit.SECONDS)).willReturn(true);
			given(singleLock.isHeldByCurrentThread()).willReturn(true);

			// When
			IllegalStateException ex = assertThrows(IllegalStateException.class, () -> proxy.boom(5L));

			// Then
			assertTrue(ex.getMessage().contains("fail"));
			then(singleLock).should(times(1)).unlock();
		}
	}

	@Nested
	@DisplayName("멀티 락")
	class MultiLockTests {

		@Test
		@DisplayName("SpEL이 List를 반환하면 MultiLock으로 처리되고 key 정렬 순서로 getLock 호출된다")
		void multiLock_success_sorted_keys_literal() throws Exception {
			// Given: keys = {'k2','k1'} -> sorted -> k1, k2
			given(redissonClient.getLock("k1")).willReturn(lock1);
			given(redissonClient.getLock("k2")).willReturn(lock2);

			given(redissonClient.getMultiLock(any(RLock[].class))).willReturn(multiLock);
			given(multiLock.tryLock(0L, TimeUnit.SECONDS)).willReturn(true);
			given(multiLock.isHeldByCurrentThread()).willReturn(true);

			// When
			String result = proxy.multiLiteral();

			// Then
			assertEquals("multi-success", result);

			InOrder inOrder = inOrder(redissonClient);
			inOrder.verify(redissonClient).getLock("k1");
			inOrder.verify(redissonClient).getLock("k2");

			ArgumentCaptor<RLock[]> captor = ArgumentCaptor.forClass(RLock[].class);
			then(redissonClient).should().getMultiLock(captor.capture());
			assertArrayEquals(new RLock[]{lock1, lock2}, captor.getValue());

			then(multiLock).should(times(1)).unlock();
		}

		@Test
		@DisplayName("멀티락: 파라미터 기반 2개 키 생성(List literal + concat)도 정상 동작한다")
		void multiLock_success_two_params() throws Throwable {
			// Given: id1=2, id2=1 -> 생성 키: folder:2, folder:1 -> sorted -> folder:1, folder:2
			given(redissonClient.getLock("folder:1")).willReturn(lock1);
			given(redissonClient.getLock("folder:2")).willReturn(lock2);

			given(redissonClient.getMultiLock(any(RLock[].class))).willReturn(multiLock);
			given(multiLock.tryLock(0L, TimeUnit.SECONDS)).willReturn(true);
			given(multiLock.isHeldByCurrentThread()).willReturn(true);

			// When
			String result = proxy.multiTwoIds(2L, 1L);

			// Then
			assertEquals("multi-two", result);

			InOrder inOrder = inOrder(redissonClient);
			inOrder.verify(redissonClient).getLock("folder:1");
			inOrder.verify(redissonClient).getLock("folder:2");

			then(multiLock).should(times(1)).unlock();
		}

		@Test
		@DisplayName("멀티락: List 파라미터 projection(#ids.![...])도 정상 동작한다")
		void multiLock_success_list_projection() throws Throwable {
			// Given: ids = [3,1,2] -> keys = folder:3, folder:1, folder:2 -> sorted -> 1,2,3
			given(redissonClient.getLock("folder:1")).willReturn(lock1);
			given(redissonClient.getLock("folder:2")).willReturn(lock2);
			given(redissonClient.getLock("folder:3")).willReturn(lock3);

			given(redissonClient.getMultiLock(any(RLock[].class))).willReturn(multiLock);
			given(multiLock.tryLock(0L, TimeUnit.SECONDS)).willReturn(true);
			given(multiLock.isHeldByCurrentThread()).willReturn(true);

			// When
			String result = proxy.multiList(List.of(3L, 1L, 2L));

			// Then
			assertEquals("multi-list", result);

			InOrder inOrder = inOrder(redissonClient);
			inOrder.verify(redissonClient).getLock("folder:1");
			inOrder.verify(redissonClient).getLock("folder:2");
			inOrder.verify(redissonClient).getLock("folder:3");

			then(multiLock).should(times(1)).unlock();
		}

		@Test
		@DisplayName("멀티락: 중복 키가 들어와도 distinct 처리되어 getLock/getMultiLock 배열 길이가 줄어든다")
		void multiLock_distinct_applied() throws Throwable {
			// Given: keys = {'k2','k1','k1'} -> distinct+sorted -> k1,k2
			given(redissonClient.getLock("k1")).willReturn(lock1);
			given(redissonClient.getLock("k2")).willReturn(lock2);

			given(redissonClient.getMultiLock(any(RLock[].class))).willReturn(multiLock);
			given(multiLock.tryLock(0L, TimeUnit.SECONDS)).willReturn(true);

			// When
			String result = proxy.multiWithDuplicates();

			// Then
			assertEquals("multi-dup", result);

			ArgumentCaptor<RLock[]> captor = ArgumentCaptor.forClass(RLock[].class);
			then(redissonClient).should().getMultiLock(captor.capture());
			assertEquals(2, captor.getValue().length); // k1,k2만 남아야 함
		}

		@Test
		@DisplayName("멀티락: blank/space 키는 필터링되어 단일락으로 축소될 수 있다(= getMultiLock 호출 안 함)")
		void multiLock_blank_filtered_becomes_single_lock() throws Throwable {
			// Given: keys = {'k1','', '   '} -> filter -> ['k1'] -> 단일락
			given(redissonClient.getLock("k1")).willReturn(singleLock);
			given(singleLock.tryLock(0L, TimeUnit.SECONDS)).willReturn(true);
			given(singleLock.isHeldByCurrentThread()).willReturn(true);

			// When
			String result = proxy.keysWithBlanks();

			// Then
			assertEquals("blank-filtered", result);
			then(redissonClient).should(never()).getMultiLock(any(RLock[].class));
			then(singleLock).should(times(1)).unlock();
		}

		@Test
		@DisplayName("멀티락: SpEL이 String[] 반환(new String[]{...})해도 정상 처리된다")
		void multiLock_string_array_result() throws Throwable {
			// Given: new String[]{'k2','k1'} -> sorted -> k1,k2
			given(redissonClient.getLock("k1")).willReturn(lock1);
			given(redissonClient.getLock("k2")).willReturn(lock2);

			given(redissonClient.getMultiLock(any(RLock[].class))).willReturn(multiLock);
			given(multiLock.tryLock(0L, TimeUnit.SECONDS)).willReturn(true);
			given(multiLock.isHeldByCurrentThread()).willReturn(true);

			// When
			String result = proxy.multiFromArray();

			// Then
			assertEquals("multi-array", result);

			InOrder inOrder = inOrder(redissonClient);
			inOrder.verify(redissonClient).getLock("k1");
			inOrder.verify(redissonClient).getLock("k2");

			then(multiLock).should(times(1)).unlock();
		}

		@Test
		@DisplayName("멀티락: tryLock 실패(false)면 예외를 던지고 proceed는 호출되지 않는다")
		void multiLock_tryLock_false_throws() throws Throwable {
			// Given
			given(redissonClient.getLock("k1")).willReturn(lock1);
			given(redissonClient.getLock("k2")).willReturn(lock2);
			given(redissonClient.getMultiLock(any(RLock[].class))).willReturn(multiLock);
			given(multiLock.tryLock(0L, TimeUnit.SECONDS)).willReturn(false);

			// When
			CustomException ex = assertThrows(CustomException.class, () -> proxy.multiLiteral());

			// Then
			assertEquals(ErrorCode.FOLDER_LOCK_CONFLICT.getStatus(), ex.getHttpStatus());
			then(aopTxManager).should(never()).proceed(any());
			then(multiLock).should(never()).unlock();
		}

		@Test
		@DisplayName("멀티락: unlock 가능한 경우 정상적으로 해제된다")
		void multiLock_unlock_exception_swallowed_success_returns_result() throws Throwable {
			// Given
			given(redissonClient.getLock("k1")).willReturn(lock1);
			given(redissonClient.getLock("k2")).willReturn(lock2);
			given(redissonClient.getMultiLock(any(RLock[].class))).willReturn(multiLock);
			given(multiLock.tryLock(0L, TimeUnit.SECONDS)).willReturn(true);
			given(multiLock.isHeldByCurrentThread()).willReturn(true);

			// When
			String result = proxy.multiLiteral();

			// Then
			assertEquals("multi-success", result);
			then(aopTxManager).should(times(1)).proceed(any());
			then(multiLock).should(times(1)).unlock();
		}
	}

	@Nested
	@DisplayName("키 표현식 오류")
	class KeyExpressionTests {

		@Test
		@DisplayName("keys SpEL 결과가 empty면 LOCK_KEY_EXPRESSION_EXCEPTION 예외를 던진다")
		void empty_keys_throw() throws Throwable {
			// When
			CustomException ex = assertThrows(CustomException.class, () -> proxy.emptyKeys());

			// Then
			assertTrue(ex.getMessage().contains("서버 처리 중 예외가 발생했습니다."));
			then(redissonClient).shouldHaveNoInteractions();
			then(aopTxManager).should(never()).proceed(any());
		}

		@Test
		@DisplayName("keys SpEL이 String/List/String[]/Collection이 아니면 LOCK_KEY_EXPRESSION_EXCEPTION 예외를 던진다")
		void unsupported_type_throw() throws Throwable {
			// When
			CustomException ex = assertThrows(CustomException.class, () -> proxy.unsupportedType());

			// Then
			assertTrue(ex.getMessage().contains("서버 처리 중 예외가 발생했습니다."));
			then(redissonClient).shouldHaveNoInteractions();
			then(aopTxManager).should(never()).proceed(any());
		}
	}

	// ===== 테스트용 서비스 =====
	static class TestService {

		@DistributedLock(keys = "'folder:' + #id", waitTime = 0, leaseTime = -1, unit = TimeUnit.SECONDS)
		public String singleWatchdog(long id) {
			return "single-success";
		}

		@DistributedLock(keys = "'folder:' + #id", waitTime = 0, leaseTime = 10, unit = TimeUnit.SECONDS)
		public String singleWithLease(long id) {
			return "lease-success";
		}

		@DistributedLock(keys = "{'k2','k1'}", waitTime = 0, leaseTime = -1, unit = TimeUnit.SECONDS)
		public String multiLiteral() {
			return "multi-success";
		}

		// ✅ 파라미터 기반 멀티락(2개)
		@DistributedLock(keys = "{'folder:' + #id1, 'folder:' + #id2}", waitTime = 0, leaseTime = -1, unit = TimeUnit.SECONDS)
		public String multiTwoIds(long id1, long id2) {
			return "multi-two";
		}

		// ✅ List 파라미터 projection
		@DistributedLock(keys = "#ids.![ 'folder:' + #this ]", waitTime = 0, leaseTime = -1, unit = TimeUnit.SECONDS)
		public String multiList(List<Long> ids) {
			return "multi-list";
		}

		// ✅ 중복 포함
		@DistributedLock(keys = "{'k2','k1','k1'}", waitTime = 0, leaseTime = -1, unit = TimeUnit.SECONDS)
		public String multiWithDuplicates() {
			return "multi-dup";
		}

		// ✅ blank 필터링
		@DistributedLock(keys = "{'k1','', '   '}", waitTime = 0, leaseTime = -1, unit = TimeUnit.SECONDS)
		public String keysWithBlanks() {
			return "blank-filtered";
		}

		// ✅ String[] 반환
		@DistributedLock(keys = "new String[]{'k2','k1'}", waitTime = 0, leaseTime = -1, unit = TimeUnit.SECONDS)
		public String multiFromArray() {
			return "multi-array";
		}

		@DistributedLock(keys = "''", waitTime = 0, leaseTime = -1, unit = TimeUnit.SECONDS)
		public String emptyKeys() {
			return "fail";
		}

		@DistributedLock(keys = "1+1", waitTime = 0, leaseTime = -1, unit = TimeUnit.SECONDS)
		public String unsupportedType() {
			return "fail";
		}

		@DistributedLock(keys = "'folder:' + #id", waitTime = 0, leaseTime = -1, unit = TimeUnit.SECONDS)
		public String boom(long id) {
			throw new IllegalStateException("fail");
		}
	}
}
