package com.woowacamp.storage.domain.file.facade;

import org.springframework.stereotype.Service;

import com.woowacamp.storage.domain.file.dto.FileMoveDto;
import com.woowacamp.storage.domain.file.dto.command.FileCreateLockContext;
import com.woowacamp.storage.domain.file.dto.command.FileMoveLockContext;
import com.woowacamp.storage.domain.file.dto.request.FileUploadRequestDto;
import com.woowacamp.storage.domain.file.dto.response.FileUploadResponseDto;
import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.file.service.FileService;
import com.woowacamp.storage.domain.file.service.S3FileService;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FileFacade {

	private final FileMetadataJpaRepository fileMetadataJpaRepository;
	private final FolderMetadataJpaRepository folderMetadataJpaRepository;
	private final FileService fileService;
	private final S3FileService s3FileService;

	public void moveFile(Long fileId, FileMoveDto dto) {
		FileMetadata sourceFile = fileMetadataJpaRepository.findById(fileId)
			.orElseThrow(ErrorCode.FILE_NOT_FOUND::baseException);
		FolderMetadata targetFolder = folderMetadataJpaRepository.findByIdNotDeleted(dto.targetFolderId())
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);

		Long rootId = sourceFile.getRootId() == null
			? (targetFolder.getRootId() == null ? targetFolder.getId() : targetFolder.getRootId())
			: sourceFile.getRootId();

		FileMoveLockContext lockContext = new FileMoveLockContext(
			fileId,
			targetFolder.getId(),
			rootId,
			sourceFile.getUploadFileName()
		);
		fileService.moveFile(lockContext, dto);
	}

	public FileUploadResponseDto createFileMetadata(FileUploadRequestDto dto) {
		FolderMetadata parentFolder = folderMetadataJpaRepository.findById(dto.parentFolderId())
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);
		Long rootId = parentFolder.getRootId() == null ? parentFolder.getId() : parentFolder.getRootId();

		FileCreateLockContext lockContext = new FileCreateLockContext(
			parentFolder.getId(),
			rootId,
			dto.fileName()
		);
		return s3FileService.createFileMetadata(lockContext, dto);
	}
}
