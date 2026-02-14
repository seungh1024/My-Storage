package com.woowacamp.storage.global.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.utils.FolderPathParser;
import com.woowacamp.storage.domain.folderoperation.repository.projection.ActiveMoveReservationProjection;
import com.woowacamp.storage.domain.folderoperation.service.FolderOperationStateService;
import com.woowacamp.storage.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ValidateParentsUtil {
	private static final String PATH_PARSE_FAILED = "Failed to parsing path, id full path: {}";

	private final FolderOperationStateService folderOperationStateService;

	/**
	 * 상위에 이미 작업 중인 폴더 유무를 확인하는 메서드. 존재하면 에러 발생.
	 * 전달받은 폴더 기준으로 전체 경로에 대해 검증한다.
	 * @param sourceFolder
	 * @param targetFolder
	 */
	public void validateParentsFolderLock(FolderMetadata sourceFolder, FolderMetadata targetFolder) {
		List<String> sourceParents = FolderPathParser.parsing(sourceFolder.getIdFullPath())
			.orElseThrow(() -> ErrorCode.FOLDER_PATH_ERROR.baseException(
				StorageStringUtil.format(PATH_PARSE_FAILED, sourceFolder.getIdFullPath())));
		List<String> targetParents = FolderPathParser.parsing(targetFolder.getIdFullPath())
			.orElseThrow(() -> ErrorCode.FOLDER_PATH_ERROR.baseException(
				StorageStringUtil.format(PATH_PARSE_FAILED, targetFolder.getIdFullPath())));

		List<Long> lockNames = Stream.concat(sourceParents.stream(), targetParents.stream())
			.distinct()
			.map(Long::parseLong)
			.toList();

		Long rootId = resolveRootId(sourceFolder);
		List<Long> parentsLockInfo = folderOperationStateService.findOperationFolderIdsByRootIdAndFolderIds(
			rootId, lockNames);

		if (parentsLockInfo.size() > 0) {
			throw ErrorCode.PARENT_LOCKED.baseException(
				StorageStringUtil.format("Failed to move folder, sourceId: {}, targetId: {}, parents lock Info: {}",
					sourceFolder.getId(), targetFolder.getId(), parentsLockInfo));
		}
	}

	public void validateParentsFolderLock(FolderMetadata targetFolder) {
		List<String> targetParents = FolderPathParser.parsing(targetFolder.getIdFullPath())
			.orElseThrow(() -> ErrorCode.FOLDER_PATH_ERROR.baseException(
				StorageStringUtil.format(PATH_PARSE_FAILED, targetFolder.getIdFullPath())));

		List<Long> lockNames = targetParents.stream()
			.distinct()
			.map(Long::parseLong)
			.toList();

		Long rootId = resolveRootId(targetFolder);
		List<Long> parentsLockInfo = folderOperationStateService.findOperationFolderIdsByRootIdAndFolderIds(
			rootId, lockNames);

		if (parentsLockInfo.size() > 0) {
			throw ErrorCode.PARENT_LOCKED.baseException(
				StorageStringUtil.format("Failed to move folder, targetId: {}, parents lock Info: {}",
					targetFolder.getId(), parentsLockInfo));
		}
	}

	/**
	 * create 시 parent 상위 경로의 ACTIVE MOVE 상태를 기준으로 path length를 검증하고
	 * 필요한 경우 예약 최대 길이(projectedMaxNamePathLength)를 갱신한다.
	 */
	public void validateMaxNamePathLengthAndReserveForCreate(FolderMetadata parentFolder, int folderNameLength,
		int maxPathLength) {
		int createdPathLength = parentFolder.getNamePathLength() + folderNameLength + 1;
		if (createdPathLength >= maxPathLength) {
			throw ErrorCode.EXCEED_MAX_PATH_LENGTH.baseException(
				StorageStringUtil.format("Total Path is too long. path length: {}", createdPathLength));
		}

		List<Long> parentIds = parseParentIds(parentFolder);
		Long rootId = resolveRootId(parentFolder);
		List<ActiveMoveReservationProjection> activeMoveStates = folderOperationStateService.findActiveMoveOperationFoldersByRootIdAndFolderIds(
			rootId, parentIds);
		Map<Long, Integer> projectedMaxNamePathLengthByFolderId = new HashMap<>();

		for (ActiveMoveReservationProjection activeMoveState : activeMoveStates) {
			int nextProjectedMaxNamePathLength = Math.max(
				activeMoveState.getProjectedMaxNamePathLength(),
				createdPathLength
			);

			if (nextProjectedMaxNamePathLength >= maxPathLength) {
				throw ErrorCode.EXCEED_MAX_PATH_LENGTH.baseException(
					StorageStringUtil.format(
						"Total Path is too long. movingFolderId={}, projectedMaxNamePathLength={}, createdPathLength={}",
						activeMoveState.getFolderId(),
						nextProjectedMaxNamePathLength,
						createdPathLength
					));
			}

			if (nextProjectedMaxNamePathLength > activeMoveState.getProjectedMaxNamePathLength()) {
				projectedMaxNamePathLengthByFolderId.merge(
					activeMoveState.getFolderId(),
					nextProjectedMaxNamePathLength,
					Integer::max
				);
			}
		}

		if (!projectedMaxNamePathLengthByFolderId.isEmpty()) {
			folderOperationStateService.batchUpdateActiveMoveProjectedMaxNamePathLengthIfLessThan(
				rootId,
				projectedMaxNamePathLengthByFolderId
			);
		}
	}

	private List<Long> parseParentIds(FolderMetadata folderMetadata) {
		return FolderPathParser.parsing(folderMetadata.getIdFullPath())
			.orElseThrow(() -> ErrorCode.FOLDER_PATH_ERROR.baseException(
				StorageStringUtil.format(PATH_PARSE_FAILED, folderMetadata.getIdFullPath())))
			.stream()
			.distinct()
			.map(Long::parseLong)
			.toList();
	}

	private Long resolveRootId(FolderMetadata folder) {
		return folder.getRootId() == null ? folder.getId() : folder.getRootId();
	}
}
