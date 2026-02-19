package com.woowacamp.storage.domain.folder.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * DFS 탐색을 위한 Stack을 JSON으로 직렬화/역직렬화하는 유틸 클래스
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class StackSerializationUtil {

	private static final ObjectMapper objectMapper = new ObjectMapper();

	/**
	 * Stack<Long>을 JSON 문자열로 직렬화
	 * 예: [1, 5, 10] -> "[1,5,10]"
	 */
	public static String serialize(Stack<Long> stack) {
		if (stack == null || stack.isEmpty()) {
			return "[]";
		}

		try {
			// Stack을 List로 변환 (순서 유지)
			List<Long> list = new ArrayList<>(stack);
			return objectMapper.writeValueAsString(list);
		} catch (JsonProcessingException e) {
			log.error("Failed to serialize stack", e);
			return "[]";
		}
	}

	/**
	 * JSON 문자열을 Stack<Long>으로 역직렬화
	 * 예: "[1,5,10]" -> Stack containing [1, 5, 10]
	 */
	public static Stack<Long> deserialize(String json) {
		if (json == null || json.trim().isEmpty() || "[]".equals(json)) {
			return new Stack<>();
		}

		try {
			List<Long> list = objectMapper.readValue(json, new TypeReference<List<Long>>() {
			});
			Stack<Long> stack = new Stack<>();
			stack.addAll(list);
			return stack;
		} catch (JsonProcessingException e) {
			log.error("Failed to deserialize stack from json: {}", json, e);
			return new Stack<>();
		}
	}

	/**
	 * Stack의 예상 크기 계산 (바이트 단위)
	 * Long 타입 기준: 각 숫자는 평균 10자리 + 구분자
	 */
	public static int estimateSize(Stack<Long> stack) {
		if (stack == null || stack.isEmpty()) {
			return 2; // "[]"
		}
		// 대략적으로 각 Long은 10자리 + 쉼표 = 11바이트로 계산
		return 2 + (stack.size() * 11);
	}
}