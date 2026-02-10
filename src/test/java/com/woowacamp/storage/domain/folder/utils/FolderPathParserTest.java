package com.woowacamp.storage.domain.folder.utils;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class FolderPathParserTest {

	@Nested
	@DisplayName("정상 입력")
	class ValidInputs {

		@Test
		@DisplayName("/pk1/pk2/pk3/ -> [pk1, pk2, pk3]")
		void parse_success_basic() {
			// Given
			String path = "/1/2/3/";

			// When
			Optional<List<String>> resultOpt = FolderPathParser.parsing(path);

			// Then
			assertTrue(resultOpt.isPresent());
			assertEquals(List.of("1", "2", "3"), resultOpt.get());
		}

		@Test
		@DisplayName("pk가 공백을 포함해도 보존한다 -> ['folder 1','folder 2','folder 3']")
		void parse_success_preserve_spaces() {
			// Given
			String path = "/folder 1/folder 2/folder 3/";

			// When
			Optional<List<String>> resultOpt = FolderPathParser.parsing(path);

			// Then
			assertTrue(resultOpt.isPresent());
			assertEquals(List.of("folder 1", "folder 2", "folder 3"), resultOpt.get());
		}

		@Test
		@DisplayName("pk의 앞뒤 공백도 그대로 보존한다 -> ['  a  ', 'b', 'c']")
		void parse_success_preserve_leading_trailing_spaces() {
			// Given
			String path = "/  a  /b/c/";

			// When
			Optional<List<String>> resultOpt = FolderPathParser.parsing(path);

			// Then
			assertTrue(resultOpt.isPresent());
			assertEquals(List.of("  a  ", "b", "c"), resultOpt.get());
		}

		@Test
		@DisplayName("자기 자신만 있는 경우(/3/) -> ['3']")
		void parse_success_self_only() {
			// Given
			String path = "/3/";

			// When
			Optional<List<String>> resultOpt = FolderPathParser.parsing(path);

			// Then
			assertTrue(resultOpt.isPresent());
			assertEquals(List.of("3"), resultOpt.get());
		}

		@Test
		@DisplayName("루트만 있는 경우(/) -> 빈 리스트 (현재 구현 기준 유효로 처리)")
		void parse_success_root_only() {
			// Given
			String path = "/";

			// When
			Optional<List<String>> resultOpt = FolderPathParser.parsing(path);

			// Then
			assertTrue(resultOpt.isPresent());
			assertEquals(List.of(), resultOpt.get());
		}
	}


	@Nested
	@DisplayName("입력값 오류(null/blank)")
	class InvalidInputs_NullOrBlank {

		@Test
		@DisplayName("null이면 Optional.empty")
		void null_returns_empty_optional() {
			// Given
			String path = null;

			// When
			Optional<List<String>> resultOpt = FolderPathParser.parsing(path);

			// Then
			assertTrue(resultOpt.isEmpty());
		}

		@Test
		@DisplayName("blank이면 Optional.empty")
		void blank_returns_empty_optional() {
			// Given
			String path = "   ";

			// When
			Optional<List<String>> resultOpt = FolderPathParser.parsing(path);

			// Then
			assertTrue(resultOpt.isEmpty());
		}
	}

	@Nested
	@DisplayName("경로 포맷 오류(시작/끝 '/' 불일치)")
	class InvalidInputs_Format {

		@ParameterizedTest
		@ValueSource(strings = {"1/2/3/", "/1/2/3", "1/2/3"})
		@DisplayName("시작/끝 '/' 불일치면 Optional.empty")
		void invalid_format_returns_empty_optional(String path) {
			// When
			Optional<List<String>> resultOpt = FolderPathParser.parsing(path);

			// Then
			assertTrue(resultOpt.isEmpty());
		}
	}

	@Nested
	@DisplayName("비정상 경로('//' 포함) 차단")
	class InvalidInputs_DoubleSlash {

		@Test
		@DisplayName("/1///2/3/ 는 '//' 포함으로 Optional.empty")
		void triple_slash_returns_empty_optional() {
			// Given
			String path = "/1///2/3/";

			// When
			Optional<List<String>> resultOpt = FolderPathParser.parsing(path);

			// Then
			assertTrue(resultOpt.isEmpty());
		}

		@Test
		@DisplayName("// 로 시작하는 경로는 Optional.empty")
		void starts_with_double_slash_returns_empty_optional() {
			// Given
			String path = "//1/2/3/";

			// When
			Optional<List<String>> resultOpt = FolderPathParser.parsing(path);

			// Then
			assertTrue(resultOpt.isEmpty());
		}

		@Test
		@DisplayName("/1/2/3// 는 Optional.empty (끝에 // 포함)")
		void ends_with_double_slash_returns_empty_optional() {
			// Given
			String path = "/1/2/3//";

			// When
			Optional<List<String>> resultOpt = FolderPathParser.parsing(path);

			// Then
			assertTrue(resultOpt.isEmpty());
		}
	}

	@Nested
	@DisplayName("리턴 규약(부작용 없음)")
	class ReturnContract {

		@Test
		@DisplayName("정상 입력이면 Optional은 비어있지 않다")
		void valid_input_optional_present() {
			// Given
			String path = "/1/2/3/";

			// When
			Optional<List<String>> resultOpt = FolderPathParser.parsing(path);

			// Then
			assertTrue(resultOpt.isPresent());
		}

		@Test
		@DisplayName("같은 입력이면 항상 같은 결과를 반환한다")
		void deterministic() {
			// Given
			String path = "/a/b/c/";

			// When
			Optional<List<String>> r1 = FolderPathParser.parsing(path);
			Optional<List<String>> r2 = FolderPathParser.parsing(path);

			// Then
			assertEquals(r1, r2);
		}
	}
}
