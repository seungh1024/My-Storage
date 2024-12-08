package com.woowacamp.storage.domain.file.service;

import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.file.dto.FileMoveDto;
import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.event.FileMoveEvent;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.file.repository.FileMetadataRepository;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.service.MetadataService;
import com.woowacamp.storage.domain.folder.utils.QueryExecuteTemplate;
import com.woowacamp.storage.global.constant.UploadStatus;
import com.woowacamp.storage.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;

import static com.woowacamp.storage.global.error.ErrorCode.*;

@Service
@RequiredArgsConstructor
public class FileService {
	private final FileMetadataRepository fileMetadataRepository;
	private final FileMetadataJpaRepository fileMetadataJpaRepository;
	private final FolderMetadataJpaRepository folderMetadataRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final MetadataService metadataService;

	@Value("${constant.batchSize}")
	private int pageSize;

	/**
	 * FileMetadata의 parentFolderId를 변경한다.
	 * source folder, target folder의 모든 정보를 수정한다.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void moveFile(Long fileId, FileMoveDto dto) {
		FolderMetadata folderMetadata = folderMetadataRepository.findByIdForUpdate(dto.targetFolderId())
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);
		if (!folderMetadata.getOwnerId().equals(dto.userId())) {
			throw ErrorCode.ACCESS_DENIED.baseException();
		}
		FileMetadata fileMetadata = fileMetadataJpaRepository.findByIdForUpdate(fileId)
			.orElseThrow(ErrorCode.FILE_NOT_FOUND::baseException);
		validateMetadata(dto, fileMetadata);

		fileMetadata.updateParentFolderId(dto.targetFolderId());
		metadataService.calculateSize(fileMetadata.getParentFolderId(), fileMetadata.getFileSize(), false);
		metadataService.calculateSize(dto.targetFolderId(), fileMetadata.getFileSize(), true);

		eventPublisher.publishEvent(new FileMoveEvent(this, fileMetadata, folderMetadata));
	}

	private void validateMetadata(FileMoveDto dto, FileMetadata fileMetadata) {
		if (fileMetadata.getUploadStatus() != UploadStatus.SUCCESS) {
			throw ErrorCode.FILE_NOT_FOUND.baseException();
		}
		if (fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(dto.targetFolderId(),
			fileMetadata.getUploadFileName(), UploadStatus.FAIL)) {
			throw ErrorCode.FILE_NAME_DUPLICATE.baseException();
		}
	}

	public FileMetadata getFileMetadataBy(Long fileId, Long userId) {
		FileMetadata fileMetadata = fileMetadataJpaRepository.findById(fileId)
			.orElseThrow(ErrorCode.FILE_NOT_FOUND::baseException);

		if (!Objects.equals(fileMetadata.getOwnerId(), userId)) {
			throw ACCESS_DENIED.baseException();
		}
		return fileMetadata;
	}

	// private String BUCKET_NAME
	@Transactional
	public void deleteFile(Long fileId, Long userId) {
		FileMetadata fileMetadata = fileMetadataJpaRepository.findByIdAndOwnerIdAndUploadStatusNot(fileId, userId,
				UploadStatus.FAIL)
			.orElseThrow(ACCESS_DENIED::baseException);

		fileMetadataJpaRepository.delete(fileMetadata);

		Long parentFolderId = fileMetadata.getParentFolderId();
		long fileSize = fileMetadata.getFileSize();
		LocalDateTime now = LocalDateTime.now();

		metadataService.calculateSize(parentFolderId, fileSize, false);
	}

	public void findOrphanFileAndHardDelete() {
		QueryExecuteTemplate.<FileMetadata>selectFilesAndExecuteWithCursor(pageSize,
			findFile -> fileMetadataRepository.findFileMetadataByLastId(
				findFile == null ? 0 : findFile.getParentFolderId(), findFile == null ? null : findFile.getId(),
				pageSize),
			fileMetadataList -> fileMetadataRepository.deleteAll(fileMetadataList));
	}

}
