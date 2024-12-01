package com.woowacamp.storage.domain.folder.service;

import java.util.Objects;
import java.util.concurrent.Executor;

import org.springframework.stereotype.Service;

import com.woowacamp.storage.domain.folder.background.BackgroundJob;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataRepository;
import com.woowacamp.storage.domain.folder.utils.QueryExecuteTemplate;

import lombok.RequiredArgsConstructor;

/**
 * 메타데이터를 후처리하는 클래스
 */
@Service
@RequiredArgsConstructor
public class MetadataService {
	private final Executor metadataThreadPoolExecutor;
	private final FolderMetadataRepository folderMetadataRepository;
	private final BackgroundJob backgroundJob;

	public void calculateSize(long folderId, long folderSize, boolean isPlus) {
		metadataThreadPoolExecutor.execute(() -> QueryExecuteTemplate.<FolderMetadata, Long>selectFilesAndBatchExecute(
			findFolderId -> folderMetadataRepository.findById(findFolderId == null ? folderId : findFolderId)
				.stream()
				.toList(),
			folderMetadataList -> folderMetadataList.stream()
				.map(FolderMetadata::getParentFolderId)
				.filter(Objects::nonNull)
				.toList(),
			folderMetadataList -> backgroundJob.addForUpdateFile(isPlus ? folderSize : -folderSize, folderMetadataList,
				(changeValue, pkList) -> folderMetadataRepository.updateAllSizeByIdInBatch(changeValue, pkList))

		));
	}
}
