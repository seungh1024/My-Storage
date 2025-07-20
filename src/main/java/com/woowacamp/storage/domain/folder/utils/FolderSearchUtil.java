package com.woowacamp.storage.domain.folder.utils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.service.RedisLockService;
import com.woowacamp.storage.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class FolderSearchUtil {

	private final FolderMetadataJpaRepository folderMetadataJpaRepository;
	private final RedisLockService redisLockService;

	/**
	 * 현재 folder에서 rootFolder까지 경로를 구하는 함수
	 */
	public Set<FolderMetadata> getPathToRoot(Long folderId) {
		Set<FolderMetadata> path = new LinkedHashSet<>();
		FolderMetadata current = folderMetadataJpaRepository.findByIdForUpdate(folderId)
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);

		while (current != null) {
			path.add(current);
			if (current.getParentFolderId() == null) {
				break;
			}
			current = folderMetadataJpaRepository.findByIdForUpdate(current.getParentFolderId())
				.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);
		}
		return path;
	}

	/**
	 * source, target path로부터 공통 조상 폴더를 구하는 함수
	 */
	public FolderMetadata getCommonAncestor(Set<FolderMetadata> sourcePath, Set<FolderMetadata> targetPath) {
		FolderMetadata commonAncestor = null;
		for (var source : sourcePath) {
			if (targetPath.contains(source)) {
				commonAncestor = source;
				break;
			}
		}
		return commonAncestor;
	}

	/**
	 * sourceFolder부터 rootFolder까지, targetFoldder부터 rootFolder까지 모든 정보를 수정한다.
	 * sourceFolder부터 commonAncestorFolder 전까지는 folderSize와 updatedAt 수정
	 * targetFolder부터 commonAncestorFolder 전까지는 folderSize와 updatedAt 수정
	 * commonAncestorFolder부터 rootFolder 까지는 updatedAt만 수정
	 */
	public void updateFolderPath(Set<FolderMetadata> sourcePath, Set<FolderMetadata> targetPath,
		FolderMetadata commonAncestor, long fileSize) {
		LocalDateTime now = LocalDateTime.now();
		boolean isExistCommonAncestor = false;
		for (var source : sourcePath) {
			if (source.equals(commonAncestor)) {
				isExistCommonAncestor = true;
			}
			if (!isExistCommonAncestor) {
				folderMetadataJpaRepository.updateFolderInfo(-fileSize, now, source.getId());
			}
		}
		for (var target : targetPath) {
			if (target.equals(commonAncestor)) {
				break;
			}
			folderMetadataJpaRepository.updateFolderInfo(fileSize, now, target.getId());
		}
	}

	/**
	 * 폴더를 무제한 생성하는 것을 방지하기 위해 깊이를 구하는 메소드
	 */
	public int getFolderDepth(long folderId) {
		int depth = 1;
		Long currentFolderId = folderId;
		while (true) {
			Optional<Long> parentFolderIdById = folderMetadataJpaRepository.findParentFolderIdById(currentFolderId);
			if (parentFolderIdById.isEmpty()) {
				break;
			}
			currentFolderId = parentFolderIdById.get();
			depth++;
		}
		return depth;
	}

	/**
	 * 상위 폴더를 재귀적으로 탐색하며 이동이나 삭제 작업이 존재하지 않는지 확인하는 메서드
	 */
	public int folderLockCheck(Long folderId, Long invalidId) {
		int depth = 0;
		FolderMetadata folderMetadata = folderMetadataJpaRepository.findById(folderId)
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);
		Long parentId = folderMetadata.getParentFolderId();

		while(parentId!=null){
			if (parentId != null && parentId == invalidId) {
				throw ErrorCode.FOLDER_MOVE_NOT_AVAILABLE.baseException();
			}

			FolderMetadata parentFolder = folderMetadataJpaRepository.findById(parentId)
				.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);

			// 부모가 이동이나 삭제 작업 중인지 확인 후 이미 진행 중이라면 예외 발생
			// boolean checkLockResult = redisLockService.checkLock(parentFolder.getId().toString());
			// if (checkLockResult) {
			// 	throw ErrorCode.PARENT_LOCKED.baseException();
			// }
			if (parentFolder.isUpdating()) {
				throw ErrorCode.PARENT_LOCKED.baseException("locked id = "+parentFolder.getId());
			}

			parentId = parentFolder.getParentFolderId(); // 부모 갱신하여 상위로 탐색
			depth++;
		}

		return depth;
	}

	public int folderDepthCheck(Long folderId) {
		int depth = 0;
		FolderMetadata folderMetadata = folderMetadataJpaRepository.findById(folderId)
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);
		Long parentId = folderMetadata.getParentFolderId();
		while(parentId!=null){

			FolderMetadata parentFolder = folderMetadataJpaRepository.findById(parentId)
				.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);

			parentId = parentFolder.getParentFolderId(); // 부모 갱신하여 상위로 탐색
			depth++;
		}

		return depth;
	}
}
