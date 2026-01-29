package com.woowacamp.storage.domain.folder.utils;

import static org.assertj.core.api.Assertions.*;

import java.util.Stack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StackSerializationUtilTest {

	@Nested
	@DisplayName("serialize 테스트")
	class SerializeTest {

		@Test
		@DisplayName("빈 스택을 직렬화하면 빈 배열 JSON을 반환한다")
		void serialize_EmptyStack_ReturnsEmptyArrayJson() {
			// given
			Stack<Long> emptyStack = new Stack<>();

			// when
			String result = StackSerializationUtil.serialize(emptyStack);

			// then
			assertThat(result).isEqualTo("[]");
		}

		@Test
		@DisplayName("null 스택을 직렬화하면 빈 배열 JSON을 반환한다")
		void serialize_NullStack_ReturnsEmptyArrayJson() {
			// when
			String result = StackSerializationUtil.serialize(null);

			// then
			assertThat(result).isEqualTo("[]");
		}

		@Test
		@DisplayName("스택을 직렬화하면 JSON 배열 문자열을 반환한다")
		void serialize_Stack_ReturnsJsonArrayString() {
			// given
			Stack<Long> stack = new Stack<>();
			stack.push(1L);
			stack.push(5L);
			stack.push(10L);

			// when
			String result = StackSerializationUtil.serialize(stack);

			// then
			assertThat(result).isEqualTo("[1,5,10]");
		}

		@Test
		@DisplayName("큰 스택도 올바르게 직렬화된다")
		void serialize_LargeStack_Success() {
			// given
			Stack<Long> stack = new Stack<>();
			for (long i = 1; i <= 125; i++) {
				stack.push(i);
			}

			// when
			String result = StackSerializationUtil.serialize(stack);

			// then
			assertThat(result).contains("1").contains("125");
			assertThat(result).startsWith("[").endsWith("]");
		}
	}

	@Nested
	@DisplayName("deserialize 테스트")
	class DeserializeTest {

		@Test
		@DisplayName("빈 배열 JSON을 역직렬화하면 빈 스택을 반환한다")
		void deserialize_EmptyArrayJson_ReturnsEmptyStack() {
			// given
			String json = "[]";

			// when
			Stack<Long> result = StackSerializationUtil.deserialize(json);

			// then
			assertThat(result).isEmpty();
		}

		@Test
		@DisplayName("null을 역직렬화하면 빈 스택을 반환한다")
		void deserialize_Null_ReturnsEmptyStack() {
			// when
			Stack<Long> result = StackSerializationUtil.deserialize(null);

			// then
			assertThat(result).isEmpty();
		}

		@Test
		@DisplayName("공백 문자열을 역직렬화하면 빈 스택을 반환한다")
		void deserialize_EmptyString_ReturnsEmptyStack() {
			// when
			Stack<Long> result = StackSerializationUtil.deserialize("   ");

			// then
			assertThat(result).isEmpty();
		}

		@Test
		@DisplayName("JSON 배열 문자열을 역직렬화하면 스택을 반환한다")
		void deserialize_JsonArrayString_ReturnsStack() {
			// given
			String json = "[1,5,10]";

			// when
			Stack<Long> result = StackSerializationUtil.deserialize(json);

			// then
			assertThat(result).hasSize(3);
			assertThat(result.get(0)).isEqualTo(1L);
			assertThat(result.get(1)).isEqualTo(5L);
			assertThat(result.get(2)).isEqualTo(10L);
		}

		@Test
		@DisplayName("잘못된 JSON을 역직렬화하면 빈 스택을 반환한다")
		void deserialize_InvalidJson_ReturnsEmptyStack() {
			// given
			String invalidJson = "[1,2,invalid]";

			// when
			Stack<Long> result = StackSerializationUtil.deserialize(invalidJson);

			// then
			assertThat(result).isEmpty();
		}
	}

	@Nested
	@DisplayName("serialize/deserialize 왕복 테스트")
	class RoundTripTest {

		@Test
		@DisplayName("직렬화 후 역직렬화하면 원본 스택과 동일하다")
		void serializeAndDeserialize_ReturnsOriginalStack() {
			// given
			Stack<Long> original = new Stack<>();
			original.push(1L);
			original.push(5L);
			original.push(10L);
			original.push(20L);

			// when
			String json = StackSerializationUtil.serialize(original);
			Stack<Long> restored = StackSerializationUtil.deserialize(json);

			// then
			assertThat(restored).hasSize(original.size());
			for (int i = 0; i < original.size(); i++) {
				assertThat(restored.get(i)).isEqualTo(original.get(i));
			}
		}

		@Test
		@DisplayName("최대 깊이 125의 스택도 왕복 변환이 가능하다")
		void serializeAndDeserialize_MaxDepth125_Success() {
			// given
			Stack<Long> original = new Stack<>();
			for (long i = 1; i <= 125; i++) {
				original.push(i);
			}

			// when
			String json = StackSerializationUtil.serialize(original);
			Stack<Long> restored = StackSerializationUtil.deserialize(json);

			// then
			assertThat(restored).hasSize(125);
			assertThat(restored.peek()).isEqualTo(125L);
		}
	}

	@Nested
	@DisplayName("estimateSize 테스트")
	class EstimateSizeTest {

		@Test
		@DisplayName("빈 스택의 예상 크기는 2바이트다 (빈 배열 [])")
		void estimateSize_EmptyStack_Returns2() {
			// given
			Stack<Long> emptyStack = new Stack<>();

			// when
			int size = StackSerializationUtil.estimateSize(emptyStack);

			// then
			assertThat(size).isEqualTo(2);
		}

		@Test
		@DisplayName("null 스택의 예상 크기는 2바이트다")
		void estimateSize_NullStack_Returns2() {
			// when
			int size = StackSerializationUtil.estimateSize(null);

			// then
			assertThat(size).isEqualTo(2);
		}

		@Test
		@DisplayName("스택의 예상 크기를 계산한다 (깊이 * 11 + 2)")
		void estimateSize_Stack_ReturnsEstimatedSize() {
			// given
			Stack<Long> stack = new Stack<>();
			stack.push(1L);
			stack.push(5L);
			stack.push(10L);

			// when
			int size = StackSerializationUtil.estimateSize(stack);

			// then
			assertThat(size).isEqualTo(2 + (3 * 11)); // 35
		}

		@Test
		@DisplayName("최대 깊이 125의 스택 예상 크기는 약 1.4KB다")
		void estimateSize_MaxDepth125_ReturnsReasonableSize() {
			// given
			Stack<Long> stack = new Stack<>();
			for (int i = 0; i < 125; i++) {
				stack.push((long)i);
			}

			// when
			int size = StackSerializationUtil.estimateSize(stack);

			// then
			int expected = 2 + (125 * 11); // 1377 bytes
			assertThat(size).isEqualTo(expected);
			assertThat(size).isLessThan(2000); // 2KB 이하
		}
	}
}