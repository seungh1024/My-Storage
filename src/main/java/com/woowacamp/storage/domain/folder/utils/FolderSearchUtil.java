package com.woowacamp.storage.domain.folder.utils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class FolderSearchUtil {

	private final FolderMetadataJpaRepository folderMetadataRepository;

	/**
	 * 현재 folder에서 rootFolder까지 경로를 구하는 함수
	 */
	public Set<FolderMetadata> getPathToRoot(Long folderId) {
		Set<FolderMetadata> path = new LinkedHashSet<>();
		FolderMetadata current = folderMetadataRepository.findByIdForUpdate(folderId)
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);

		while (current != null) {
			path.add(current);
			if (current.getParentFolderId() == null) {
				break;
			}
			current = folderMetadataRepository.findByIdForUpdate(current.getParentFolderId())
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
				folderMetadataRepository.updateFolderInfo(-fileSize, now, source.getId());
			}
		}
		for (var target : targetPath) {
			if (target.equals(commonAncestor)) {
				break;
			}
			folderMetadataRepository.updateFolderInfo(fileSize, now, target.getId());
		}
	}

	/**
	 * 폴더를 무제한 생성하는 것을 방지하기 위해 깊이를 구하는 메소드
	 */
	public int getFolderDepth(long folderId) {
		int depth = 1;
		Long currentFolderId = folderId;
		while (true) {
			Optional<Long> parentFolderIdById = folderMetadataRepository.findParentFolderIdById(currentFolderId);
			if (parentFolderIdById.isEmpty()) {
				break;
			}
			currentFolderId = parentFolderIdById.get();
			depth++;
		}
		return depth;
	}
}
