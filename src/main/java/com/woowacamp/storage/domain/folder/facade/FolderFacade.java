package com.woowacamp.storage.domain.folder.facade;

import org.springframework.stereotype.Service;

import com.woowacamp.storage.domain.folder.dto.request.FolderMoveDto;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.service.FolderService;
import com.woowacamp.storage.global.error.ErrorCode;
import com.woowacamp.storage.global.util.StorageStringUtil;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FolderFacade {

	private final FolderService folderService;
	private final FolderMetadataJpaRepository folderMetadataJpaRepository;

	public void moveFolder(Long sourceFolderId, FolderMoveDto dto) {
		FolderMetadata folderMetadata = folderMetadataJpaRepository.findByIdNotDeleted(sourceFolderId)
			.orElseThrow(() -> ErrorCode.FOLDER_NOT_FOUND.baseException(
				StorageStringUtil.format("Folder id: {}", sourceFolderId)));
		folderService.moveFolder(folderMetadata.getRootId(), sourceFolderId, dto);
	}
}
