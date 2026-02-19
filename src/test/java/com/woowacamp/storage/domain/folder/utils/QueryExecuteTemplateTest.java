package com.woowacamp.storage.domain.folder.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueryExecuteTemplateTest {

	@Test
	@DisplayName("성공: limit 기준으로 cursor paging 반복 호출 + consumer도 반복 호출된다")
	void success_paging_loop() {
		// given
		int limit = 2;
		List<List<Integer>> consumed = new ArrayList<>();

		Function<Integer, List<Integer>> selector = last -> {
			if (last == null) return List.of(1, 2); // size==limit -> 계속
			if (last == 2) return List.of(3, 4);    // size==limit -> 계속
			if (last == 4) return List.of(5);       // size<limit  -> 종료
			return List.of();
		};

		// when
		QueryExecuteTemplate.selectFilesAndExecuteWithCursor(limit, selector, consumed::add);

		// then
		assertEquals(3, consumed.size());
		assertEquals(List.of(1, 2), consumed.get(0));
		assertEquals(List.of(3, 4), consumed.get(1));
		assertEquals(List.of(5), consumed.get(2));
	}

	@Test
	@DisplayName("성공: 첫 페이지가 빈 리스트면 consumer는 호출되지 않고 즉시 종료된다")
	void success_empty_first_page_no_consume() {
		// given
		int limit = 2;
		AtomicInteger consumeCnt = new AtomicInteger();

		// when
		QueryExecuteTemplate.selectFilesAndExecuteWithCursor(limit, last -> List.of(), list -> consumeCnt.incrementAndGet());

		// then
		assertEquals(0, consumeCnt.get());
	}

	@Test
	@DisplayName("검증: 다음 페이지 selectFunction 입력은 직전 리스트의 마지막 원소다")
	void verify_last_element_is_passed() {
		// given
		int limit = 2;
		List<Integer> received = new ArrayList<>();

		Function<Integer, List<Integer>> selector = last -> {
			received.add(last); // null 포함 기록
			if (last == null) return List.of(10, 20);
			if (last == 20) return List.of(30, 40);
			if (last == 30) return List.of(50);
			return List.of();
		};

		// when
		QueryExecuteTemplate.selectFilesAndExecuteWithCursor(limit, selector, list -> {});

		System.out.println(received);
		// then
		assertEquals(3, received.size());   // null, 20, 30
		assertNull(received.get(0));
		assertEquals(20, received.get(1));
		assertEquals(40, received.get(2));
	}
}
