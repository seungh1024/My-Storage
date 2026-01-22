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

	@Mock
	private FileMetadataRepository fileMetadataRepository;
	@Mock
	private FileMetadataJpaRepository fileMetadataJpaRepository;
	@Mock
	private FolderMetadataJpaRepository folderMetadataRepository;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@Mock
	private ValidateParentsUtil validateParentsUtil;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(fileService, "pageSize", 2);
	}

	// ====== DTO helpers (record는 mock 금지) ======
	private FileMoveDto moveDto(long targetFolderId, long userId, long rootId, String fileName) {
		return new FileMoveDto(targetFolderId, userId, rootId, fileName);
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
			// given
			long fileId = 1L;
			FileMoveDto dto = moveDto(100L, 10L, 999L, "a.txt");

			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.empty());

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> fileService.moveFile(fileId, dto));
			assertEquals(ErrorCode.FILE_NOT_FOUND.getMessage(), ex.getMessage());

			then(folderMetadataRepository).shouldHaveNoInteractions();
			then(validateParentsUtil).shouldHaveNoInteractions();
			then(eventPublisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: target 폴더 없음 -> FOLDER_NOT_FOUND")
		void fail_target_folder_not_found() {
			// given
			long fileId = 1L;
			FileMoveDto dto = moveDto(100L, 10L, 999L, "a.txt");

			FileMetadata file = mock(FileMetadata.class);
			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.of(file));
			given(folderMetadataRepository.findByIdNotDeleted(dto.targetFolderId())).willReturn(Optional.empty());

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> fileService.moveFile(fileId, dto));
			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), ex.getMessage());

			then(validateParentsUtil).shouldHaveNoInteractions();
			then(eventPublisher).shouldHaveNoInteractions();
			then(file).should(never()).updateParentFolderId(anyLong());
		}

		@Test
		@DisplayName("실패: target owner != dto.userId -> ACCESS_DENIED (validateMetadata 전 종료)")
		void fail_target_owner_mismatch() {
			// given
			long fileId = 1L;
			FileMoveDto dto = moveDto(100L, 10L, 999L, "a.txt");

			FileMetadata file = mock(FileMetadata.class);
			FolderMetadata target = mock(FolderMetadata.class);

			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.of(file));
			given(folderMetadataRepository.findByIdNotDeleted(dto.targetFolderId())).willReturn(Optional.of(target));
			given(target.getOwnerId()).willReturn(999L); // mismatch

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> fileService.moveFile(fileId, dto));
			assertEquals(ErrorCode.ACCESS_DENIED.getMessage(), ex.getMessage());

			then(fileMetadataJpaRepository).should(never())
				.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(anyLong(), anyString(), any());
			then(validateParentsUtil).shouldHaveNoInteractions();
			then(eventPublisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: file.owner != dto.userId (또는 target.owner) -> ACCESS_DENIED")
		void fail_file_owner_mismatch() {
			// given
			long fileId = 1L;
			FileMoveDto dto = moveDto(100L, 10L, 999L, "a.txt");

			FileMetadata file = mock(FileMetadata.class);
			FolderMetadata target = mock(FolderMetadata.class);

			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.of(file));
			given(folderMetadataRepository.findByIdNotDeleted(dto.targetFolderId())).willReturn(Optional.of(target));

			// 1차 owner 체크 통과
			given(target.getOwnerId()).willReturn(dto.userId());

			// validateMetadata에서 실패
			given(file.getOwnerId()).willReturn(123L); // dto.userId(10)와 다름

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> fileService.moveFile(fileId, dto));
			assertEquals(ErrorCode.ACCESS_DENIED.getMessage(), ex.getMessage());

			then(validateParentsUtil).shouldHaveNoInteractions();
			then(eventPublisher).shouldHaveNoInteractions();
			then(file).should(never()).updateParentFolderId(anyLong());
		}

		@Test
		@DisplayName("실패: uploadStatus != SUCCESS -> FILE_NOT_FOUND")
		void fail_upload_not_success() {
			// given
			long fileId = 1L;
			FileMoveDto dto = moveDto(100L, 10L, 999L, "a.txt");

			FileMetadata file = mock(FileMetadata.class);
			FolderMetadata target = mock(FolderMetadata.class);

			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.of(file));
			given(folderMetadataRepository.findByIdNotDeleted(dto.targetFolderId())).willReturn(Optional.of(target));

			given(target.getOwnerId()).willReturn(dto.userId());
			given(file.getOwnerId()).willReturn(dto.userId());
			given(file.getUploadStatus()).willReturn(UploadStatus.FAIL);

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> fileService.moveFile(fileId, dto));
			assertEquals(ErrorCode.FILE_NOT_FOUND.getMessage(), ex.getMessage());

			then(fileMetadataJpaRepository).should(never())
				.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(anyLong(), anyString(), any());
			then(validateParentsUtil).shouldHaveNoInteractions();
			then(eventPublisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: target에 동일 파일명 존재 -> FILE_NAME_DUPLICATE (parentsLock 호출 전 종료)")
		void fail_duplicate_file_name_in_target() {
			// given
			long fileId = 1L;
			FileMoveDto dto = moveDto(100L, 10L, 999L, "a.txt");

			FileMetadata file = mock(FileMetadata.class);
			FolderMetadata target = mock(FolderMetadata.class);

			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.of(file));
			given(folderMetadataRepository.findByIdNotDeleted(dto.targetFolderId())).willReturn(Optional.of(target));

			given(target.getOwnerId()).willReturn(dto.userId());
			given(file.getOwnerId()).willReturn(dto.userId());
			given(file.getUploadStatus()).willReturn(UploadStatus.SUCCESS);
			given(file.getUploadFileName()).willReturn("a.txt");

			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
				dto.targetFolderId(), "a.txt", UploadStatus.FAIL
			)).willReturn(true);

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> fileService.moveFile(fileId, dto));
			assertEquals(ErrorCode.FILE_NAME_DUPLICATE.getMessage(), ex.getMessage());

			then(validateParentsUtil).shouldHaveNoInteractions();
			then(eventPublisher).shouldHaveNoInteractions();
			then(file).should(never()).updateParentFolderId(anyLong());
		}

		@Test
		@DisplayName("실패: validateParentsUtil에서 예외(PARENT_LOCKED 등) -> update/publish 없이 종료")
		void fail_parent_locked_by_util() {
			// given
			long fileId = 1L;
			FileMoveDto dto = moveDto(100L, 10L, 999L, "a.txt");

			FileMetadata file = mock(FileMetadata.class);
			FolderMetadata target = mock(FolderMetadata.class);

			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.of(file));
			given(folderMetadataRepository.findByIdNotDeleted(dto.targetFolderId())).willReturn(Optional.of(target));

			given(target.getOwnerId()).willReturn(dto.userId()); // ✅ 여기까지만(= owner 체크 통과)

			given(file.getOwnerId()).willReturn(dto.userId());
			given(file.getUploadStatus()).willReturn(UploadStatus.SUCCESS);
			given(file.getUploadFileName()).willReturn("a.txt");

			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
				dto.targetFolderId(), "a.txt", UploadStatus.FAIL
			)).willReturn(false);

			willThrow(ErrorCode.PARENT_LOCKED.baseException())
				.given(validateParentsUtil).validateParentsFolderLock(target);

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> fileService.moveFile(fileId, dto));
			assertEquals(ErrorCode.PARENT_LOCKED.getMessage(), ex.getMessage());

			then(file).should(never()).updateParentFolderId(anyLong());
			then(eventPublisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("성공: parentFolderId 변경 + FolderSizeEvent 2개 발행(원본 -size, 타겟 +size)")
		void success_update_and_publish_2_events() {
			// given
			long fileId = 1L;
			long originParentId = 111L;
			long targetFolderId = 222L;
			long userId = 10L;
			long rootId = 999L;
			long fileSize = 123L;

			FileMoveDto dto = moveDto(targetFolderId, userId, rootId, "a.txt");

			FileMetadata file = mock(FileMetadata.class);
			FolderMetadata target = mock(FolderMetadata.class);

			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.of(file));
			given(folderMetadataRepository.findByIdNotDeleted(targetFolderId)).willReturn(Optional.of(target));

			given(target.getOwnerId()).willReturn(userId);
			given(target.getId()).willReturn(targetFolderId);

			given(file.getOwnerId()).willReturn(userId);
			given(file.getUploadStatus()).willReturn(UploadStatus.SUCCESS);
			given(file.getUploadFileName()).willReturn("a.txt");
			given(file.getParentFolderId()).willReturn(originParentId);
			given(file.getFileSize()).willReturn(fileSize);

			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
				targetFolderId, "a.txt", UploadStatus.FAIL
			)).willReturn(false);

			willDoNothing().given(validateParentsUtil).validateParentsFolderLock(target);

			// when
			fileService.moveFile(fileId, dto);

			// then
			then(validateParentsUtil).should().validateParentsFolderLock(target);
			then(file).should().updateParentFolderId(targetFolderId);

			ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
			then(eventPublisher).should(times(2)).publishEvent(captor.capture());

			assertEquals(2, captor.getAllValues().size());
			assertTrue(captor.getAllValues().get(0) instanceof FolderSizeEvent);
			assertTrue(captor.getAllValues().get(1) instanceof FolderSizeEvent);

			FolderSizeEvent e1 = (FolderSizeEvent)captor.getAllValues().get(0);
			FolderSizeEvent e2 = (FolderSizeEvent)captor.getAllValues().get(1);

			assertEquals(originParentId, e1.getFolderMetadataId());
			assertEquals(-fileSize, e1.getSize());

			assertEquals(targetFolderId, e2.getFolderMetadataId());
			assertEquals(fileSize, e2.getSize());
		}

		@Test
		@DisplayName("호출 순서: validateParentsFolderLock이 updateParentFolderId 이전에 수행된다")
		void order_validate_parents_before_update() {
			// given
			long fileId = 1L;
			long originParentId = 111L;
			long targetFolderId = 222L;
			long userId = 10L;

			FileMoveDto dto = moveDto(targetFolderId, userId, 999L, "a.txt");

			FileMetadata file = mock(FileMetadata.class);
			FolderMetadata target = mock(FolderMetadata.class);

			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.of(file));
			given(folderMetadataRepository.findByIdNotDeleted(targetFolderId)).willReturn(Optional.of(target));

			given(target.getOwnerId()).willReturn(userId);
			given(target.getId()).willReturn(targetFolderId);

			given(file.getOwnerId()).willReturn(userId);
			given(file.getUploadStatus()).willReturn(UploadStatus.SUCCESS);
			given(file.getUploadFileName()).willReturn("a.txt");
			given(file.getParentFolderId()).willReturn(originParentId);
			given(file.getFileSize()).willReturn(1L);

			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
				targetFolderId, "a.txt", UploadStatus.FAIL
			)).willReturn(false);

			// when
			fileService.moveFile(fileId, dto);

			// then
			InOrder inOrder = inOrder(validateParentsUtil, file, eventPublisher);
			inOrder.verify(validateParentsUtil).validateParentsFolderLock(target);
			inOrder.verify(file).updateParentFolderId(targetFolderId);
			inOrder.verify(eventPublisher).publishEvent(any(FolderSizeEvent.class));
			inOrder.verify(eventPublisher).publishEvent(any(FolderSizeEvent.class));
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
			// given
			given(fileMetadataJpaRepository.findById(1L)).willReturn(Optional.empty());

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> fileService.getFileMetadataBy(1L, 10L));
			assertEquals(ErrorCode.FILE_NOT_FOUND.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("실패: owner 불일치 -> ACCESS_DENIED")
		void fail_owner_mismatch() {
			// given
			FileMetadata file = mock(FileMetadata.class);
			given(fileMetadataJpaRepository.findById(1L)).willReturn(Optional.of(file));
			given(file.getOwnerId()).willReturn(999L);

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> fileService.getFileMetadataBy(1L, 10L));
			assertEquals(ErrorCode.ACCESS_DENIED.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("성공: owner 일치 -> FileMetadata 반환")
		void success_returns_file() {
			// given
			FileMetadata file = mock(FileMetadata.class);
			given(fileMetadataJpaRepository.findById(1L)).willReturn(Optional.of(file));
			given(file.getOwnerId()).willReturn(10L);

			// when
			FileMetadata result = fileService.getFileMetadataBy(1L, 10L);

			// then
			assertSame(file, result);
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
			// given
			given(fileMetadataJpaRepository.findByIdAndOwnerIdAndUploadStatusNot(1L, 10L, UploadStatus.FAIL))
				.willReturn(Optional.empty());

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> fileService.deleteFile(1L, 10L));
			assertEquals(ErrorCode.ACCESS_DENIED.getMessage(), ex.getMessage());

			then(fileMetadataJpaRepository).should(never()).softDelete(anyLong());
		}

		@Test
		@DisplayName("성공: softDelete 호출")
		void success_soft_delete_called() {
			// given
			FileMetadata file = mock(FileMetadata.class);
			given(fileMetadataJpaRepository.findByIdAndOwnerIdAndUploadStatusNot(1L, 10L, UploadStatus.FAIL))
				.willReturn(Optional.of(file));
			given(file.getId()).willReturn(1L);

			// when
			fileService.deleteFile(1L, 10L);

			// then
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
			// given
			given(fileMetadataRepository.findSoftDeletedFileWithLastIdAndDuration(isNull(), eq(2),
				any(LocalDateTime.class)))
				.willReturn(List.of());

			// when
			fileService.doHardDelete();

			// then
			then(fileMetadataRepository).should(never()).deleteAll(anyList());
			then(fileMetadataRepository).should(times(1))
				.findSoftDeletedFileWithLastIdAndDuration(isNull(), eq(2), any(LocalDateTime.class));
		}

		@Test
		@DisplayName("성공: 페이지 단위로 반복 삭제(2개 -> 2개 -> 1개)")
		void success_multi_pages() {
			// given
			FileMetadata f1 = mock(FileMetadata.class);
			FileMetadata f2 = mock(FileMetadata.class);
			FileMetadata f3 = mock(FileMetadata.class);
			FileMetadata f4 = mock(FileMetadata.class);
			FileMetadata f5 = mock(FileMetadata.class);

			// ✅ lastId 계산에 실제로 쓰이는 건 f2, f4 뿐이다
			given(f2.getId()).willReturn(2L);
			given(f4.getId()).willReturn(4L);

			given(
				fileMetadataRepository.findSoftDeletedFileWithLastIdAndDuration(any(), eq(2), any(LocalDateTime.class)))
				.willAnswer(inv -> {
					Long lastId = inv.getArgument(0);
					if (lastId == null)
						return List.of(f1, f2);
					if (lastId.equals(2L))
						return List.of(f3, f4);
					if (lastId.equals(4L))
						return List.of(f5);
					return List.of();
				});

			// when
			fileService.doHardDelete();

			// then
			then(fileMetadataRepository).should().deleteAll(List.of(f1, f2));
			then(fileMetadataRepository).should().deleteAll(List.of(f3, f4));
			then(fileMetadataRepository).should().deleteAll(List.of(f5));
		}

		@Test
		@DisplayName("검증: timeLimit 파라미터는 now - hardDeleteDuration(days)에 가까운 값이다")
		void verify_time_limit_argument() {
			// given
			ArgumentCaptor<LocalDateTime> timeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

			given(fileMetadataRepository.findSoftDeletedFileWithLastIdAndDuration(isNull(), eq(2),
				any(LocalDateTime.class)))
				.willReturn(List.of()); // 바로 종료

			LocalDateTime before = LocalDateTime.now();

			// when
			fileService.doHardDelete();

			// then
			then(fileMetadataRepository).should()
				.findSoftDeletedFileWithLastIdAndDuration(isNull(), eq(2), timeCaptor.capture());

			LocalDateTime after = LocalDateTime.now();
			LocalDateTime passed = timeCaptor.getValue();

			// expected: now - 30days (호출 시점 오차 감안)
			LocalDateTime expectedLower = before.minusDays(CommonConstant.hardDeleteDuration);
			LocalDateTime expectedUpper = after.minusDays(CommonConstant.hardDeleteDuration);

			// passed가 expectedLower~expectedUpper 사이면 OK
			assertFalse(passed.isBefore(expectedLower), "timeLimit should be >= (before - 30days)");
			assertFalse(passed.isAfter(expectedUpper), "timeLimit should be <= (after - 30days)");
		}
	}
}
