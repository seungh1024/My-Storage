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
	 * 필요한 파일을 select 수행한 결과로 addFunction으로 pk만 선별한 후, 해당 결과로 비즈니스 로직을 수행
	 * stack 기반으로 DFS 탐색함
	 *
	 * @param selectFunction
	 * @param addFunction
	 * @param resultConsumer
	 * @param <T>
	 */
	public static <T> void selectFilesAndExecute(Function<Long, List<T>> selectFunction,
		Function<List<T>, List<Long>> addFunction, Consumer<List<T>> resultConsumer) {
		Stack<Long> stack = new Stack<>();
		do {
			log.info("[Stack] {}", stack);
			List<T> selectList = selectFunction.apply(stack.size() > 0 ? stack.pop() : null);
			log.info("[After Stack] {}", stack);
			List<Long> folderPkList = addFunction.apply(selectList);
			log.info("[FolderPkList] {}", folderPkList);
			stack.addAll(folderPkList);
			resultConsumer.accept(selectList);

		} while (!stack.isEmpty());
	}

	/**
	 * select로 실행한 결과를 모아서 한 번만 비즈니스 로직을 수행
	 * 용량 업데이트, 권한 수정 등 조회한 여러 값들을 하나의 값으로 변경하기 위해 사용
	 *
	 * @param selectFunction
	 * @param addFunction
	 * @param resultConsumer
	 * @param <T>
	 */
	public static <T> void selectFilesAndBatchExecute(Function<Long, List<T>> selectFunction,
		Function<List<T>, List<Long>> addFunction, Consumer<List<Long>> resultConsumer){
		Stack<Long> stack = new Stack<>();
		List<Long> idList = new ArrayList<>();
		do {
			log.info("[Stack] {}", stack);
			List<T> selectList = selectFunction.apply(stack.size() > 0 ? stack.pop() : null);
			log.info("[After Stack] {}", stack);
			List<Long> folderPkList = addFunction.apply(selectList);
			log.info("[FolderPkList] {}", folderPkList);
			stack.addAll(folderPkList);
			idList.addAll(folderPkList);

		} while (!stack.isEmpty());
		resultConsumer.accept(idList);
	}
}
