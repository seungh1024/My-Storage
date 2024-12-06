package com.woowacamp.storage.global.background;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.file.service.FileService;
import com.woowacamp.storage.domain.folder.service.FolderService;

import lombok.RequiredArgsConstructor;

/**
 * 파일 이동과 삭제 중 발생하는 고아 파일을 찾아서 제거하는 클래스
 */
@Component
@RequiredArgsConstructor
public class OrphanFileManager {
	private final FileService fileService;
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
		folderService.findOrphanFolderAndSoftDelete();
	}

	/**
	 * 고아 파일을 찾아서 hard delete를 진행한다
	 */
	@Scheduled(fixedDelay = FIND_DELAY)
	private void orphanFileFinder() {
		fileService.findOrphanFileAndHardDelete();
	}
}
