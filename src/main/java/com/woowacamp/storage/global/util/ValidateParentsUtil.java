package com.woowacamp.storage.global.util;

import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.utils.FolderPathParser;
import com.woowacamp.storage.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ValidateParentsUtil {

	private final FolderMetadataJpaRepository folderMetadataJpaRepository;

	/**
	 * 상위에 이미 작업 중인 폴더 유무를 확인하는 메서드. 존재하면 에러 발생.
	 * @param sourceFolder
	 * @param targetFolder
	 */
	public void validateParentsFolderLock(FolderMetadata sourceFolder, FolderMetadata targetFolder) {
		List<String> sourceParents = FolderPathParser.parsing(sourceFolder.getIdFullPath())
			.orElseThrow(() -> ErrorCode.FOLDER_PATH_ERROR.baseException(
				StorageStringUtil.format("Failed to parsing path, id full path: {}", sourceFolder.getIdFullPath())));
		List<String> targetParents = FolderPathParser.parsing(targetFolder.getIdFullPath())
			.orElseThrow(() -> ErrorCode.FOLDER_PATH_ERROR.baseException(
				StorageStringUtil.format("Failed to parsing path, id full path: {}", targetFolder.getIdFullPath())));

		List<Long> lockNames = Stream.concat(sourceParents.stream(), targetParents.stream())
			.distinct()
			.map(Long::parseLong)
			.toList();

		// 삭제 또는 이동 작업이 선행되고 있으면 예외 발생
		List<Long> parentsLockInfo = folderMetadataJpaRepository.findParentIdsMovingOrDeleted(lockNames);

		if (parentsLockInfo.size() > 0) {
			throw ErrorCode.PARENT_LOCKED.baseException(
				StorageStringUtil.format("Failed to move folder, sourceId: {}, targetId: {}, parents lock Info: {}",
					sourceFolder.getId(), targetFolder.getId(), parentsLockInfo));
		}
	}

	public void validateParentsFolderLock(FolderMetadata targetFolder) {
		List<String> targetParents = FolderPathParser.parsing(targetFolder.getIdFullPath())
			.orElseThrow(() -> ErrorCode.FOLDER_PATH_ERROR.baseException(
				StorageStringUtil.format("Failed to parsing path, id full path: {}", targetFolder.getIdFullPath())));

		List<Long> lockNames = targetParents.stream()
			.distinct()
			.map(Long::parseLong)
			.toList();

		// 삭제 또는 이동 작업이 선행되고 있으면 예외 발생
		List<Long> parentsLockInfo = folderMetadataJpaRepository.findParentIdsDeleted(lockNames);

		if (parentsLockInfo.size() > 0) {
			throw ErrorCode.PARENT_LOCKED.baseException(
				StorageStringUtil.format("Failed to move folder, targetId: {}, parents lock Info: {}", targetFolder.getId(), parentsLockInfo));
		}
	}
}
