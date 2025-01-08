package com.woowacamp.storage.domain.file.service;

import java.util.Arrays;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.file.dto.request.FileUploadRequestDto;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.global.constant.CommonConstant;
import com.woowacamp.storage.global.constant.UploadStatus;
import com.woowacamp.storage.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ValidationService {

	private final FileMetadataJpaRepository fileMetadataJpaRepository;
	private final FolderMetadataJpaRepository folderMetadataJpaRepository;

	@Value("${file.request.maxFileSize}")
	private long MAX_FILE_SIZE;
	@Value("${file.request.maxStorageSize}")
	private long MAX_STORAGE_SIZE;

	@Transactional
	public void validateFile(FileUploadRequestDto fileUploadRequestDto) {
		// 파일 이름에 금칙어가 있는지 확인
		if (Arrays.stream(CommonConstant.FILE_NAME_BLACK_LIST)
			.anyMatch(character -> fileUploadRequestDto.fileName().indexOf(character) != -1)) {
			throw ErrorCode.INVALID_FILE_NAME.baseException();
		}
		// 확장자에 금칙어가 있는지 확인
		if (Arrays.stream(CommonConstant.FILE_NAME_BLACK_LIST)
			.anyMatch(character -> fileUploadRequestDto.fileExtension().indexOf(character) != -1)) {
			throw ErrorCode.INVALID_FILE_NAME.baseException();
		}
		// 이미 해당 폴더에 같은 이름의 파일이 존재하는지 확인
		if (fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
			fileUploadRequestDto.parentFolderId(), fileUploadRequestDto.fileName(), UploadStatus.FAIL)) {
			throw ErrorCode.FILE_NAME_DUPLICATE.baseException();
		}
	}

	@Transactional
	public void validateFileSize(long fileSize, Long rootFolderId) {
		FolderMetadata rootFolderMetadata = folderMetadataJpaRepository.findByIdForUpdate(rootFolderId)
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);

		if (fileSize > MAX_FILE_SIZE) {
			throw ErrorCode.EXCEED_MAX_FILE_SIZE.baseException();
		}
		if (rootFolderMetadata.getSize() + fileSize > MAX_STORAGE_SIZE) {
			throw ErrorCode.EXCEED_MAX_STORAGE_SIZE.baseException();
		}
	}

	/**
	 * UUID를 생성해 이미 존재하는지 확인
	 */
	public String getUuidFileName() {
		String uuidFileName = UUID.randomUUID().toString();
		while (fileMetadataJpaRepository.existsByUuidFileName(uuidFileName)) {
			uuidFileName = UUID.randomUUID().toString();
		}
		return uuidFileName;
	}
}
