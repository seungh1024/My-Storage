package com.woowacamp.storage.domain.file.service;

import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.file.dto.FileMoveDto;
import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.event.FileMoveEvent;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.file.repository.FileMetadataRepository;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.service.MetadataService;
import com.woowacamp.storage.domain.folder.service.RedisLockService;
import com.woowacamp.storage.domain.folder.utils.FolderSearchUtil;
import com.woowacamp.storage.domain.folder.utils.QueryExecuteTemplate;
import com.woowacamp.storage.global.constant.UploadStatus;
import com.woowacamp.storage.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;

import static com.woowacamp.storage.global.constant.CommonConstant.*;
import static com.woowacamp.storage.global.error.ErrorCode.*;

@Service
@RequiredArgsConstructor
public class FileService {
	private final FileMetadataRepository fileMetadataRepository;
	private final FileMetadataJpaRepository fileMetadataJpaRepository;
	private final FolderMetadataJpaRepository folderMetadataRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final MetadataService metadataService;
	private final FolderSearchUtil folderSearchUtil;
	private final RedisLockService redisLockService;

	@Value("${constant.batchSize}")
	private int pageSize;

	/**
	 * FileMetadata의 parentFolderId를 변경한다.
	 * source folder, target folder의 모든 정보를 수정한다.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void moveFile(Long fileId, FileMoveDto dto) {
		FileMetadata fileMetadata = fileMetadataJpaRepository.findById(fileId)
			.orElseThrow(ErrorCode.FILE_NOT_FOUND::baseException);
		redisLockService.runWithWatchdogMultiLock(fileMetadata.getParentFolderId() + "", dto.targetFolderId() + "",
			() -> moveFileTask(dto, fileMetadata));

	}

	private void moveFileTask(FileMoveDto dto, FileMetadata fileMetadata) {
		FolderMetadata targetFolder = folderMetadataRepository.findByIdNotDeleted(dto.targetFolderId())
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);
		if (!targetFolder.getOwnerId().equals(dto.userId())) {
			throw ErrorCode.ACCESS_DENIED.baseException();
		}
		validateMetadata(dto, fileMetadata, targetFolder);

		long originParentId = fileMetadata.getParentFolderId();
		fileMetadata.updateParentFolderId(dto.targetFolderId());

		// 이름 중복 방지를 위한 락 사용. 락 반환 전에 flush
		redisLockService.runWithWatchdogLock(fileMetadata.getParentFolderId() + "/" + fileMetadata.getUploadFileName(),
			() -> fileCommit(fileMetadata));

		metadataService.calculateSize(originParentId);
		metadataService.calculateSize(dto.targetFolderId());

		eventPublisher.publishEvent(new FileMoveEvent(this, fileMetadata, targetFolder));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	protected void fileCommit(FileMetadata fileMetadata) {
		fileMetadataJpaRepository.saveAndFlush(fileMetadata);
	}

	private void validateMetadata(FileMoveDto dto, FileMetadata fileMetadata, FolderMetadata targetFolder) {
		if (fileMetadata.getUploadStatus() != UploadStatus.SUCCESS) {
			throw ErrorCode.FILE_NOT_FOUND.baseException();
		}
		if (fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(dto.targetFolderId(),
			fileMetadata.getUploadFileName(), UploadStatus.FAIL)) {
			throw ErrorCode.FILE_NAME_DUPLICATE.baseException();
		}

		folderSearchUtil.folderLockCheck(fileMetadata.getParentFolderId(), null);
		folderSearchUtil.folderLockCheck(targetFolder.getParentFolderId(), fileMetadata.getParentFolderId());
	}

	public FileMetadata getFileMetadataBy(Long fileId, Long userId) {
		FileMetadata fileMetadata = fileMetadataJpaRepository.findById(fileId)
			.orElseThrow(ErrorCode.FILE_NOT_FOUND::baseException);

		if (!Objects.equals(fileMetadata.getOwnerId(), userId)) {
			throw ACCESS_DENIED.baseException();
		}
		return fileMetadata;
	}

	@Transactional
	public void deleteFile(Long fileId, Long userId) {
		FileMetadata fileMetadata = fileMetadataJpaRepository.findByIdAndOwnerIdAndUploadStatusNot(fileId, userId,
			UploadStatus.FAIL).orElseThrow(ACCESS_DENIED::baseException);
		redisLockService.runWithWatchdogLock(fileMetadata.getParentFolderId() + "",
			() -> deleteFileTask(fileMetadata));
	}

	private void deleteFileTask(FileMetadata fileMetadata) {
		fileMetadataJpaRepository.softDelete(fileMetadata.getId());
		metadataService.calculateSize(fileMetadata.getParentFolderId());
	}

	public void doHardDelete() {
		LocalDateTime timeLimit = LocalDateTime.now().minusDays(hardDeleteDuration);
		QueryExecuteTemplate.<FileMetadata>selectFilesAndExecuteWithCursor(pageSize,
			findFile -> fileMetadataRepository.findSoftDeletedFileWithLastIdAndDuration(
				findFile == null ? null : findFile.getId(), pageSize, timeLimit),
			fileMetadataList -> fileMetadataRepository.deleteAll(fileMetadataList));
	}
}
