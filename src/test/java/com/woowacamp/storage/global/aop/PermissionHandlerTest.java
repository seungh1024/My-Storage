package com.woowacamp.storage.global.aop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataRepository;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.global.aop.type.FileType;
import com.woowacamp.storage.global.constant.PermissionType;
import com.woowacamp.storage.global.error.CustomException;
import com.woowacamp.storage.global.error.ErrorCode;

@ExtendWith(MockitoExtension.class)
class PermissionHandlerTest {

	@InjectMocks
	PermissionHandler permissionHandler;
	@Mock
	FolderMetadataJpaRepository folderMetadataRepository;
	@Mock
	FileMetadataRepository fileMetadataRepository;

	long userId = 1;
	long fileId = 1;
	long folderId = 1;
	long moveFolderId = 2;
	LocalDateTime expiredAt = LocalDateTime.of(2025, 1, 1, 0, 0);
	LocalDateTime expiredTime = LocalDateTime.of(2024, 1, 1, 0, 0);

	PermissionFieldsDto getPermissionFieldsDto() {
		PermissionFieldsDto permissionFieldsDto = new PermissionFieldsDto();
		permissionFieldsDto.setUserId(userId);
		permissionFieldsDto.setFileId(fileId);
		permissionFieldsDto.setFolderId(folderId);
		permissionFieldsDto.setMoveFolderId(moveFolderId);

		return permissionFieldsDto;
	}

	FileMetadata.FileMetadataBuilder getFileMetadataBuilder() {
		return FileMetadata.builder().id(fileId).ownerId(userId).sharingExpiredAt(expiredAt);
	}

	FileMetadata getWriteFileMetadata() {
		return getFileMetadataBuilder().permissionType(PermissionType.WRITE).build();
	}

	FileMetadata getReadFileMetadata() {
		return getFileMetadataBuilder().permissionType(PermissionType.READ).build();
	}

	FileMetadata getExpiredFileMetadata() {
		return getFileMetadataBuilder().permissionType(PermissionType.WRITE).sharingExpiredAt(expiredTime).build();
	}

	FolderMetadata.FolderMetadataBuilder getFolderMetadataBuilder() {
		return FolderMetadata.builder().id(folderId).ownerId(userId).sharingExpiredAt(expiredAt);
	}

	FolderMetadata getWriteFolderMetadata() {
		return getFolderMetadataBuilder().permissionType(PermissionType.WRITE).build();
	}

	FolderMetadata getReadFolderMetadata() {
		return getFolderMetadataBuilder().permissionType(PermissionType.READ).build();
	}

	FolderMetadata getExpiredFolderMetadata() {
		return getFolderMetadataBuilder().permissionType(PermissionType.WRITE).sharingExpiredAt(expiredTime).build();
	}

	@Nested
	@DisplayName("파일 권한 확인 테스트")
	class ValidateFile {

		@Test
		@DisplayName("접근 권한이 있으면 권한 인증에 성공한다.")
		void request_with_valid_permission() {
			// Given
			PermissionFieldsDto permissionFieldsDto = getPermissionFieldsDto();
			permissionFieldsDto.setUserId(2L);
			permissionFieldsDto.setMoveFolderId(null);
			given(fileMetadataRepository.findById(fileId)).willReturn(Optional.of(getWriteFileMetadata()));

			// When
			permissionHandler.hasPermission(PermissionType.READ, FileType.FILE, permissionFieldsDto);
		}

