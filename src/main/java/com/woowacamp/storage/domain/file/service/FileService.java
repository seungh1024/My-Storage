package com.woowacamp.storage.domain.file.service;

import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.file.dto.FileMoveDto;
import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.file.repository.FileMetadataRepository;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.event.FolderSizeEvent;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.utils.QueryExecuteTemplate;
import com.woowacamp.storage.global.constant.UploadStatus;
import com.woowacamp.storage.global.error.ErrorCode;
import com.woowacamp.storage.global.util.StorageStringUtil;
import com.woowacamp.storage.global.util.ValidateParentsUtil;
import com.woowacamp.storage.lock.annotation.DistributedLock;

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
	private final ValidateParentsUtil validateParentsUtil;

	@Value("${constant.batchSize}")
	private int pageSize;

	/**
	 * FileMetadata의 parentFolderId를 변경한다.
	 * source folder, target folder의 모든 정보를 수정한다.
	 */
	@DistributedLock(keys = """
			{
				@lockKeys.folderJob(#dto.targetFolderId()),
				@lockKeys.folderName(#dto.targetFolderId(), #dto.fileName())
		   	}
		""")
	public void moveFile(Long fileId, FileMoveDto dto) {
		FileMetadata fileMetadata = fileMetadataJpaRepository.findById(fileId)
			.orElseThrow(ErrorCode.FILE_NOT_FOUND::baseException);

		FolderMetadata targetFolder = folderMetadataRepository.findByIdNotDeleted(dto.targetFolderId())
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);
		if (!targetFolder.getOwnerId().equals(dto.userId())) {
			throw ErrorCode.ACCESS_DENIED.baseException();
		}
		validateMetadata(dto, fileMetadata, targetFolder);

		// 고아 방지를 위해 상위 부모중 삭제 작업 진행 중이면 이동 실패.
		validateParentsUtil.validateParentsFolderLock(targetFolder);

		long originParentId = fileMetadata.getParentFolderId();
		fileMetadata.updateParentFolderId(dto.targetFolderId());
		fileMetadataJpaRepository.save(fileMetadata);

		eventPublisher.publishEvent(new FolderSizeEvent(originParentId, -fileMetadata.getFileSize()));
		eventPublisher.publishEvent(new FolderSizeEvent(targetFolder.getId(), fileMetadata.getFileSize()));
	}

	private void validateMetadata(FileMoveDto dto, FileMetadata fileMetadata, FolderMetadata targetFolder) {
		if (!Objects.equals(fileMetadata.getOwnerId(), targetFolder.getOwnerId()) || !Objects.equals(
			fileMetadata.getOwnerId(), dto.userId())) {
			throw ACCESS_DENIED.baseException(StorageStringUtil.format(
				"File is not owned by user. fileId: {}, fileOwnerId: {}, targetFolderOwnerId: {}, userId: {}",
				fileMetadata.getId(), fileMetadata.getOwnerId(), targetFolder.getOwnerId(), dto.userId()));
		}
		if (fileMetadata.getUploadStatus() != UploadStatus.SUCCESS) {
			throw ErrorCode.FILE_NOT_FOUND.baseException();
		}
		if (fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(dto.targetFolderId(),
			fileMetadata.getUploadFileName(), UploadStatus.FAIL)) {
			throw ErrorCode.FILE_NAME_DUPLICATE.baseException();
		}
	}

	@Transactional(readOnly = true)
	public FileMetadata getFileMetadataBy(Long fileId, Long userId) {
		FileMetadata fileMetadata = fileMetadataJpaRepository.findById(fileId)
			.orElseThrow(ErrorCode.FILE_NOT_FOUND::baseException);

		if (!Objects.equals(fileMetadata.getOwnerId(), userId)) {
			throw ACCESS_DENIED.baseException();
		}
		return fileMetadata;
	}

	/**
	 * 단건 삭제는 고아될 일이 없기에 상위 탐지를 하지 않는다.
	 */
	@Transactional
	public void deleteFile(Long fileId, Long userId) {
		FileMetadata fileMetadata = fileMetadataJpaRepository.findByIdAndOwnerIdAndUploadStatusNot(fileId, userId,
			UploadStatus.FAIL).orElseThrow(ACCESS_DENIED::baseException);
		fileMetadataJpaRepository.softDelete(fileMetadata.getId());
		eventPublisher.publishEvent(new FolderSizeEvent(fileMetadata.getParentFolderId(),-fileMetadata.getFileSize()));
	}

	public void doHardDelete() {
		LocalDateTime timeLimit = LocalDateTime.now().minusDays(hardDeleteDuration);
		QueryExecuteTemplate.<FileMetadata>selectFilesAndExecuteWithCursor(pageSize,
			findFile -> fileMetadataRepository.findSoftDeletedFileWithLastIdAndDuration(
				findFile == null ? null : findFile.getId(), pageSize, timeLimit),
			fileMetadataList -> fileMetadataRepository.deleteAll(fileMetadataList));
	}
}
