package com.woowacamp.storage.domain.dummy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DummyThreadPoolExecutorTest {

	@Test
	@DisplayName("init 후 execute한 작업이 waitToEnd에서 완료된다")
	void execute_runsTasks_afterInit() {
		DummyThreadPoolExecutor executor = new DummyThreadPoolExecutor(1, 10);
		executor.init();

		AtomicInteger counter = new AtomicInteger();
		executor.execute(counter::incrementAndGet);

		executor.waitToEnd();

		assertEquals(1, counter.get());
		assertTrue(executor.isInvalidState());
	}

	@Test
	@DisplayName("작업 예외는 waitToEnd에서 다시 던진다")
	void waitToEnd_throwsWhenTaskFails() {
		DummyThreadPoolExecutor executor = new DummyThreadPoolExecutor(1, 10);
		executor.init();

		executor.execute(() -> {
			throw new IllegalStateException("boom");
		});

		assertThrows(IllegalStateException.class, executor::waitToEnd);
	}

	@Test
	@DisplayName("init 전에는 invalid 상태이며 execute가 무시된다")
	void execute_ignoredBeforeInit() {
		DummyThreadPoolExecutor executor = new DummyThreadPoolExecutor(1, 10);
		assertTrue(executor.isInvalidState());

		assertDoesNotThrow(() -> executor.execute(() -> {
			throw new IllegalStateException("should not run");
		}));
	}
}