		@Test
		@DisplayName("읽기 권한인 파일에 쓰기 작업을 요청한 경우 인증에 실패한다.")
		void request_with_read_permission_to_write_permission() {
			// Given
			PermissionFieldsDto permissionFieldsDto = getPermissionFieldsDto();
			permissionFieldsDto.setUserId(2L);
			given(fileMetadataRepository.findByIdForShare(fileId)).willReturn(Optional.of(getReadFileMetadata()));

			// When
			CustomException customException = assertThrows(CustomException.class, () -> {
				permissionHandler.hasPermission(PermissionType.WRITE, FileType.FILE, permissionFieldsDto);
			});

			// Then
			assertEquals(ErrorCode.ACCESS_DENIED.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("공유 기한이 지난 파일에 대한 요청에서, 소유주가 아닌 경우 예외를 반환한다.")
		void request_with_permission_expired_and_not_owner() {
			//Given
			PermissionFieldsDto permissionFieldsDto = getPermissionFieldsDto();
			permissionFieldsDto.setUserId(2L);
			given(fileMetadataRepository.findByIdForShare(fileId)).willReturn(Optional.of(getExpiredFileMetadata()));

			// When
			CustomException customException = assertThrows(CustomException.class, () -> {
				permissionHandler.hasPermission(PermissionType.WRITE, FileType.FILE, permissionFieldsDto);
			});

			// Then
			assertEquals(ErrorCode.ACCESS_DENIED.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("공유 기한이 지났는데 파일에 대한 소유주라면 권한 인증에 성공한다.")
		void request_with_permission_expired_and_owner() {
			//Given
			PermissionFieldsDto permissionFieldsDto = getPermissionFieldsDto();
			permissionFieldsDto.setMoveFolderId(null);
			given(fileMetadataRepository.findByIdForShare(fileId)).willReturn(Optional.of(getExpiredFileMetadata()));

			// When
			permissionHandler.hasPermission(PermissionType.WRITE, FileType.FILE, permissionFieldsDto);
		}

		@Test
		@DisplayName("존재하지 않는 파일에 대한 요청 시 FILE_NOT_FOUND 예외를 반환한다.")
		void request_with_not_exist_file() {
			//Given
			PermissionFieldsDto permissionFieldsDto = getPermissionFieldsDto();
			given(fileMetadataRepository.findByIdForShare(fileId)).willReturn(Optional.empty());

			// When
			CustomException customException = assertThrows(CustomException.class, () -> {
				permissionHandler.hasPermission(PermissionType.WRITE, FileType.FILE, permissionFieldsDto);
			});

			// Then
			assertEquals(ErrorCode.FILE_NOT_FOUND.getMessage(), customException.getMessage());
		}
	}

	@Nested
	@DisplayName("폴더 권한 확인 테스트")
	class ValidateFolder {

		@Test
		@DisplayName("접근 권한이 있으면 권한 인증에 성공한다.")
		void request_with_valid_permission() {
			// Given
			PermissionFieldsDto permissionFieldsDto = getPermissionFieldsDto();
			permissionFieldsDto.setMoveFolderId(null);
			given(folderMetadataRepository.findById(folderId)).willReturn(Optional.of(getWriteFolderMetadata()));

			// when
			permissionHandler.hasPermission(PermissionType.READ, FileType.FOLDER, permissionFieldsDto);
		}

		@Test
		@DisplayName("읽기 권한인 폴더에 쓰기 작업을 요청한 경우 인증에 실패한다.")
		void request_with_read_permission_to_write_permission() {
			// Given
			PermissionFieldsDto permissionFieldsDto = getPermissionFieldsDto();
			permissionFieldsDto.setUserId(2L);
			given(folderMetadataRepository.findByIdForShare(folderId)).willReturn(Optional.of(getReadFolderMetadata()));

			// When
			CustomException customException = assertThrows(CustomException.class, () -> {
				permissionHandler.hasPermission(PermissionType.WRITE, FileType.FOLDER, permissionFieldsDto);
			});

			// Then
			assertEquals(ErrorCode.ACCESS_DENIED.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("공유 기한이 지난 폴더에 대한 요청에서, 소유주가 아닌 경우 예외를 반환한다.")
		void request_with_permission_expired_and_not_owner() {
			//Given
			PermissionFieldsDto permissionFieldsDto = getPermissionFieldsDto();
			permissionFieldsDto.setUserId(2L);
			given(folderMetadataRepository.findByIdForShare(folderId)).willReturn(
				Optional.of(getExpiredFolderMetadata()));

			// When
			CustomException customException = assertThrows(CustomException.class, () -> {
				permissionHandler.hasPermission(PermissionType.WRITE, FileType.FOLDER, permissionFieldsDto);
			});

			// Then
			assertEquals(ErrorCode.ACCESS_DENIED.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("공유 기한이 지났지만 파일에 대한 소유주라면 권한 인증에 성공한다.")
		void request_with_permission_expired_and_owner() {
			//Given
			PermissionFieldsDto permissionFieldsDto = getPermissionFieldsDto();
			permissionFieldsDto.setMoveFolderId(null);
			given(folderMetadataRepository.findByIdForShare(folderId)).willReturn(
				Optional.of(getExpiredFolderMetadata()));

			// When
			permissionHandler.hasPermission(PermissionType.WRITE, FileType.FOLDER, permissionFieldsDto);
		}

		@Test
		@DisplayName("폴더 이동 요청에서는 이동할 폴더에 대한 권한까지 존재해야 이동에 성공한다.")
		void request_with_move_folder() {
			//Given
			PermissionFieldsDto permissionFieldsDto = getPermissionFieldsDto();
			given(folderMetadataRepository.findByIdForShare(folderId)).willReturn(
				Optional.of(getExpiredFolderMetadata()));
			given(folderMetadataRepository.findByIdForShare(moveFolderId)).willReturn(
				Optional.of(getExpiredFolderMetadata()));

			// When
			permissionHandler.hasPermission(PermissionType.WRITE, FileType.FOLDER, permissionFieldsDto);
		}

		@Test
		@DisplayName("존재하지 않는 폴더에 대한 요청 시 FOLDER_NOT_FOUND 예외를 반환한다..")
		void request_with_not_exist_folder() {
			//Given
			PermissionFieldsDto permissionFieldsDto = getPermissionFieldsDto();
			given(folderMetadataRepository.findByIdForShare(folderId)).willReturn(Optional.empty());

			// When
			CustomException customException = assertThrows(CustomException.class, () -> {
				permissionHandler.hasPermission(PermissionType.WRITE, FileType.FOLDER, permissionFieldsDto);
			});

			// Then
			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), customException.getMessage());
		}

	}
}
