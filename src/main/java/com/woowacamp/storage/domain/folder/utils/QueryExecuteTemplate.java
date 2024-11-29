package com.woowacamp.storage.domain.folder.utils;

import java.util.List;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

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
	public static <T> void selectFilesAndExecute(
		Function<Long ,List<T>> selectFunction,
		Function<List<T>, List<Long>> addFunction,
		Consumer<List<T>> resultConsumer
	){
		Stack<Long> stack = new Stack<>();
		do{
			log.info("[Stack] {}",stack);
			List<T> selectList = selectFunction.apply(stack.size() > 0 ? stack.pop() : null);
			log.info("[After Stack] {}",stack);
			List<Long> folderPkList = addFunction.apply(selectList);
			log.info("[FolderPkList] {}",folderPkList);
			stack.addAll(folderPkList);
			resultConsumer.accept(selectList);

		}while(!stack.isEmpty() && stack.size()<10);
	}
}
