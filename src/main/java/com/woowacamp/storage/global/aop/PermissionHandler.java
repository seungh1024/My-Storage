package com.woowacamp.storage.global.aop;

import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataRepository;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.global.aop.type.FileType;
import com.woowacamp.storage.global.constant.PermissionType;
import com.woowacamp.storage.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionHandler {
	private final FolderMetadataJpaRepository folderMetadataRepository;
	private final FileMetadataRepository fileMetadataRepository;

	/**
	 * 권한을 확인하는 메소드
	 *
	 * @param permissionType 읽기 작업인지, 쓰기 작업인지 명시
	 * @param fileType 파일 작업인지, 폴더 작업인지 명시
	 * @param permissionFieldsDto 요청 객체의 필드 데이터
	 * @return
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void hasPermission(PermissionType permissionType, FileType fileType,
		PermissionFieldsDto permissionFieldsDto) {

		switch (fileType) {
			case FILE:
				validateFile(permissionFieldsDto, permissionType);
				// 이동할 폴더의 값이 있다면 이동 요청일 수 있으므로 권한을 확인한다.
				if (permissionFieldsDto.getMoveFolderId() != null) {
					validateFileMove(permissionFieldsDto, permissionType);
				}
				break;
			case FOLDER:
				validateFolder(permissionFieldsDto, permissionType);
				break;
			default:
				throw ErrorCode.PERMISSION_CHECK_FAILED.baseException("존재하지 않는 파일 타입을 사용했습니다. file type = " + fileType);
		}
	}

	private void validateFileMove(PermissionFieldsDto permissionFieldsDto, PermissionType permissionType) {
		FolderMetadata folderMetadata = null;

		if (Objects.equals(permissionType, PermissionType.READ)) {
			folderMetadata = folderMetadataRepository.findById(permissionFieldsDto.getMoveFolderId())
				.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);
		} else {
			folderMetadata = folderMetadataRepository.findByIdForShare(permissionFieldsDto.getMoveFolderId())
				.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);
		}

		LocalDateTime sharingExpiredAt = folderMetadata.getSharingExpiredAt();
		Long ownerId = folderMetadata.getOwnerId();
		PermissionType sharedPermissionType = folderMetadata.getPermissionType();
		LocalDateTime now = LocalDateTime.now();

		validateExpiredAndPermission(sharingExpiredAt, now, ownerId, permissionFieldsDto.getUserId(),
			sharedPermissionType, permissionType);
	}

	/**
	 * 폴더에 대한 권한을 확인하는 메소드
	 * 폴더 이동의 경우 이동할 폴더에 대한 권한도 추가로 검증한다.
	 * @param permissionFieldsDto 사용자 정보, 파일 정보 등의 요청 데이터가 존재하는 dto
	 * @param permissionType 컨트롤러에서 필요한 권한 타입(쓰기, 읽기)
	 */
	private void validateFolder(PermissionFieldsDto permissionFieldsDto, PermissionType permissionType) {
		FolderMetadata folderMetadata = null;

		if (Objects.equals(permissionType, PermissionType.READ)) {
			folderMetadata = folderMetadataRepository.findById(permissionFieldsDto.getFolderId())
				.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);
		} else {
			folderMetadata = folderMetadataRepository.findByIdForShare(permissionFieldsDto.getFolderId())
				.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);
		}

		LocalDateTime sharingExpiredAt = folderMetadata.getSharingExpiredAt();
		Long ownerId = folderMetadata.getOwnerId();
		PermissionType sharedPermissionType = folderMetadata.getPermissionType();
		LocalDateTime now = LocalDateTime.now();

		validateExpiredAndPermission(sharingExpiredAt, now, ownerId, permissionFieldsDto.getUserId(),
			sharedPermissionType, permissionType);

		// moveFolderId에 값이 존재하면 이동에 대한 권한을 추가로 확인한다.
		if (permissionFieldsDto.getMoveFolderId() != null) {
			FolderMetadata moveFolderMetadata = folderMetadataRepository.findByIdForShare(
				permissionFieldsDto.getMoveFolderId()).orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);

			LocalDateTime movingExpiredAt = moveFolderMetadata.getSharingExpiredAt();
			Long movingOwnerId = moveFolderMetadata.getOwnerId();
			PermissionType movingPermissionType = moveFolderMetadata.getPermissionType();
			validateExpiredAndPermission(movingExpiredAt, now, movingOwnerId, permissionFieldsDto.getUserId(),
				movingPermissionType, permissionType);
		}

		permissionFieldsDto.setOwnerId(ownerId);
	}

	/**
	 * 파일에 대한 권한을 확인하는 메소드
	 * @param permissionFieldsDto 사용자 정보, 파일 정보 등의 요청 데이터가 존재하는 dto
	 * @param permissionType 컨트롤러에서 필요한 권한 타입(쓰기, 읽기)
	 */
	private void validateFile(PermissionFieldsDto permissionFieldsDto, PermissionType permissionType) {
		FileMetadata fileMetadata = null;

		// 읽기 작업인 경우 락을 걸지 않고 권한을 확인한다.
		if (Objects.equals(permissionType, PermissionType.READ)) {
			fileMetadata = fileMetadataRepository.findById(permissionFieldsDto.getFileId())
				.orElseThrow(ErrorCode.FILE_NOT_FOUND::baseException);
		} else {
			fileMetadata = fileMetadataRepository.findByIdForShare(permissionFieldsDto.getFileId())
				.orElseThrow(ErrorCode.FILE_NOT_FOUND::baseException);
		}

		LocalDateTime sharingExpiredAt = fileMetadata.getSharingExpiredAt();
		Long ownerId = fileMetadata.getOwnerId();
		PermissionType sharedPermissionType = fileMetadata.getPermissionType();
		LocalDateTime now = LocalDateTime.now();

		validateExpiredAndPermission(sharingExpiredAt, now, ownerId, permissionFieldsDto.getUserId(),
			sharedPermissionType, permissionType);

		permissionFieldsDto.setOwnerId(ownerId);
	}

	/**
	 * 만료 기간과 쓰기 및 읽기 권한을 검증하는 메소드
	 * @param sharingExpiredAt 공유한 파일의 만료 기간
	 * @param now 사용자가 접근한 시간
	 * @param ownerId 공유한 파일의 소유자
	 * @param userId 요청한 사용자 pk
	 * @param sharedPermissionType 공유한 파일의 권한 타입(쓰기, 읽기)
	 * @param permissionType 실제 작업에 필요한 권한 타입(쓰기, 읽기)
	 */
	private void validateExpiredAndPermission(LocalDateTime sharingExpiredAt, LocalDateTime now, Long ownerId,
		Long userId, PermissionType sharedPermissionType, PermissionType permissionType) {
		// 공유 기한이 지났는데 파일에 대한 소유주가 아닌 경우 예외를 반환한다.
		if (sharingExpiredAt.isBefore(now) && !Objects.equals(userId, ownerId)) {
			log.error(
				"[SharingExpired Exception] 권한 검증 중, 공유 기한이 지나고 파일 소유주가 아닌 예외 발생. request user id = {}, file owner id = {}",
				userId, ownerId);
			throw ErrorCode.ACCESS_DENIED.baseException();
		}

		// 소유주가 아닌데 읽기 권한만 있는 파일에 쓰기 작업을 시도하면 예외를 반환한다.
		if (!Objects.equals(userId, ownerId) && Objects.equals(permissionType, PermissionType.WRITE) && Objects.equals(
			sharedPermissionType, PermissionType.READ)) {
			log.error(
				"[PermissionType Exception] 읽기 권한이 있는 사용자가 쓰기 권한 요청하여 예외 발생.request user id = {}, file owner id = {}",
				userId, ownerId);
			throw ErrorCode.ACCESS_DENIED.baseException();
		}
	}

	/**
	 * MultipartUpload에서 사용하는 권한 검증 메소드입니다.
	 * 우선 요청한 폴더에 대한 권한을 확인합니다.
	 * 이후 AOP로 파라미터를 처리한 것 처럼 활용하기 위해 onwerId를 리턴합니다.
	 * @param permissionType 메소드 실행에 필요한 권한
	 * @param fileType 검증할 파일의 타입(파일, 폴더)
	 * @param permissionFieldsDto
	 * @return
	 */
	@Transactional
	public long getOwnerIdAndCheckPermission(PermissionType permissionType, FileType fileType,
		PermissionFieldsDto permissionFieldsDto) {
		// 요청에 대한 권한을 먼저 확인한다.
		hasPermission(permissionType, fileType, permissionFieldsDto);
		FolderMetadata folderMetadata = folderMetadataRepository.findByIdForShare(permissionFieldsDto.getFolderId())
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);
		return folderMetadata.getOwnerId();
	}
}
