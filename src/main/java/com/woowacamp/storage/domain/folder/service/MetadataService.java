package com.woowacamp.storage.domain.folder.service;

import java.util.concurrent.Executor;

import org.springframework.stereotype.Service;

import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 메타데이터를 후처리하는 클래스
 */
@Service
@RequiredArgsConstructor
public class MetadataService {
	private final Executor metadataThreadPoolExecutor;
	private final FolderMetadataJpaRepository folderMetadataJpaRepository;
	private final FileMetadataJpaRepository fileMetadataJpaRepository;

	/**
	 * 상위 폴더를 탐색하며 현재 폴더의 사이즈를 계산하고 업데이트
	 */
	public void calculateSize(long folderId) {
		metadataThreadPoolExecutor.execute(() -> {
			FolderMetadata folderMetadata = folderMetadataJpaRepository.findByParentId(folderId)
				.orElseThrow(()->ErrorCode.FOLDER_NOT_FOUND.baseException("폴더 용량 계산 중 예외 발생"));

			long totalSize = folderMetadataJpaRepository.sumChildFolderSize(folderMetadata.getId()).orElse(0L)
				+ fileMetadataJpaRepository.sumChildFileSize(folderMetadata.getId()).orElse(0L);
			folderMetadataJpaRepository.updateFolderSize(totalSize, folderMetadata.getId());

			if (folderMetadata.getParentFolderId() != null) {
				calculateSize(folderMetadata.getParentFolderId());
			}
		});
	}
}
