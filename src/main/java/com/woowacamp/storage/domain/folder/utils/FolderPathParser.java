package com.woowacamp.storage.domain.folder.utils;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE) // 인스턴스 생성 방지
public class FolderPathParser {

	/**
	 * /pk1/pk2/pk3/ 와 같은 문자열 경로를 파싱해 root를 제외한 pk들만 리턴
	 */
	public static Optional<List<String>> parsing(String path) {
		if (path == null || path.isBlank()){
			return Optional.empty(); // 입력값 오류
		}
		if (!path.startsWith("/") || !path.endsWith("/")){
			return Optional.empty(); // 경로 포맷 이상
		}
		if (path.contains("//")){
			return Optional.empty(); // '///' 같은 비정상 차단
		}

		List<String> idList = Arrays.stream(path.split("/"))
			.filter(s -> !s.isEmpty())
			.toList();

		return Optional.of(idList);
	}
}
