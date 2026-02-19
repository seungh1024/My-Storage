package com.woowacamp.storage.domain.folder.facade;

import com.woowacamp.storage.domain.folder.dto.request.CreateFolderReqDto;
import com.woowacamp.storage.domain.folder.dto.request.FolderMoveDto;
import com.woowacamp.storage.domain.folder.dto.command.CreateLockContext;
import com.woowacamp.storage.domain.folder.dto.command.MoveLockContext;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.service.FolderService;
import com.woowacamp.storage.global.error.ErrorCode;
import com.woowacamp.storage.global.util.StorageStringUtil;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FolderFacade {
	private static final String FOLDER_ID_MESSAGE = "Folder id: {}";

	private final FolderMetadataJpaRepository folderMetadataJpaRepository;
	private final FolderService folderService;

	public void moveFolder(Long sourceFolderId, FolderMoveDto dto) {
		FolderMetadata sourceFolder = folderMetadataJpaRepository.findByIdNotDeleted(sourceFolderId)
			.orElseThrow(() -> ErrorCode.FOLDER_NOT_FOUND.baseException(
				StorageStringUtil.format(FOLDER_ID_MESSAGE, sourceFolderId)));

		FolderMetadata targetFolder = folderMetadataJpaRepository.findByIdNotDeleted(dto.targetFolderId())
			.orElseThrow(() -> ErrorCode.FOLDER_NOT_FOUND.baseException(
				StorageStringUtil.format(FOLDER_ID_MESSAGE, dto.targetFolderId())));

		Long sourceRootId = sourceFolder.getRootId() == null ? sourceFolder.getId() : sourceFolder.getRootId();
		MoveLockContext moveLockContext = new MoveLockContext(
			sourceFolderId,
			targetFolder.getId(),
			sourceRootId,
			sourceFolder.getUploadFolderName()
		);
		folderService.moveFolder(moveLockContext, dto);
	}

	public Long createFolder(CreateFolderReqDto req) {
		FolderMetadata parentFolder = folderMetadataJpaRepository.findById(req.parentFolderId())
			.orElseThrow(() -> ErrorCode.FOLDER_NOT_FOUND.baseException(
				StorageStringUtil.format("folder not found when creating folder. find folder id:{}", req.parentFolderId())));

		Long rootId = parentFolder.getRootId() == null ? parentFolder.getId() : parentFolder.getRootId();
		CreateLockContext createLockContext = new CreateLockContext(
			parentFolder.getId(),
			rootId,
			req.uploadFolderName()
		);
		return folderService.createFolder(createLockContext, req);
	}
}
