package com.woowacamp.storage.global.background;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataRepository;
import com.woowacamp.storage.domain.file.service.PresignedUrlService;
import com.woowacamp.storage.domain.folder.utils.QueryExecuteTemplate;
import com.woowacamp.storage.global.constant.CommonConstant;

import lombok.RequiredArgsConstructor;

/**
 * 업로드에 실패한 파일을 처리하는 메서드
 * 원격 저장소에 존재하는 파일을 제거 후, 메타데이터 정보를 제거한다.
 */
@Component
@RequiredArgsConstructor
public class UploadFailManager {
	private final FileMetadataRepository fileMetadataRepository;
	private final BackgroundJob backgroundJob;
	private final PresignedUrlService presignedUrlService;
	private final Executor deleteRgwFileThreadPoolExecutor;
	private final static int DELETE_DELAY = 1000 * 60;

	@Value("${constant.batchSize}")
	private int pageSize;

	@Scheduled(fixedDelay = DELETE_DELAY)
	private void uploadFailureFileScheduler() {
		QueryExecuteTemplate.<FileMetadata>selectFilesAndExecuteWithCursor(pageSize,
			findFile -> fileMetadataRepository.findUploadFailureFileByLastId(
				findFile == null ? null : findFile.getId(), pageSize),
			findFileList -> findFileList.forEach(this::deleteRgwFile)
		);
	}

	/**
	 * pending 상태로 30분이 지속된 파일은 오류가 생겨 업로드가 완료되지 않은 것으로 판단하여 제거하는 스케줄러
	 * 현재 인당 제공하는 저장 공간이 적어서 30분이 지나서 삭제하는 것은 문제가 없을 것 같다.
	 */
	@Scheduled(fixedDelay = DELETE_DELAY)
	private void tooMuchPendingFileScheduler() {
		LocalDateTime timeLimit = LocalDateTime.now().minusMinutes(CommonConstant.maxPendingDuration);
		QueryExecuteTemplate.<FileMetadata>selectFilesAndExecuteWithCursor(pageSize,
			findFile -> fileMetadataRepository.findUploadPendingFileByLastId(
				findFile == null ? null : findFile.getId(), pageSize, timeLimit),
			findFileList -> findFileList.forEach(this::deleteRgwFile)
		);
	}

	private void deleteRgwFile(FileMetadata fileMetadata) {
		deleteRgwFileThreadPoolExecutor.execute(() -> {
			presignedUrlService.deleteFile(fileMetadata.getUuidFileName());
			backgroundJob.addForDeleteFile(fileMetadata);
		});
	}
}
