package com.woowacamp.storage.domain.folder.repository;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.woowacamp.storage.domain.folder.entity.FolderMetadata;

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
}
