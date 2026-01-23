package com.woowacamp.storage.domain.file.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.woowacamp.storage.domain.file.dto.FileMoveDto;
import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.file.repository.FileMetadataRepository;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.event.FolderSizeEvent;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.global.constant.CommonConstant;
import com.woowacamp.storage.global.constant.PermissionType;
import com.woowacamp.storage.global.constant.UploadStatus;
import com.woowacamp.storage.global.error.CustomException;
import com.woowacamp.storage.global.error.ErrorCode;
import com.woowacamp.storage.global.util.ValidateParentsUtil;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class FileServiceTest {

	@InjectMocks
	private FileService fileService;

	@Mock private FileMetadataRepository fileMetadataRepository;
	@Mock private FileMetadataJpaRepository fileMetadataJpaRepository;

	// NOTE: 기존 코드가 변수명이 folderMetadataRepository였지만 실제 타입은 JpaRepository였음(그대로 유지)
	@Mock private FolderMetadataJpaRepository folderMetadataRepository;

	@Mock private ApplicationEventPublisher eventPublisher;
	@Mock private ValidateParentsUtil validateParentsUtil;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(fileService, "pageSize", 2);
	}

	// ====== DTO helpers (record는 mock 금지) ======
	private FileMoveDto moveDto(long targetFolderId, long userId, long rootId, String fileName) {
		return new FileMoveDto(targetFolderId, userId, rootId, fileName);
	}

	// ====== FolderMetadata fixture (✅ mock 금지) ======
	private FolderMetadata folder(
		long id,
		long rootId,
		long ownerId,
		Long parentFolderId,
		String uploadFolderName,
		String nameFullPath,
		String idFullPath,
		int namePathLength,
		LocalDateTime sharingExpiredAt
	) {
		LocalDateTime now = LocalDateTime.now();
		return FolderMetadata.builder()
			.id(id)
			.rootId(rootId)
			.ownerId(ownerId)
			.creatorId(ownerId)
			.createdAt(now)
			.updatedAt(now)
			.parentFolderId(parentFolderId)
			.uploadFolderName(uploadFolderName)
			.size(0L)
			.sharingExpiredAt(sharingExpiredAt)
			.permissionType(PermissionType.NONE)
			.isDeleted(false)
			.version(0L)
			.nameFullPath(nameFullPath)
			.idFullPath(idFullPath)
			.namePathLength(namePathLength)
			.isMoving(false)
			.build();
	}

	// ====== FileMetadata fixture (✅ mock 금지) ======
	private FileMetadata file(
		long id,
		long rootId,
		long ownerId,
		long parentFolderId,
		long fileSize,
		String uploadFileName,
		UploadStatus uploadStatus
	) {
		LocalDateTime now = LocalDateTime.now();
		return FileMetadata.builder()
			.id(id)
			.rootId(rootId)
			.creatorId(ownerId)
			.ownerId(ownerId)
			.fileType("FILE")
			.createdAt(now)
			.updatedAt(now)
			.parentFolderId(parentFolderId)
			.fileSize(fileSize)
			.uploadFileName(uploadFileName)
			.uuidFileName("uuid-" + id)
			.uploadStatus(uploadStatus)
			.thumbnailUUID(null)
			.sharingExpiredAt(CommonConstant.UNAVAILABLE_TIME)
			.permissionType(PermissionType.NONE)
			.nameFullPath("/p/" + uploadFileName + "/")
			.idFullPath("/" + parentFolderId + "/" + id + "/")
			.namePathLength(("/p/" + uploadFileName + "/").length())
			.build();
	}

	// =========================================================
	// moveFile
	// =========================================================
	@Nested
	@DisplayName("moveFile")
	class MoveFileTests {

		@Test
		@DisplayName("실패: 파일 없음 -> FILE_NOT_FOUND, target/validate/publish 진행 안 함")
		void fail_file_not_found() {
			long fileId = 1L;
			FileMoveDto dto = moveDto(100L, 10L, 999L, "a.txt");

			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.empty());

			CustomException ex = assertThrows(CustomException.class, () -> fileService.moveFile(fileId, dto));
			assertEquals(ErrorCode.FILE_NOT_FOUND.getMessage(), ex.getMessage());

			then(folderMetadataRepository).shouldHaveNoInteractions();
			then(validateParentsUtil).shouldHaveNoInteractions();
			then(eventPublisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: target 폴더 없음 -> FOLDER_NOT_FOUND")
		void fail_target_folder_not_found() {
			long fileId = 1L;
			FileMoveDto dto = moveDto(100L, 10L, 999L, "a.txt");

			FileMetadata f = file(fileId, dto.rootId(), dto.userId(), 111L, 1L, "a.txt", UploadStatus.SUCCESS);
			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.of(f));

			given(folderMetadataRepository.findByIdNotDeleted(dto.targetFolderId())).willReturn(Optional.empty());

			CustomException ex = assertThrows(CustomException.class, () -> fileService.moveFile(fileId, dto));
			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), ex.getMessage());

			then(validateParentsUtil).shouldHaveNoInteractions();
			then(eventPublisher).shouldHaveNoInteractions();

			// file 상태 변화 없음을 실객체로 검증
			assertEquals(111L, f.getParentFolderId());
		}

		@Test
		@DisplayName("실패: target owner != dto.userId -> ACCESS_DENIED (validateMetadata 전 종료)")
		void fail_target_owner_mismatch() {
			long fileId = 1L;
			FileMoveDto dto = moveDto(100L, 10L, 999L, "a.txt");

			FileMetadata f = file(fileId, dto.rootId(), dto.userId(), 111L, 1L, "a.txt", UploadStatus.SUCCESS);

			// target.owner mismatch
			FolderMetadata target = folder(dto.targetFolderId(), dto.rootId(), 999L, 1L,
				"t", "/t/", "/100/", 3, CommonConstant.UNAVAILABLE_TIME);

			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.of(f));
			given(folderMetadataRepository.findByIdNotDeleted(dto.targetFolderId())).willReturn(Optional.of(target));

			CustomException ex = assertThrows(CustomException.class, () -> fileService.moveFile(fileId, dto));
			assertEquals(ErrorCode.ACCESS_DENIED.getMessage(), ex.getMessage());

			then(fileMetadataJpaRepository).should(never())
				.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(anyLong(), anyString(), any());
			then(validateParentsUtil).shouldHaveNoInteractions();
			then(eventPublisher).shouldHaveNoInteractions();

			assertEquals(111L, f.getParentFolderId());
		}

		@Test
		@DisplayName("실패: file.owner != dto.userId -> ACCESS_DENIED")
		void fail_file_owner_mismatch() {
			long fileId = 1L;
			FileMoveDto dto = moveDto(100L, 10L, 999L, "a.txt");

			// file.owner mismatch
			FileMetadata f = file(fileId, dto.rootId(), 123L, 111L, 1L, "a.txt", UploadStatus.SUCCESS);

			FolderMetadata target = folder(dto.targetFolderId(), dto.rootId(), dto.userId(), 1L,
				"t", "/t/", "/100/", 3, CommonConstant.UNAVAILABLE_TIME);

			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.of(f));
			given(folderMetadataRepository.findByIdNotDeleted(dto.targetFolderId())).willReturn(Optional.of(target));

			CustomException ex = assertThrows(CustomException.class, () -> fileService.moveFile(fileId, dto));
			assertEquals(ErrorCode.ACCESS_DENIED.getMessage(), ex.getMessage());

			then(validateParentsUtil).shouldHaveNoInteractions();
			then(eventPublisher).shouldHaveNoInteractions();

			assertEquals(111L, f.getParentFolderId());
		}

		@Test
		@DisplayName("실패: uploadStatus != SUCCESS -> FILE_NOT_FOUND")
		void fail_upload_not_success() {
			long fileId = 1L;
			FileMoveDto dto = moveDto(100L, 10L, 999L, "a.txt");

			FileMetadata f = file(fileId, dto.rootId(), dto.userId(), 111L, 1L, "a.txt", UploadStatus.FAIL);
			FolderMetadata target = folder(dto.targetFolderId(), dto.rootId(), dto.userId(), 1L,
				"t", "/t/", "/100/", 3, CommonConstant.UNAVAILABLE_TIME);

			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.of(f));
			given(folderMetadataRepository.findByIdNotDeleted(dto.targetFolderId())).willReturn(Optional.of(target));

			CustomException ex = assertThrows(CustomException.class, () -> fileService.moveFile(fileId, dto));
			assertEquals(ErrorCode.FILE_NOT_FOUND.getMessage(), ex.getMessage());

			then(fileMetadataJpaRepository).should(never())
				.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(anyLong(), anyString(), any());
			then(validateParentsUtil).shouldHaveNoInteractions();
			then(eventPublisher).shouldHaveNoInteractions();

			assertEquals(111L, f.getParentFolderId());
		}

		@Test
		@DisplayName("실패: target에 동일 파일명 존재 -> FILE_NAME_DUPLICATE (parentsLock 호출 전 종료)")
		void fail_duplicate_file_name_in_target() {
			long fileId = 1L;
			FileMoveDto dto = moveDto(100L, 10L, 999L, "a.txt");

			FileMetadata f = file(fileId, dto.rootId(), dto.userId(), 111L, 1L, "a.txt", UploadStatus.SUCCESS);
			FolderMetadata target = folder(dto.targetFolderId(), dto.rootId(), dto.userId(), 1L,
				"t", "/t/", "/100/", 3, CommonConstant.UNAVAILABLE_TIME);

			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.of(f));
			given(folderMetadataRepository.findByIdNotDeleted(dto.targetFolderId())).willReturn(Optional.of(target));

			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
				dto.targetFolderId(), "a.txt", UploadStatus.FAIL
			)).willReturn(true);

			CustomException ex = assertThrows(CustomException.class, () -> fileService.moveFile(fileId, dto));
			assertEquals(ErrorCode.FILE_NAME_DUPLICATE.getMessage(), ex.getMessage());

			then(validateParentsUtil).shouldHaveNoInteractions();
			then(eventPublisher).shouldHaveNoInteractions();

			assertEquals(111L, f.getParentFolderId());
		}

		@Test
		@DisplayName("실패: validateParentsUtil에서 예외(PARENT_LOCKED 등) -> update/publish 없이 종료")
		void fail_parent_locked_by_util() {
			long fileId = 1L;
			FileMoveDto dto = moveDto(100L, 10L, 999L, "a.txt");

			FileMetadata f = file(fileId, dto.rootId(), dto.userId(), 111L, 1L, "a.txt", UploadStatus.SUCCESS);
			FolderMetadata target = folder(dto.targetFolderId(), dto.rootId(), dto.userId(), 1L,
				"t", "/t/", "/100/", 3, CommonConstant.UNAVAILABLE_TIME);

			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.of(f));
			given(folderMetadataRepository.findByIdNotDeleted(dto.targetFolderId())).willReturn(Optional.of(target));

			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
				dto.targetFolderId(), "a.txt", UploadStatus.FAIL
			)).willReturn(false);

			willThrow(ErrorCode.PARENT_LOCKED.baseException())
				.given(validateParentsUtil).validateParentsFolderLock(target);

			CustomException ex = assertThrows(CustomException.class, () -> fileService.moveFile(fileId, dto));
			assertEquals(ErrorCode.PARENT_LOCKED.getMessage(), ex.getMessage());

			assertEquals(111L, f.getParentFolderId());
			then(eventPublisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("성공: parentFolderId 변경 + FolderSizeEvent 2개 발행(원본 -size, 타겟 +size)")
		void success_update_and_publish_2_events() {
			long fileId = 1L;
			long originParentId = 111L;
			long targetFolderId = 222L;
			long userId = 10L;
			long rootId = 999L;
			long fileSize = 123L;

			FileMoveDto dto = moveDto(targetFolderId, userId, rootId, "a.txt");

			FileMetadata f = file(fileId, rootId, userId, originParentId, fileSize, "a.txt", UploadStatus.SUCCESS);
			FolderMetadata target = folder(targetFolderId, rootId, userId, 1L,
				"t", "/t/", "/222/", 3, CommonConstant.UNAVAILABLE_TIME);

			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.of(f));
			given(folderMetadataRepository.findByIdNotDeleted(targetFolderId)).willReturn(Optional.of(target));

			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
				targetFolderId, "a.txt", UploadStatus.FAIL
			)).willReturn(false);

			willDoNothing().given(validateParentsUtil).validateParentsFolderLock(target);

			// when
			fileService.moveFile(fileId, dto);

			// then (✅ 실객체 상태)
			assertEquals(targetFolderId, f.getParentFolderId());

			// ✅ 이벤트 2개
			ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
			then(eventPublisher).should(times(2)).publishEvent(captor.capture());

			assertTrue(captor.getAllValues().get(0) instanceof FolderSizeEvent);
			assertTrue(captor.getAllValues().get(1) instanceof FolderSizeEvent);

			FolderSizeEvent e1 = (FolderSizeEvent) captor.getAllValues().get(0);
			FolderSizeEvent e2 = (FolderSizeEvent) captor.getAllValues().get(1);

			assertEquals(originParentId, e1.getFolderMetadataId());
			assertEquals(-fileSize, e1.getSize());

			assertEquals(targetFolderId, e2.getFolderMetadataId());
			assertEquals(fileSize, e2.getSize());
		}

		@Test
		@DisplayName("호출 순서: validateParentsFolderLock 시점에는 아직 parentFolderId가 원본이다(=update 이전)")
		void order_validate_parents_before_update_without_spy() {
			long fileId = 1L;
			long originParentId = 111L;
			long targetFolderId = 222L;
			long userId = 10L;
			long rootId = 999L;

			FileMoveDto dto = moveDto(targetFolderId, userId, rootId, "a.txt");

			FileMetadata f = file(fileId, rootId, userId, originParentId, 1L, "a.txt", UploadStatus.SUCCESS);
			FolderMetadata target = folder(targetFolderId, rootId, userId, 1L,
				"t", "/t/", "/222/", 3, CommonConstant.UNAVAILABLE_TIME);

			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.of(f));
			given(folderMetadataRepository.findByIdNotDeleted(targetFolderId)).willReturn(Optional.of(target));

			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
				targetFolderId, "a.txt", UploadStatus.FAIL
			)).willReturn(false);

			// ✅ validateParentsUtil 호출 "시점"에 아직 update가 안 됐음을 강제 검증
			willAnswer(inv -> {
				assertEquals(originParentId, f.getParentFolderId(), "lock validation must happen BEFORE parent update");
				return null;
			}).given(validateParentsUtil).validateParentsFolderLock(target);

			// when
			fileService.moveFile(fileId, dto);

			// then (최종 상태는 변경)
			assertEquals(targetFolderId, f.getParentFolderId());

			// 그리고 validateParentsUtil -> publishEvent 순서도 보장
			InOrder inOrder = inOrder(validateParentsUtil, eventPublisher);
			inOrder.verify(validateParentsUtil).validateParentsFolderLock(target);
			inOrder.verify(eventPublisher, times(2)).publishEvent(any(FolderSizeEvent.class));

		}
	}

	// =========================================================
	// getFileMetadataBy
	// =========================================================
	@Nested
	@DisplayName("getFileMetadataBy")
	class GetFileMetadataByTests {

		@Test
		@DisplayName("실패: 파일 없음 -> FILE_NOT_FOUND")
		void fail_not_found() {
			given(fileMetadataJpaRepository.findById(1L)).willReturn(Optional.empty());

			CustomException ex = assertThrows(CustomException.class, () -> fileService.getFileMetadataBy(1L, 10L));
			assertEquals(ErrorCode.FILE_NOT_FOUND.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("실패: owner 불일치 -> ACCESS_DENIED")
		void fail_owner_mismatch() {
			FileMetadata f = file(1L, 999L, 999L, 111L, 1L, "a.txt", UploadStatus.SUCCESS);
			given(fileMetadataJpaRepository.findById(1L)).willReturn(Optional.of(f));

			CustomException ex = assertThrows(CustomException.class, () -> fileService.getFileMetadataBy(1L, 10L));
			assertEquals(ErrorCode.ACCESS_DENIED.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("성공: owner 일치 -> FileMetadata 반환")
		void success_returns_file() {
			FileMetadata f = file(1L, 999L, 10L, 111L, 1L, "a.txt", UploadStatus.SUCCESS);
			given(fileMetadataJpaRepository.findById(1L)).willReturn(Optional.of(f));

			FileMetadata result = fileService.getFileMetadataBy(1L, 10L);
			assertSame(f, result);
		}
	}

	// =========================================================
	// deleteFile
	// =========================================================
	@Nested
	@DisplayName("deleteFile")
	class DeleteFileTests {

		@Test
		@DisplayName("실패: 조건 불일치/없음 -> ACCESS_DENIED, softDelete 호출 안 함")
		void fail_access_denied() {
			given(fileMetadataJpaRepository.findByIdAndOwnerIdAndUploadStatusNot(1L, 10L, UploadStatus.FAIL))
				.willReturn(Optional.empty());

			CustomException ex = assertThrows(CustomException.class, () -> fileService.deleteFile(1L, 10L));
			assertEquals(ErrorCode.ACCESS_DENIED.getMessage(), ex.getMessage());

			then(fileMetadataJpaRepository).should(never()).softDelete(anyLong());
		}

		@Test
		@DisplayName("성공: softDelete 호출")
		void success_soft_delete_called() {
			FileMetadata f = file(1L, 999L, 10L, 111L, 1L, "a.txt", UploadStatus.SUCCESS);

			given(fileMetadataJpaRepository.findByIdAndOwnerIdAndUploadStatusNot(1L, 10L, UploadStatus.FAIL))
				.willReturn(Optional.of(f));

			fileService.deleteFile(1L, 10L);

			then(fileMetadataJpaRepository).should().softDelete(1L);
		}
	}

	// =========================================================
	// doHardDelete
	// =========================================================
	@Nested
	@DisplayName("doHardDelete")
	class DoHardDeleteTests {

		@Test
		@DisplayName("성공: 빈 리스트면 deleteAll 없이 종료")
		void success_empty_first_page() {
			given(fileMetadataRepository.findSoftDeletedFileWithLastIdAndDuration(isNull(), eq(2), any(LocalDateTime.class)))
				.willReturn(List.of());

			fileService.doHardDelete();

			then(fileMetadataRepository).should(never()).deleteAll(anyList());
			then(fileMetadataRepository).should(times(1))
				.findSoftDeletedFileWithLastIdAndDuration(isNull(), eq(2), any(LocalDateTime.class));
		}

		@Test
		@DisplayName("성공: 페이지 단위로 반복 삭제(2개 -> 2개 -> 1개)")
		void success_multi_pages() {
			FileMetadata f1 = file(1L, 1L, 1L, 10L, 1L, "f1", UploadStatus.SUCCESS);
			FileMetadata f2 = file(2L, 1L, 1L, 10L, 1L, "f2", UploadStatus.SUCCESS);
			FileMetadata f3 = file(3L, 1L, 1L, 10L, 1L, "f3", UploadStatus.SUCCESS);
			FileMetadata f4 = file(4L, 1L, 1L, 10L, 1L, "f4", UploadStatus.SUCCESS);
			FileMetadata f5 = file(5L, 1L, 1L, 10L, 1L, "f5", UploadStatus.SUCCESS);

			given(fileMetadataRepository.findSoftDeletedFileWithLastIdAndDuration(any(), eq(2), any(LocalDateTime.class)))
				.willAnswer(inv -> {
					Long lastId = inv.getArgument(0);
					if (lastId == null) return List.of(f1, f2);
					if (lastId.equals(2L)) return List.of(f3, f4);
					if (lastId.equals(4L)) return List.of(f5);
					return List.of();
				});

			fileService.doHardDelete();

			then(fileMetadataRepository).should().deleteAll(List.of(f1, f2));
			then(fileMetadataRepository).should().deleteAll(List.of(f3, f4));
			then(fileMetadataRepository).should().deleteAll(List.of(f5));
		}

		@Test
		@DisplayName("검증: timeLimit 파라미터는 now - hardDeleteDuration(days)에 가까운 값이다")
		void verify_time_limit_argument() {
			ArgumentCaptor<LocalDateTime> timeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

			given(fileMetadataRepository.findSoftDeletedFileWithLastIdAndDuration(isNull(), eq(2), any(LocalDateTime.class)))
				.willReturn(List.of()); // 바로 종료

			LocalDateTime before = LocalDateTime.now();

			fileService.doHardDelete();

			then(fileMetadataRepository).should()
				.findSoftDeletedFileWithLastIdAndDuration(isNull(), eq(2), timeCaptor.capture());

			LocalDateTime after = LocalDateTime.now();
			LocalDateTime passed = timeCaptor.getValue();

			LocalDateTime expectedLower = before.minusDays(CommonConstant.hardDeleteDuration);
			LocalDateTime expectedUpper = after.minusDays(CommonConstant.hardDeleteDuration);

			assertFalse(passed.isBefore(expectedLower), "timeLimit should be >= (before - hardDeleteDuration)");
			assertFalse(passed.isAfter(expectedUpper), "timeLimit should be <= (after - hardDeleteDuration)");
		}
	}
}
