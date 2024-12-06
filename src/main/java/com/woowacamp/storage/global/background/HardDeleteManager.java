package com.woowacamp.storage.global.background;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.folder.service.FolderService;

import lombok.RequiredArgsConstructor;

/**
 * soft delete한 폴더와 파일을 제거하는 클래스
 */
@Component
@RequiredArgsConstructor
public class HardDeleteManager {
	private final FolderService folderService;
	private static final int FIND_DELAY = 1000 * 60 * 60 * 60;

	@Value("${constant.batchSize}")
	private int pageSize;

	@Scheduled(fixedDelay = FIND_DELAY)
	private void folderDeleteScheduler() {
		folderService.doHardDelete();
	}

}
