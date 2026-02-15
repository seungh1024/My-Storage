package com.woowacamp.storage.domain.folder.dto.command;

/**
 * 폴더 이동 검증 결과와 이동 루트 반영에 필요한 계산값 묶음.
 *
 * @param parentPathLengthChange source 부모 경로 대비 target 부모 경로 길이 변화량(증가/감소)
 * @param projectedMaxNamePathLength 이동이 완료되었을 때 예상되는 서브트리 최대 name path 길이
 * @param nextSourceIdFullPath 이동 루트에 반영할 다음 id_full_path
 * @param nextSourceNameFullPath 이동 루트에 반영할 다음 name_full_path
 * @param nextSourceNamePathLength 이동 루트에 반영할 다음 name_path_length
 */
public record MovePlan(
	int parentPathLengthChange,
	int projectedMaxNamePathLength,
	String nextSourceIdFullPath,
	String nextSourceNameFullPath,
	int nextSourceNamePathLength
) {
}
