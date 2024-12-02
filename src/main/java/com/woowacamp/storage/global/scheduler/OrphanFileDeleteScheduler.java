package com.woowacamp.storage.global.scheduler;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataRepository;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.global.constant.CommonConstant;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 부모 폴더가 없는 파일과 폴더를 제거하는 스케줄러
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrphanFileDeleteScheduler {
	public static final int DELAY = 1000 * 30;
	private final AmazonS3 amazonS3;
	private final FileMetadataRepository fileMetadataRepository;
	private final FolderMetadataJpaRepository folderMetadataRepository;
	@Value("${cloud.aws.credentials.bucketName}")
	private String BUCKET_NAME;

	/**
	 * 부모 폴더가 없는 파일을 제거하는 스케줄러 입니다.
	 * S3에 파일이 있는지 확인하고 제거 후 메타데이터를 제거합니다.
	 * <p>
	 * 메타데이터를 먼저 지우면 S3의 파일이 어떤 이유로 제거되지 않을 수 있습니다.
	 * 그래서 S3 삭제가 된 후에 파일 메타데이터를 제거합니다.
	 */
	@Scheduled(fixedDelay = DELAY)
	public void deleteOrphanFile() {
		List<FileMetadata> orphanFiles = fileMetadataRepository.findOrphanFiles(CommonConstant.ORPHAN_PARENT_ID);
		if (orphanFiles.isEmpty()) {
			return;
		}

		orphanFiles.forEach(fileMetadata -> {
			try {
				ObjectMetadata objectMetadata = amazonS3.getObjectMetadata(BUCKET_NAME,
					fileMetadata.getUuidFileName());
				if (Objects.nonNull(objectMetadata)) {
					amazonS3.deleteObject(BUCKET_NAME, fileMetadata.getUploadFileName());
					if (fileMetadata.getThumbnailUUID() != null) {
						amazonS3.deleteObject(BUCKET_NAME, fileMetadata.getThumbnailUUID());
					}
					fileMetadataRepository.deleteById(fileMetadata.getId());
				}
			} catch (AmazonS3Exception e) {
				log.error("[Amazon S3 Exception] cannot find orphan file in S3, file metadata id = {}",
					fileMetadata.getId());
			}
		});
	}

	@Scheduled(fixedDelay = DELAY)
	@Transactional
	public void deleteOrphanFolder() {
		folderMetadataRepository.deleteOrphanFolders(CommonConstant.ORPHAN_PARENT_ID);
	}
}
