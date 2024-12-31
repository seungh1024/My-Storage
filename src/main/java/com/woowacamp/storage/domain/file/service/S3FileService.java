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

import static com.woowacamp.storage.global.error.ErrorCode.*;

@Service
@RequiredArgsConstructor
public class S3FileService {

	private final FileMetadataJpaRepository fileMetadataJpaRepository;
	private final FolderMetadataJpaRepository folderMetadataRepository;
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
		FolderMetadata parentFolder = folderMetadataRepository.findById(fileUploadRequestDto.parentFolderId())
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

		return new FileUploadResponseDto(objectKey, presignedUrl);
	}

	/**
	 * 사용자 정보는 로그인을 했다고 가정하고 사용했습니다.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void finalizeMetadata(FileMetadataDto fileMetadataDto, long fileSize) {
		FileMetadata fileMetadata = fileMetadataJpaRepository.findById(fileMetadataDto.metadataId())
			.orElseThrow(ErrorCode.FILE_METADATA_NOT_FOUND::baseException);

		// 파일 메타데이터를 먼저 쓴다.
		fileMetadataJpaRepository.finalizeMetadata(fileMetadata.getId(), fileSize, UploadStatus.SUCCESS);

		LocalDateTime now = LocalDateTime.now();
		updateFolderMetadataStatus(fileMetadataDto, fileSize, now);

	}

	private void validateFileSize(long fileSize, Long rootFolderId) {
		FolderMetadata rootFolderMetadata = folderMetadataRepository.findByIdForUpdate(rootFolderId)
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);

		if (fileSize > MAX_FILE_SIZE) {
			throw ErrorCode.EXCEED_MAX_FILE_SIZE.baseException();
		}
		if (rootFolderMetadata.getSize() + fileSize > MAX_STORAGE_SIZE) {
			throw ErrorCode.EXCEED_MAX_STORAGE_SIZE.baseException();
		}
	}

	/**
	 * 현재 폴더에서 루트 폴더까지 모든 폴더에 대한 size, updatedAt을 갱신
	 * 중간에 새로운 파일들이 써질 수 있으니 최상위 폴더까지의 락을 획득 후 작업을 진행한다.
	 */
	private void updateFolderMetadataStatus(FileMetadataDto req, long fileSize, LocalDateTime now) {
		Long parentFolderId = req.parentFolderId();
		while (parentFolderId != null) {
			FolderMetadata folderMetadata = folderMetadataRepository.findByIdForUpdate(parentFolderId)
				.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);
			if (folderMetadata.getSize() + fileSize > MAX_STORAGE_SIZE) {
				throw ErrorCode.EXCEED_MAX_STORAGE_SIZE.baseException();
			}
			folderMetadata.addSize(fileSize);
			folderMetadata.updateUpdatedAt(now);
			parentFolderId = folderMetadata.getParentFolderId();
		}
	}

	/**
	 * 요청한 parentFolderId가 자신의 폴더에 대한 id인지 확인
	 */
	private FolderMetadata validateParentFolder(long parentFolderId, long userId) {
		FolderMetadata folderMetadata = folderMetadataRepository.findByIdForUpdate(parentFolderId)
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);
		if (!Objects.equals(folderMetadata.getOwnerId(), userId)) {
			throw ACCESS_DENIED.baseException();
		}

		return folderMetadata;
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

		return presignedUrlService.getDownloadUrl(fileMetadata.getUuidFileName());
	}
}
