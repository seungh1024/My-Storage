package com.woowacamp.storage.domain.folder.utils;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class QueryExecuteTemplate {

	/**
	 * cursor paging으로 select 수행한 결과로 비즈니스 로직을 수행
	 */
	public static <T> void selectFilesAndExecuteWithCursor(int limit, Function<T, List<T>> selectFunction,
		Consumer<List<T>> resultConsumer) {
		List<T> selectList = null;
		do {
			selectList = selectFunction.apply(selectList != null ? selectList.get(selectList.size() - 1) : null);
			log.info("[Select List] {}", selectList);
			if (!selectList.isEmpty()) {
				resultConsumer.accept(selectList);
			}
		} while (selectList.size() >= limit);
	}

}
