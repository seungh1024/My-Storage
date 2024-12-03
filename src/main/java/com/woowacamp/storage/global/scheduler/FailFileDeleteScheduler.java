package com.woowacamp.storage.global.scheduler;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FAIL 인 파일의 썸네일과 실제 파일데이터를 S3에서 삭제하는 스케쥴러
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FailFileDeleteScheduler {
	public static final int DELAY = 1000 * 30;
	private final AmazonS3 amazonS3;
	private final FileMetadataJpaRepository fileMetadataJpaRepository;
	@Value("${cloud.aws.credentials.bucketName}")
	private String BUCKET_NAME;

	/**
	 * 업로드에 실패하여 상태가 FAIL 인 파일의 실제 데이터와 썸네일 데이터를 삭제하는 로직입니다.
	 */
	@Scheduled(fixedDelay = DELAY)
	public void deleteFailFiles() {
		List<FileMetadata> failFiles = fileMetadataJpaRepository.findFailedFileMetadata();
		if (failFiles.isEmpty()) {
			return;
		}

		failFiles.forEach(fileMetadata -> {
			try {
				// s3 에서 파일 데이터와 썸네일 데이터를 삭제합니다.
				String thumbnailUUID = fileMetadata.getThumbnailUUID();
				String uuidFileName = fileMetadata.getUuidFileName();
				amazonS3.deleteObject(BUCKET_NAME, uuidFileName);
				if (thumbnailUUID != null) {
					amazonS3.deleteObject(BUCKET_NAME, thumbnailUUID);
				}
				// 삭제가 된 경우 db 에서 메타데이터를 삭제합니다.
				fileMetadataJpaRepository.deleteById(fileMetadata.getId());
			} catch (AmazonS3Exception e) {
				log.error("[Amazon S3 Exception] cannot find fail file in S3, file metadata id = {}",
					fileMetadata.getId());
			}
		});
	}
}
