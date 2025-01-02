package com.woowacamp.storage.domain.file.service;

import java.net.URL;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.file.dto.FileMetadataDto;
import com.woowacamp.storage.domain.file.dto.request.FileUploadRequestDto;
import com.woowacamp.storage.domain.file.dto.response.FileUploadResponseDto;
import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.entity.FileMetadataFactory;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.service.RedisLockService;
import com.woowacamp.storage.global.constant.CommonConstant;
import com.woowacamp.storage.global.constant.UploadStatus;
import com.woowacamp.storage.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import static com.woowacamp.storage.global.error.ErrorCode.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3FileService {

	private final FileMetadataJpaRepository fileMetadataJpaRepository;
	private final FolderMetadataJpaRepository folderMetadataJpaRepository;
	private final RedisLockService redisLockService;
	private final PresignedUrlService presignedUrlService;

	@Value("${file.request.maxFileSize}")
	private long MAX_FILE_SIZE;
	@Value("${file.request.maxStorageSize}")
	private long MAX_STORAGE_SIZE;

	/**
	 * 1차로 메타데이터를 생성하는 메소드.
	 * 사용자의 요청 데이터에 있는 사용자 정보, 상위 폴더 정보, 파일 사이즈의 정보를 저장
	 */
	public FileUploadResponseDto createInitialMetadata(FileUploadRequestDto fileUploadRequestDto) {
		String lockName = fileUploadRequestDto.parentFolderId() + "/" + fileUploadRequestDto.fileName();
		return redisLockService.<FileUploadResponseDto>handleUserRequest(lockName, () ->
				createFileMetadata(fileUploadRequestDto)
			, FILE_NAME_DUPLICATE.baseException());
	}

	@Transactional
	protected FileUploadResponseDto createFileMetadata(FileUploadRequestDto fileUploadRequestDto) {
		FolderMetadata parentFolder = folderMetadataJpaRepository.findById(fileUploadRequestDto.parentFolderId())
			.orElseThrow(FOLDER_NOT_FOUND::baseException);
		// 파일 이름 검증
		validateFile(fileUploadRequestDto);

		// 1차 메타데이터 초기화
		String uuidFileName = getUuidFileName();
		String objectKey = uuidFileName;
		if (fileUploadRequestDto.fileExtension() != null) {
			objectKey += "." + fileUploadRequestDto.fileExtension();
		}
		FileMetadata fileMetadata = FileMetadataFactory.buildInitialMetadata(parentFolder, fileUploadRequestDto,
			objectKey);

		fileMetadataJpaRepository.save(fileMetadata);

		URL presignedUrl = presignedUrlService.getPresignedUrl(objectKey);

		return new FileUploadResponseDto(fileMetadata.getId(), objectKey, presignedUrl);
	}


	private void validateFileSize(long fileSize, Long rootFolderId) {
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
	private String getUuidFileName() {
		String uuidFileName = UUID.randomUUID().toString();
		while (fileMetadataJpaRepository.existsByUuidFileName(uuidFileName)) {
			uuidFileName = UUID.randomUUID().toString();
		}
		return uuidFileName;
	}

	private void validateFile(FileUploadRequestDto fileUploadRequestDto) {
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

	private String getFileTypeByFileName(String fileName) {
		String fileType = null;
		int index = fileName.lastIndexOf('.');
		if (index != -1) {
			fileType = fileName.substring(index);
		}
		return fileType;
	}

	/**
	 * 파일 다운로드 url 생성
	 * @param fileId
	 * @return
	 */
	public URL getFileUrl(long fileId) {
		FileMetadata fileMetadata = fileMetadataJpaRepository.findById(fileId)
			.orElseThrow(FILE_NOT_FOUND::baseException);

		if (!Objects.equals(fileMetadata.getUploadStatus(), UploadStatus.SUCCESS)) {
			throw ErrorCode.FILE_NOT_FOUND.baseException();
		}

		return presignedUrlService.getDownloadUrl(fileMetadata.getUuidFileName());
	}

	/**
	 * 파일 업로드 완료 메서드
	 * @param fileId
	 * @param userId
	 */
	public void createComplete(long fileId, long userId, String objectKey) {
		FileMetadata fileMetadata = fileMetadataJpaRepository.findById(fileId)
			.orElseThrow(FILE_NOT_FOUND::baseException);

		// 자신이 생성한 파일이 아니면 완료 요청을 보낼 수 없다.
		if (fileMetadata.getCreatorId() != userId) {
			throw WRONG_PERMISSION_TYPE.baseException();
		}

		if (!Objects.equals(fileMetadata.getUuidFileName(), objectKey)) {
			throw WRONG_OBJECT_KEY.baseException();
		}

		HeadObjectResponse rgwFileMetadata = presignedUrlService.getFileMetadata(fileMetadata.getUuidFileName());
		// 파일 사이즈가 다르면 기존 요청과 다른 파일을 업로드 한 것으로 간주하고 상태를 실패로 변경
		if (rgwFileMetadata.contentLength().longValue() != fileMetadata.getFileSize().longValue()) {
			fileMetadata.updateFailUploadStatus();
			fileMetadataJpaRepository.save(fileMetadata);
			throw INVALID_FILE_SIZE.baseException();
		}

		fileMetadata.updateFinishUploadStatus();
		fileMetadataJpaRepository.save(fileMetadata);
	}

}
