package com.woowacamp.storage.domain.file.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Repository;

import com.woowacamp.storage.domain.file.entity.FileMetadata;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class FileMetadataRepository {
	private final FileMetadataJpaRepository fileMetadataJpaRepository;

	public List<FileMetadata> findFileMetadataByLastId(long parentFolderId, Long lastId, int size) {
		if (lastId == null) {
			return fileMetadataJpaRepository.findByParentFolderId(parentFolderId, size);
		}
		return fileMetadataJpaRepository.findByParentFolderIdWithLastId(parentFolderId, lastId, size);
	}
	public void deleteAll(List<FileMetadata> fileMetadataList) {
		fileMetadataJpaRepository.deleteAllByIdInBatch(fileMetadataList.stream().map(FileMetadata::getId).toList());
	}

	public List<FileMetadata> findOrphanFileByLastId(long lastParentId, Long lastId, int size) {
		if (lastId == null) {
			fileMetadataJpaRepository.findOrphanFileList(size);
		}
		return fileMetadataJpaRepository.findOrhanFileListWithLastId(lastParentId, lastId, size);
	}

	public List<FileMetadata> findUploadFailureFileByLastId(Long lastId, int size) {
		if (lastId == null) {
			return fileMetadataJpaRepository.findUploadFailureList(size);
		}

		return fileMetadataJpaRepository.findUploadFailureListWithLastId(lastId, size);
	}

	public List<FileMetadata> findUploadPendingFileByLastId(Long lastId, int size, LocalDateTime timeLimit) {
		if (lastId == null) {
			return fileMetadataJpaRepository.findUploadPendingList(size, timeLimit);
		}

		return fileMetadataJpaRepository.findUploadPendingListWithLastId(lastId, size, timeLimit);
	}
}
