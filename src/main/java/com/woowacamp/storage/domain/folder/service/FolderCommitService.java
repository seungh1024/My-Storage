package com.woowacamp.storage.domain.folder.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.event.FolderSizeEvent;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class FolderCommitService {

	private final FolderMetadataJpaRepository folderMetadataJpaRepository;
	private final ApplicationEventPublisher publisher;

	/**
	 * 락 내부에서 커밋을 하기 위해 이름 중복 체크와 폴더 이동 적용을 별도의 트랜잭션에서 처리
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	protected int duplicatedCheckAndMoveCommit(Long sourceId, Long targetId) {
		FolderMetadata sourceFolder = folderMetadataJpaRepository.findById(sourceId)
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);

		FolderMetadata targetFolder = folderMetadataJpaRepository.findById(targetId)
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);

		validateDuplicatedFolderName(targetFolder, sourceFolder);

		// 용량 업데이트 이벤트 발행.
		publisher.publishEvent(new FolderSizeEvent(sourceFolder.getParentFolderId(), -sourceFolder.getSize()));
		publisher.publishEvent(new FolderSizeEvent(targetFolder.getId(), sourceFolder.getSize()));

		return folderMetadataJpaRepository.updateParentInfoWithVersion(sourceFolder.getId(), targetFolder.getId(),
			sourceFolder.getVersion());
	}

	/**
	 * 같은 폴더 내에 동일한 이름의 폴더가 있는지 확인
	 */
	private void validateDuplicatedFolderName(FolderMetadata targetFolder, FolderMetadata folderMetadata) {
		if (folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(targetFolder.getId(),
			folderMetadata.getUploadFolderName())) {
			throw ErrorCode.FILE_NAME_DUPLICATE.baseException();
		}
	}
}
