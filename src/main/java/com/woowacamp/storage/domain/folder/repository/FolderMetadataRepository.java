package com.woowacamp.storage.domain.folder.repository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Repository;

import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.global.constant.CommonConstant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Repository
@RequiredArgsConstructor
public class FolderMetadataRepository {
	private final FolderMetadataJpaRepository folderMetadataJpaRepository;

	public List<FolderMetadata> findByParentFolderIdWithLastId(long parentFolderId, Long lastId, int size) {
		log.info("[Find Folder With Cursor] parent = {}, last = {} , size = {}", parentFolderId, lastId, size);
		if (lastId == null) {
			return folderMetadataJpaRepository.findByParentFolderId(parentFolderId, size);
		}

		return folderMetadataJpaRepository.findByParentFolderIdWithLastId(parentFolderId, lastId, size);
	}

	public List<FolderMetadata> findSoftDeletedFolderWithLastId(Long lastId, int size) {
		if (lastId == null) {
			return folderMetadataJpaRepository.findSoftDeletedFolder(size);
		}
		return folderMetadataJpaRepository.findSoftDeletedFolderWithLastId(lastId, size);
	}

	public List<FolderMetadata> findSoftDeletedFolderWithLastIdAndDuration(Long lastId, int size) {
		if (lastId == null) {
			return folderMetadataJpaRepository.findSoftDeletedFolder(size, LocalDateTime.now().minusDays(CommonConstant.hardDeleteDuration));
		}
		return folderMetadataJpaRepository.findSoftDeletedFolderWithLastId(lastId, size, LocalDateTime.now().minusDays(CommonConstant.hardDeleteDuration));
	}

	public void deleteAll(List<FolderMetadata> folderMetadataList) {
		folderMetadataJpaRepository.deleteAllByIdInBatch(folderMetadataList.stream().map(FolderMetadata::getId).toList());
	}

}
