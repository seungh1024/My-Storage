package com.woowacamp.storage.global.util;

import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
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
	private final FolderMetadataJpaRepository folderMetadataJpaRepository;

	public record CreatePathValidationResult(
		String projectedParentIdFullPath,
		String projectedParentNameFullPath,
		Long reservationFolderId,
		Integer reservationProjectedMaxNamePathLength
	) {
		public boolean hasReservation() {
			return reservationFolderId != null && reservationProjectedMaxNamePathLength != null;
		}
	}

	public void validateParentsFolderLock(Long rootId, List<Long> sourceParentIds, List<Long> targetParentIds) {
		List<Long> lockNames = Stream.concat(sourceParentIds.stream(), targetParentIds.stream())
			.distinct()
			.toList();
		List<Long> parentsLockInfo = folderOperationStateService.findOperationFolderIdsByRootIdAndFolderIds(
			rootId, lockNames);

		if (parentsLockInfo.size() > 0) {
			throw ErrorCode.PARENT_LOCKED.baseException(
				StorageStringUtil.format("Failed to move folder. rootId: {}, parents lock Info: {}",
					rootId, parentsLockInfo));
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
	 * create 시점 기준 상위 ACTIVE MOVE를 고려해
	 * 1) 최종 parent 경로 계산
	 * 2) 경로 길이 검증
	 * 3) projectedMaxNamePathLength 갱신 후보 계산
	 * 을 한 번에 수행한다.
	 */
	public CreatePathValidationResult validateAndResolveForCreate(FolderMetadata parentFolder, int folderNameLength,
		int maxPathLength) {
		List<Long> parentPathIdsInOrder = parsePathIdsInOrder(parentFolder.getIdFullPath());
		List<Long> parentIds = parentPathIdsInOrder.stream().distinct().toList();
		Long rootId = resolveRootId(parentFolder);
		ActiveMoveReservationProjection activeMoveState = folderOperationStateService.findSingleActiveMoveOperationByRootIdAndFolderIds(
				rootId,
				parentIds
			)
			.orElse(null);
		if (activeMoveState == null) {
			int createdPathLength = parentFolder.getNamePathLength() + folderNameLength + 1;
			validateCreatedPathLength(createdPathLength, maxPathLength);
			return new CreatePathValidationResult(
				parentFolder.getIdFullPath(),
				parentFolder.getNameFullPath(),
				null,
				null
			);
		}

		FolderMetadata movingRootFolder = folderMetadataJpaRepository.findByIdNotDeleted(activeMoveState.getFolderId())
			.orElseThrow(() -> ErrorCode.FOLDER_NOT_FOUND.baseException(
				StorageStringUtil.format("Moving root folder metadata not found. folderId={}", activeMoveState.getFolderId())));

		List<String> parentPathNamesInOrder = parsePath(parentFolder.getNameFullPath());
		if (parentPathNamesInOrder.size() != parentPathIdsInOrder.size()) {
			throw ErrorCode.FOLDER_PATH_ERROR.baseException(
				StorageStringUtil.format(
					"Path token mismatch. parentFolderId={}, idPathTokenSize={}, namePathTokenSize={}",
					parentFolder.getId(),
					parentPathIdsInOrder.size(),
					parentPathNamesInOrder.size()
				));
		}

		// 서브 트리의 루트 기준으로 하위 Id 및 Folder name 경로 생성 -> 이동 전 상위 경로를 제거하는 작업
		int movingRootIndex = findPathIndexOrThrow(parentPathIdsInOrder, activeMoveState.getFolderId());
		String suffixIdPath = buildSuffixPathByTokens(parentPathIdsInOrder, movingRootIndex);
		String suffixNamePath = buildSuffixPathByTokens(parentPathNamesInOrder, movingRootIndex);

		// 이동 후 경로 생성
		String projectedParentIdFullPath = movingRootFolder.getIdFullPath() + suffixIdPath;
		String projectedParentNameFullPath = movingRootFolder.getNameFullPath() + suffixNamePath;
		int createdPathLengthAfterMove = projectedParentNameFullPath.length() + folderNameLength + 1;
		int nextProjectedMaxNamePathLength = Math.max(
			activeMoveState.getProjectedMaxNamePathLength(),
			createdPathLengthAfterMove
		);
		validateCreatedPathLengthForMove(activeMoveState.getFolderId(), nextProjectedMaxNamePathLength,
			createdPathLengthAfterMove, maxPathLength);

		Long reservationFolderId = null;
		Integer reservationProjectedMaxNamePathLength = null;
		if (nextProjectedMaxNamePathLength > activeMoveState.getProjectedMaxNamePathLength()) {
			reservationFolderId = activeMoveState.getFolderId();
			reservationProjectedMaxNamePathLength = nextProjectedMaxNamePathLength;
		}

		return new CreatePathValidationResult(
			projectedParentIdFullPath,
			projectedParentNameFullPath,
			reservationFolderId,
			reservationProjectedMaxNamePathLength
		);
	}

	private int findPathIndexOrThrow(List<Long> parentPathIdsInOrder, Long movingRootFolderId) {
		int movingRootIndex = parentPathIdsInOrder.indexOf(movingRootFolderId);
		if (movingRootIndex < 0) {
			throw ErrorCode.FOLDER_PATH_ERROR.baseException(
				StorageStringUtil.format("Moving root not found in parent path. movingRootFolderId={}", movingRootFolderId));
		}
		return movingRootIndex;
	}

	private void validateCreatedPathLength(int createdPathLength, int maxPathLength) {
		if (createdPathLength > maxPathLength) {
			throw ErrorCode.EXCEED_MAX_PATH_LENGTH.baseException(
				StorageStringUtil.format("Total Path is too long. path length: {}", createdPathLength));
		}
	}

	private void validateCreatedPathLengthForMove(Long movingFolderId, int nextProjectedMaxNamePathLength,
		int createdPathLengthAfterMove, int maxPathLength) {
		if (nextProjectedMaxNamePathLength > maxPathLength) {
			throw ErrorCode.EXCEED_MAX_PATH_LENGTH.baseException(
				StorageStringUtil.format(
					"Total Path is too long. movingFolderId={}, projectedMaxNamePathLength={}, createdPathLengthAfterMove={}",
					movingFolderId,
					nextProjectedMaxNamePathLength,
					createdPathLengthAfterMove
				));
		}
	}

	private List<Long> parsePathIdsInOrder(String idFullPath) {
		return parsePath(idFullPath)
			.stream()
			.map(Long::parseLong)
			.toList();
	}

	private List<String> parsePath(String fullPath) {
		return FolderPathParser.parsing(fullPath)
			.orElseThrow(() -> ErrorCode.FOLDER_PATH_ERROR.baseException(
				StorageStringUtil.format(PATH_PARSE_FAILED, fullPath)));
	}

	private String buildSuffixPathByTokens(List<?> tokens, int fromExclusive) {
		StringBuilder suffixPath = new StringBuilder();
		for (int i = fromExclusive + 1; i < tokens.size(); i++) {
			suffixPath.append(tokens.get(i)).append('/');
		}
		return suffixPath.toString();
	}

	private Long resolveRootId(FolderMetadata folder) {
		return folder.getRootId() == null ? folder.getId() : folder.getRootId();
	}
}
