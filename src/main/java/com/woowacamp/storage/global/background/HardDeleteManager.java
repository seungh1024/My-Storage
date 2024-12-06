package com.woowacamp.storage.global.background;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataRepository;
import com.woowacamp.storage.domain.folder.utils.QueryExecuteTemplate;

import lombok.RequiredArgsConstructor;

/**
 * soft delete한 폴더와 파일을 제거하는 클래스
 */
@Component
@RequiredArgsConstructor
public class HardDeleteManager {
	private final FolderMetadataRepository folderMetadataRepository;
	private static final int FIND_DELAY = 1000 * 60 * 60 * 60;

	@Value("${constant.batchSize}")
	private int pageSize;
	
	@Scheduled(fixedDelay = FIND_DELAY)
	private void folderDeleteScheduler() {
		QueryExecuteTemplate.<FolderMetadata>selectFilesAndExecuteWithCursor(pageSize,
			findFolder -> folderMetadataRepository.findSoftDeletedFolderWithLastId(findFolder == null ? null : findFolder.getId(),
				pageSize), folderMetadataList -> folderMetadataRepository.deleteAll(folderMetadataList));
	}

}
