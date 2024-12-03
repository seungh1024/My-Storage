package com.woowacamp.storage.domain.file.repository;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.woowacamp.storage.domain.file.entity.FileMetadata;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class FileMetadataRepository {
	private final FileMetadataJpaRepository fileMetadataJpaRepository;

	public List<FileMetadata> findSoftDeletedFile(Long lastId, int size) {
		if (lastId == null) {
			return fileMetadataJpaRepository.findSoftDeletedFile(size);
		}
		return fileMetadataJpaRepository.findSoftDeletedFileWithLastId(lastId, size);
	}
	public void deleteAll(List<FileMetadata> fileMetadataList) {
		fileMetadataJpaRepository.deleteAllByIdInBatch(fileMetadataList.stream().map(FileMetadata::getId).toList());
	}
}
