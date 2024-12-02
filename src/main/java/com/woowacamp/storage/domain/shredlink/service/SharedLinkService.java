package com.woowacamp.storage.domain.shredlink.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Stack;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataRepository;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.shredlink.dto.request.CancelSharedLinkRequestDto;
import com.woowacamp.storage.domain.shredlink.dto.request.MakeSharedLinkRequestDto;
import com.woowacamp.storage.domain.shredlink.dto.response.SharedLinkResponseDto;
import com.woowacamp.storage.domain.shredlink.entity.SharedLink;
import com.woowacamp.storage.domain.shredlink.entity.SharedLinkFactory;
import com.woowacamp.storage.domain.shredlink.repository.SharedLinkRepository;
import com.woowacamp.storage.global.constant.CommonConstant;
import com.woowacamp.storage.global.constant.PermissionType;
import com.woowacamp.storage.global.error.ErrorCode;
import com.woowacamp.storage.global.util.UrlUtil;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SharedLinkService {
	private final SharedLinkRepository sharedLinkRepository;
	private final FolderMetadataJpaRepository folderMetadataRepository;
	private final FileMetadataRepository fileMetadataRepository;

	/**
	 * 공유 링크 생성 메소드
	 *공유 대상 폴더/파일(폴더라면 하위 폴더 및 파일까지)의   공유 상태를 업데이트 하고 공유 링크를 반환합니다.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public SharedLinkResponseDto createShareLink(MakeSharedLinkRequestDto requestDto) {
		validateRequest(requestDto.userId(), requestDto.isFile(), requestDto.targetId());
		if (requestDto.getPermissionType().equals(PermissionType.NONE)) {
			throw ErrorCode.WRONG_PERMISSION_TYPE.baseException();
		}

		// 기존에 발급된 링크가 있다면 반환
		Optional<SharedLink> existingSharedLink = getExistingSharedLink(requestDto.isFile(), requestDto.targetId(),
			requestDto.getPermissionType());
		if (existingSharedLink.isPresent()) {
			return new SharedLinkResponseDto(createSharedLinkUrl(existingSharedLink.get().getSharedId()));
		}

		SharedLink sharedLink = createSharedLink(requestDto);
		try {
			sharedLinkRepository.saveAndFlush(sharedLink);
		} catch (DataIntegrityViolationException e) {
			throw ErrorCode.DUPLICATED_SHARED_LINK.baseException();
		}

		updateShareStatus(sharedLink);
		return new SharedLinkResponseDto(createSharedLinkUrl(sharedLink.getSharedId()));
	}

	private void validateRequest(Long userId, boolean isFile, long targetId) {
		if (isFile) { // file인 경우
			FileMetadata fileMetadata = fileMetadataRepository.findById(targetId)
				.orElseThrow(ErrorCode.FILE_NOT_FOUND::baseException);
			if (!Objects.equals(fileMetadata.getOwnerId(), userId)) {
				throw ErrorCode.ACCESS_DENIED.baseException();
			}
		} else { // folder인 경우
			FolderMetadata folderMetadata = folderMetadataRepository.findById(targetId)
				.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);
			if (!Objects.equals(folderMetadata.getOwnerId(), userId)) {
				throw ErrorCode.ACCESS_DENIED.baseException();
			}
		}
	}

	private Optional<SharedLink> getExistingSharedLink(boolean isFile, long targetId, PermissionType permissionType) {
		List<SharedLink> existingLinks = sharedLinkRepository.findByIsFileAndTargetId(isFile, targetId);
		for (SharedLink link : existingLinks) {
			if (!link.isExpired() && Objects.equals(permissionType, link.getPermissionType())) {
				return Optional.of(link);
			}
		}
		return Optional.empty();
	}

	private SharedLink createSharedLink(MakeSharedLinkRequestDto requestDto) {
		String sharedId = UUID.randomUUID().toString();
		String redirectUrl = createRedirectUrl(requestDto.isFile(), requestDto.targetId());
		return SharedLinkFactory.createSharedLink(requestDto, sharedId, redirectUrl);
	}

	private String createRedirectUrl(boolean isFile, long targetId) {
		String template = isFile ? CommonConstant.FILE_READ_URI : CommonConstant.FOLDER_READ_URI;
		return UrlUtil.getAbsoluteUrl(template + targetId);
	}

	private String createSharedLinkUrl(String sharedId) {
		return UrlUtil.getAbsoluteUrl(CommonConstant.SHARED_LINK_URI + sharedId);
	}

	/** 공유 대상을 조회하는 url을  반환합니다.
	 **/
	public String getRedirectUrl(String sharedId) {
		SharedLink sharedLink = sharedLinkRepository.findBySharedId(sharedId)
			.orElseThrow(ErrorCode.SHARED_LINK_NOT_FOUND::baseException);
		if (sharedLink.isExpired()) {
			throw ErrorCode.EXPIRED_SHARED_LINK.baseException();
		}
		return sharedLink.getRedirectUrl();
	}

	public void updateShareStatus(SharedLink sharedLink) {
		if (Boolean.TRUE.equals(sharedLink.getIsFile())) {
			updateFileShareStatus(sharedLink.getTargetId(), sharedLink.getPermissionType(),
				sharedLink.getExpiredAt());
		} else {
			updateFolderSharingStatus(sharedLink.getTargetId(),
				sharedLink.getPermissionType(), sharedLink.getExpiredAt());
		}
	}

	public void updateFileShareStatus(Long fileId, PermissionType permissionType, LocalDateTime sharingExpireAt) {
		FileMetadata fileMetadata = fileMetadataRepository.findById(fileId)
			.orElseThrow(ErrorCode.FILE_NOT_FOUND::baseException);
		fileMetadata.updateShareStatus(permissionType, sharingExpireAt);
	}

	public void updateFolderSharingStatus(Long folderId, PermissionType permissionType,
		LocalDateTime sharingExpireAt) {
		folderMetadataRepository.findById(folderId)
			.orElseThrow(ErrorCode.FILE_NOT_FOUND::baseException);

		Stack<Long> folderIdStack = new Stack<>();
		folderIdStack.push(folderId);

		List<Long> folderIdsToUpdate = new ArrayList<>();
		List<Long> fileIdsToUpdate = new ArrayList<>();
		folderIdsToUpdate.add(folderId);

		while (!folderIdStack.isEmpty()) {
			Long currentFolderId = folderIdStack.pop();

			// 하위의 파일 조회
			List<FileMetadata> childFileMetadata = fileMetadataRepository.findByParentFolderIdForUpdate(
				currentFolderId);

			// 하위 파일의 공유 상태 수정
			childFileMetadata.forEach(fileMetadata -> {
				fileIdsToUpdate.add(fileMetadata.getId());
			});

			// 하위의 폴더 조회
			List<FolderMetadata> childFolders = folderMetadataRepository.findByParentFolderIdForUpdate(currentFolderId);

			// 하위 폴더들을 스택에 추가
			for (FolderMetadata childFolder : childFolders) {
				folderIdsToUpdate.add(childFolder.getId());
				folderIdStack.push(childFolder.getId());
			}
		}
		fileMetadataRepository.updateShareStatusInBatch(fileIdsToUpdate, permissionType,
			sharingExpireAt);
		folderMetadataRepository.updateShareStatusInBatch(folderIdsToUpdate, permissionType,
			sharingExpireAt);
	}

	@Transactional
	public void cancelShare(CancelSharedLinkRequestDto requestDto) {
		validateRequest(requestDto.userId(), requestDto.isFile(), requestDto.targetId());
		cancelShare(requestDto.isFile(), requestDto.targetId());
	}

	private void cancelShare(boolean isFile, Long targetId) {
		sharedLinkRepository.deleteByIsFileAndTargetId(isFile, targetId);
		if (isFile) {
			FileMetadata fileMetadata = fileMetadataRepository.findById(targetId)
				.orElseThrow(ErrorCode.FILE_NOT_FOUND::baseException);
			fileMetadata.cancelShare();
		} else {
			cancelFolderShare(targetId);
		}
	}

	public void cancelFolderShare(Long folderId) {
		FolderMetadata folder = folderMetadataRepository.findById(folderId)
			.orElseThrow(ErrorCode.FILE_NOT_FOUND::baseException);

		Stack<Long> folderIdStack = new Stack<>();
		folderIdStack.push(folderId);

		List<Long> folderIdsToUpdate = new ArrayList<>();
		List<Long> fileIdsToUpdate = new ArrayList<>();
		folderIdsToUpdate.add(folderId);

		while (!folderIdStack.isEmpty()) {
			Long currentFolderId = folderIdStack.pop();

			// 하위의 파일 조회
			List<FileMetadata> childFileMetadata = fileMetadataRepository.findByParentFolderIdForUpdate(
				currentFolderId);

			// 하위 파일의 공유 상태 수정
			childFileMetadata.forEach(fileMetadata -> {
				fileIdsToUpdate.add(fileMetadata.getId());
			});

			// 하위의 폴더 조회
			List<FolderMetadata> childFolders = folderMetadataRepository.findByParentFolderIdForUpdate(currentFolderId);

			// 하위 폴더들을 스택에 추가
			for (FolderMetadata childFolder : childFolders) {
				folderIdsToUpdate.add(childFolder.getId());
				folderIdStack.push(childFolder.getId());
			}
		}
		fileMetadataRepository.updateShareStatusInBatch(fileIdsToUpdate, PermissionType.NONE,
			CommonConstant.UNAVAILABLE_TIME);
		folderMetadataRepository.updateShareStatusInBatch(folderIdsToUpdate, PermissionType.NONE,
			CommonConstant.UNAVAILABLE_TIME);
	}
}
