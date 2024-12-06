package com.woowacamp.storage.global.background;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataRepository;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataRepository;
import com.woowacamp.storage.domain.folder.service.FolderService;
import com.woowacamp.storage.domain.folder.utils.QueryExecuteTemplate;

import lombok.RequiredArgsConstructor;

/**
 * 파일 이동과 삭제 중 발생하는 고아 파일을 찾아서 제거하는 클래스
 */
@Component
@RequiredArgsConstructor
public class OrphanFileManager {
	private final FolderMetadataRepository folderMetadataRepository;
	private final FileMetadataRepository fileMetadataRepository;
	private final FolderService folderService;

	private static final int FIND_DELAY = 1000 * 60;

	@Value("${constant.batchSize}")
	private int pageSize;

	/**
	 * 이미 soft delete가 완료된 폴더를 기준으로 삭제가 되지 않은 하위 폴더 및 파일을 탐색
	 * 이후 마찬가지로 폴더는 soft delete, 파일은 hard delete를 진행한다
	 */
	@Scheduled(fixedDelay = FIND_DELAY)
	private void orphanFolderFinder() {
		System.out.println("Orphan Find Start");
		QueryExecuteTemplate.<FolderMetadata>selectFilesAndExecuteWithCursor(pageSize,
			findFolder -> folderMetadataRepository.findSoftDeletedFolderWithLastId(findFolder == null ? null : findFolder.getId(), pageSize),
			folderMetadataList -> folderMetadataList.forEach(folder->folderService.deleteFolderTree(folder)));
	}

	/**
	 * 고아 파일을 찾아서 hard delete를 진행한다
	 */
	@Scheduled(fixedDelay = FIND_DELAY)
	private void orphanFileFinder() {
		QueryExecuteTemplate.<FileMetadata>selectFilesAndExecuteWithCursor(pageSize,
			findFile -> fileMetadataRepository.findFileMetadataByLastId(
				findFile == null ? 0 : findFile.getParentFolderId(), findFile == null ? null : findFile.getId(), pageSize),
			fileMetadataList -> fileMetadataRepository.deleteAll(fileMetadataList));
	}
}
