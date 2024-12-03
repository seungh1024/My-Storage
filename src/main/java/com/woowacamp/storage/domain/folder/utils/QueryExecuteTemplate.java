package com.woowacamp.storage.domain.folder.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
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

	/**
	 * select로 실행한 결과를 모아서 ID로 가공 후, 한 번만 비즈니스 로직을 수행
	 * 용량 업데이트, 권한 수정 등 조회한 여러 값들을 하나의 값으로 변경하기 위해 사용
	 *
	 * parentId의 null 이슈로 ID로 가공하여 ID 기반으로 탐색을 진행
	 */
	public static <T, ID> void selectFilesAndBatchExecute(ID startFolderId, Function<ID, List<T>> selectFunction,
		Function<List<T>, List<ID>> addFunction, Consumer<List<ID>> resultConsumer) {

		Stack<ID> stack = new Stack<>();
		List<ID> idList = new ArrayList<>();
		idList.add(startFolderId);

		do {
			log.info("[Stack] {}", stack);
			List<T> selectList = selectFunction.apply(stack.size() > 0 ? stack.pop() : null);
			log.info("[After Stack] {}", stack);
			List<ID> folderPkList = addFunction.apply(selectList);
			log.info("[FolderPkList] {}", folderPkList);
			stack.addAll(folderPkList);
			idList.addAll(folderPkList);

		} while (!stack.isEmpty());
		resultConsumer.accept(idList);
	}
}
