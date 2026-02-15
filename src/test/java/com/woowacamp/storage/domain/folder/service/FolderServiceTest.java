package com.woowacamp.storage.domain.folder.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.file.repository.FileMetadataRepository;
import com.woowacamp.storage.domain.folder.dto.type.CursorType;
import com.woowacamp.storage.domain.folder.dto.response.FolderContentsDto;
import com.woowacamp.storage.domain.folder.dto.type.FolderContentsSortField;
import com.woowacamp.storage.domain.folder.dto.command.MovePlan;
import com.woowacamp.storage.domain.folder.dto.request.CreateFolderReqDto;
import com.woowacamp.storage.domain.folder.dto.request.FolderMoveDto;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.event.FolderMoveEvent;
import com.woowacamp.storage.domain.folder.event.FolderSizeEvent;
import com.woowacamp.storage.domain.folder.dto.command.FolderJobInsertCommand;
import com.woowacamp.storage.domain.folder.repository.FolderJobJpaRepository;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataRepository;
import com.woowacamp.storage.domain.folderoperation.service.FolderOperationStateService;
import com.woowacamp.storage.domain.message.event.MessageInfoEvent;
import com.woowacamp.storage.domain.message.repository.MessageInfoJpaRepository;
import com.woowacamp.storage.domain.message.util.MessageStatus;
import com.woowacamp.storage.domain.user.entity.User;
import com.woowacamp.storage.domain.user.repository.UserRepository;
import com.woowacamp.storage.global.background.BackgroundJob;
import com.woowacamp.storage.global.constant.CommonConstant;
import com.woowacamp.storage.global.constant.PermissionType;
import com.woowacamp.storage.global.error.CustomException;
import com.woowacamp.storage.global.error.ErrorCode;
import com.woowacamp.storage.global.util.ValidateParentsUtil;
import com.woowacamp.storage.lock.util.LockKeys;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class FolderServiceTest {

	@InjectMocks
	private FolderService folderService;

	@Mock private FileMetadataJpaRepository fileMetadataJpaRepository;
	@Mock private FileMetadataRepository fileMetadataRepository;
	@Mock private FolderMetadataJpaRepository folderMetadataJpaRepository;
	@Mock private FolderMetadataRepository folderMetadataRepository;
	@Mock private UserRepository userRepository;
	@Mock private Executor searchThreadPoolExecutor;
	@Mock private BackgroundJob backgroundJob;
	@Mock private LockKeys lockKeys;
	@Mock private FolderJobJpaRepository folderJobJpaRepository;
	@Mock private FolderOperationStateService folderOperationStateService;
	@Mock private ValidateParentsUtil validateParentsUtil;
	@Mock private org.springframework.context.ApplicationEventPublisher publisher;
	@Mock private MessageInfoJpaRepository messageInfoJpaRepository;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(folderService, "pageSize", 100);
		ReflectionTestUtils.setField(folderService, "maxPathLength", 250);
		org.mockito.Mockito.lenient()
			.when(folderMetadataJpaRepository.findMaxFolderNamePathLengthByPrefix(anyLong(), anyString()))
			.thenReturn(Optional.empty());
		org.mockito.Mockito.lenient()
			.when(fileMetadataJpaRepository.findMaxFileNamePathLengthByPrefix(anyLong(), anyString()))
			.thenReturn(Optional.empty());
		org.mockito.Mockito.lenient()
			.when(folderOperationStateService.findMaxActiveMoveProjectedNamePathLengthByPrefix(anyLong(), anyString()))
			.thenReturn(Optional.empty());
	}

	// ====== DTO helpers ======
	private FolderMoveDto moveDto(long userId, long targetFolderId, long rootId, String folderName) {
		return new FolderMoveDto(userId, targetFolderId, rootId, folderName);
	}

	private CreateFolderReqDto createReq(long userId, long parentFolderId, String uploadFolderName, long creatorId) {
		return new CreateFolderReqDto(userId, 1L, parentFolderId, uploadFolderName, creatorId);
	}

	// ====== FolderMetadata fixture ======
	private FolderMetadata folder(
		long id,
		Long rootId,
		long ownerId,
		Long parentFolderId,
		String uploadFolderName,
		String nameFullPath,
		String idFullPath,
		int namePathLength,
		long size,
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
			.size(size)
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

	// =========================================================
	// checkFolderOwnedBy
	// =========================================================
	@Nested
	@DisplayName("checkFolderOwnedBy")
	class CheckFolderOwnedByTests {

		@Test
		@DisplayName("성공: findByIdForUpdate 조회 + ownerId 일치면 통과")
		void success_owner_matches() {
			FolderMetadata f = folder(
				10L, 1L, 100L, 1L,
				"a", "/a/", "/10/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			given(folderMetadataJpaRepository.findByIdForUpdate(10L)).willReturn(Optional.of(f));

			assertDoesNotThrow(() -> folderService.checkFolderOwnedBy(10L, 100L));
		}

		@Test
		@DisplayName("실패: 폴더 없음 -> CustomException")
		void fail_not_found() {
			given(folderMetadataJpaRepository.findByIdForUpdate(10L)).willReturn(Optional.empty());
			assertThrows(CustomException.class, () -> folderService.checkFolderOwnedBy(10L, 100L));
		}

		@Test
		@DisplayName("실패: ownerId 불일치 -> CustomException")
		void fail_owner_mismatch() {
			FolderMetadata f = folder(
				10L, 1L, 999L, 1L,
				"a", "/a/", "/10/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			given(folderMetadataJpaRepository.findByIdForUpdate(10L)).willReturn(Optional.of(f));

			assertThrows(CustomException.class, () -> folderService.checkFolderOwnedBy(10L, 100L));
		}
	}

	// =========================================================
	// getFolderContents
	// =========================================================
	@Nested
	@DisplayName("getFolderContents")
	class GetFolderContentsTests {

		@Test
		@DisplayName("cursorType=FILE: 파일만 조회, 폴더 조회는 하지 않는다")
		void file_only_query() {
			FileMetadata f1 = mock(FileMetadata.class);
			given(fileMetadataJpaRepository.selectFilesWithPagination(
				eq(10L), eq(123L), any(), any(), eq(5), any(), any()
			)).willReturn(List.of(f1));

			FolderContentsDto dto = folderService.getFolderContents(
				10L, 123L, CursorType.FILE, 5,
				FolderContentsSortField.CREATED_AT, Sort.Direction.DESC,
				LocalDateTime.now(), null, true
			);

			assertNotNull(dto);
			then(folderMetadataJpaRepository).should(never())
				.selectFoldersWithPagination(anyLong(), anyLong(), any(), any(), anyInt(), any(), any());
		}

		@Test
		@DisplayName("cursorType=FILE + ownerRequested=false: 만료 파일은 필터링된다")
		void file_filter_expired_when_not_owner() {
			FileMetadata alive = mock(FileMetadata.class);
			FileMetadata expired = mock(FileMetadata.class);

			given(alive.isSharingExpired()).willReturn(false);
			given(expired.isSharingExpired()).willReturn(true);

			given(fileMetadataJpaRepository.selectFilesWithPagination(
				eq(10L), eq(1L), any(), any(), eq(10), any(), any()
			)).willReturn(List.of(alive, expired));

			FolderContentsDto dto = folderService.getFolderContents(
				10L, 1L, CursorType.FILE, 10,
				FolderContentsSortField.CREATED_AT, Sort.Direction.DESC,
				LocalDateTime.now(), null, false
			);

			assertEquals(1, dto.fileMetadataList().size());
			assertSame(alive, dto.fileMetadataList().get(0));
		}

		@Test
		@DisplayName("cursorType=FOLDER: 폴더가 limit 미만이면 INITIAL_CURSOR_ID(0)로 파일 추가 조회")
		void folder_then_files_when_not_enough() {
			LocalDateTime now = LocalDateTime.now();
			FolderMetadata oneFolder = folder(
				1L, 1L, 100L, 10L,
				"f", "/f/", "/1/", 3,
				0L, now.plusDays(1)
			);

			given(folderMetadataJpaRepository.selectFoldersWithPagination(
				eq(10L), eq(777L), any(), any(), eq(3), any(), any()
			)).willReturn(List.of(oneFolder)); // 1개만 -> 2개 부족

			FileMetadata f1 = mock(FileMetadata.class);
			FileMetadata f2 = mock(FileMetadata.class);
			given(fileMetadataJpaRepository.selectFilesWithPagination(
				eq(10L), eq(0L), any(), any(), eq(2), any(), any()
			)).willReturn(List.of(f1, f2));

			FolderContentsDto dto = folderService.getFolderContents(
				10L, 777L, CursorType.FOLDER, 3,
				FolderContentsSortField.CREATED_AT, Sort.Direction.DESC,
				LocalDateTime.now(), null, true
			);

			assertEquals(1, dto.folderMetadataList().size());
			assertEquals(2, dto.fileMetadataList().size());
		}

		@Test
		@DisplayName("cursorType=FOLDER + ownerRequested=false: 만료 폴더는 필터링되고 부족분은 파일로 채운다")
		void folder_filter_causes_extra_file_fetch() {
			LocalDateTime now = LocalDateTime.now();
			FolderMetadata alive = folder(
				1L, 1L, 100L, 10L,
				"alive", "/alive/", "/1/", 7,
				0L, now.plusDays(1)
			);
			FolderMetadata expired = folder(
				2L, 1L, 100L, 10L,
				"expired", "/expired/", "/2/", 9,
				0L, now.minusDays(1)
			);

			given(folderMetadataJpaRepository.selectFoldersWithPagination(
				eq(10L), eq(777L), any(), any(), eq(2), any(), any()
			)).willReturn(List.of(alive, expired));

			FileMetadata f1 = mock(FileMetadata.class);
			given(fileMetadataJpaRepository.selectFilesWithPagination(
				eq(10L), eq(0L), any(), any(), eq(1), any(), any()
			)).willReturn(List.of(f1));

			FolderContentsDto dto = folderService.getFolderContents(
				10L, 777L, CursorType.FOLDER, 2,
				FolderContentsSortField.CREATED_AT, Sort.Direction.DESC,
				LocalDateTime.now(), null, false
			);

			assertEquals(1, dto.folderMetadataList().size());
			assertEquals(1, dto.fileMetadataList().size());
		}
	}

	// =========================================================
	// getFolderJobLock
	// =========================================================
	// =========================================================
	// getFolderJobLock
	// =========================================================
	@Nested
	@DisplayName("getFolderJobLock")
	class GetFolderJobLockTests {
		private MovePlan movePlan(long sourceId) {
			return new MovePlan(
				0,
				10,
				"/1/" + sourceId + "/",
				"/p/source/",
				10
			);
		}

		@Test
		@DisplayName("성공: operation state insert + job insert=1이면 통과")
		void success_lock_and_insert() {
			given(folderJobJpaRepository.insert(any(FolderJobInsertCommand.class))).willReturn(1);
			MovePlan plan = movePlan(10L);

			assertDoesNotThrow(() -> folderService.getFolderJobLock(1L, 10L, plan));
		}

		@Test
		@DisplayName("실패: operation state 중복이면 CustomException, job insert는 호출되지 않는다")
		void fail_operation_state_conflict() {
			willThrow(ErrorCode.FOLDER_JOB_CONFLICT.baseException())
				.given(folderOperationStateService)
				.insertActiveMove(anyLong(), anyLong(), anyString(), anyInt(), anyLong());
			MovePlan plan = movePlan(10L);

			assertThrows(CustomException.class, () -> folderService.getFolderJobLock(1L, 10L, plan));
			then(folderJobJpaRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: insert 결과가 1이 아니면 CustomException")
		void fail_insert_not_1() {
			given(folderJobJpaRepository.insert(any(FolderJobInsertCommand.class))).willReturn(0);
			MovePlan plan = movePlan(10L);

			assertThrows(CustomException.class, () -> folderService.getFolderJobLock(1L, 10L, plan));
		}

		@Test
		@DisplayName("실패: insert에서 DataIntegrityViolationException이면 CustomException")
		void fail_insert_duplicate() {
			given(folderJobJpaRepository.insert(any(FolderJobInsertCommand.class)))
				.willThrow(new DataIntegrityViolationException("dup"));
			MovePlan plan = movePlan(10L);

			assertThrows(CustomException.class, () -> folderService.getFolderJobLock(1L, 10L, plan));
		}
	}

	// =========================================================
	// moveFolder
	// =========================================================
	@Nested
	@DisplayName("moveFolder")
	class MoveFolderTests {

		private void stubJobLockSuccess(long sourceId) {
			given(folderJobJpaRepository.insert(any(FolderJobInsertCommand.class))).willReturn(1);
		}

		@Test
		@DisplayName("성공: 검증 통과 -> save + 이벤트 3개 발행 + source 경로/부모 변경")
		void success_save_and_publish_3_events_and_update_source() {
			long sourceId = 10L;
			long targetId = 30L;
			long parentId = 20L;
			long rootId = 1L;
			long userId = 100L;

			stubJobLockSuccess(sourceId);

			FolderMetadata source = folder(
				sourceId, rootId, userId, parentId,
				"src", "/p/src/", "/20/10/", 7,
				123L, CommonConstant.UNAVAILABLE_TIME
			);

			FolderMetadata target = folder(
				targetId, rootId, userId, 5L,
				"t", "/t/", "/5/30/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);

			FolderMetadata parentNotDeleted = folder(
				parentId, rootId, userId, 1L,
				"p", "/p/", "/1/20/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));
			given(folderMetadataJpaRepository.findByIdNotDeleted(parentId)).willReturn(Optional.of(parentNotDeleted));

			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(targetId, "src"))
				.willReturn(false);

			willDoNothing().given(validateParentsUtil).validateParentsFolderLock(parentNotDeleted, target);

			given(folderMetadataJpaRepository.save(any(FolderMetadata.class))).willAnswer(inv -> inv.getArgument(0));

			folderService.moveFolder(sourceId, moveDto(userId, targetId, rootId, "lock-key-only"));

			assertEquals(targetId, source.getParentFolderId());
			assertEquals("/5/30/10/", source.getIdFullPath());
			assertEquals("/t/src/", source.getNameFullPath());
			assertEquals(source.getNameFullPath().length(), source.getNamePathLength());
			assertTrue(source.isMoving());

			ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
			then(publisher).should(times(3)).publishEvent(eventCaptor.capture());

			assertTrue(eventCaptor.getAllValues().get(0) instanceof FolderSizeEvent);
			assertTrue(eventCaptor.getAllValues().get(1) instanceof FolderSizeEvent);
			assertTrue(eventCaptor.getAllValues().get(2) instanceof FolderMoveEvent);
		}

		@Test
		@DisplayName("실패: operation state insert에서 충돌이 나면 CustomException")
		void fail_operation_state_conflict() {
			long sourceId = 10L;
			long targetId = 30L;
			long parentId = 20L;
			long userId = 100L;

			FolderMetadata source = folder(
				sourceId, 1L, userId, parentId,
				"src", "/p/src/", "/20/10/", 7,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata target = folder(
				targetId, 1L, userId, 5L,
				"t", "/t/", "/5/30/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata parent = folder(
				parentId, 1L, userId, 1L,
				"p", "/p/", "/1/20/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));
			given(folderMetadataJpaRepository.findByIdNotDeleted(parentId)).willReturn(Optional.of(parent));
			willThrow(ErrorCode.FOLDER_JOB_CONFLICT.baseException())
				.given(folderOperationStateService)
				.insertActiveMove(anyLong(), anyLong(), anyString(), anyInt(), anyLong());

			FolderMoveDto dto = moveDto(userId, targetId, 1L, "x");
			assertThrows(CustomException.class, () -> folderService.moveFolder(sourceId, dto));

			then(folderJobJpaRepository).shouldHaveNoInteractions();
			then(folderMetadataJpaRepository).should(never()).save(any());
			then(publisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: job insert!=1이면 CustomException")
		void fail_job_insert_not_1() {
			long sourceId = 10L;
			long targetId = 30L;
			long parentId = 20L;
			long userId = 100L;

			FolderMetadata source = folder(
				sourceId, 1L, userId, parentId,
				"src", "/p/src/", "/20/10/", 7,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata target = folder(
				targetId, 1L, userId, 5L,
				"t", "/t/", "/5/30/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata parent = folder(
				parentId, 1L, userId, 1L,
				"p", "/p/", "/1/20/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);

				given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
				given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));
				given(folderMetadataJpaRepository.findByIdNotDeleted(parentId)).willReturn(Optional.of(parent));
				given(folderJobJpaRepository.insert(any(FolderJobInsertCommand.class))).willReturn(0);

			FolderMoveDto dto = moveDto(userId, targetId, 1L, "x");
			assertThrows(CustomException.class, () -> folderService.moveFolder(10L, dto));
		}

		@Test
		@DisplayName("실패: job insert에서 DataIntegrityViolationException이면 CustomException")
		void fail_job_insert_duplicate() {
			long sourceId = 10L;
			long targetId = 30L;
			long parentId = 20L;
			long userId = 100L;

			FolderMetadata source = folder(
				sourceId, 1L, userId, parentId,
				"src", "/p/src/", "/20/10/", 7,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata target = folder(
				targetId, 1L, userId, 5L,
				"t", "/t/", "/5/30/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata parent = folder(
				parentId, 1L, userId, 1L,
				"p", "/p/", "/1/20/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);

				given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
				given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));
				given(folderMetadataJpaRepository.findByIdNotDeleted(parentId)).willReturn(Optional.of(parent));
				given(folderJobJpaRepository.insert(any(FolderJobInsertCommand.class)))
					.willThrow(new DataIntegrityViolationException("dup"));

			FolderMoveDto dto = moveDto(userId, targetId, 1L, "x");
			assertThrows(CustomException.class, () -> folderService.moveFolder(10L, dto));
		}

		@Test
		@DisplayName("실패: source 폴더 없음 -> CustomException")
		void fail_source_not_found() {
			given(folderMetadataJpaRepository.findByIdNotDeleted(10L)).willReturn(Optional.empty());

			FolderMoveDto dto = moveDto(100L, 30L, 1L, "x");
			assertThrows(CustomException.class, () -> folderService.moveFolder(10L, dto));
			then(folderMetadataJpaRepository).should(never()).findByIdNotDeleted(30L);
		}

		@Test
		@DisplayName("실패: source owner 불일치 -> CustomException, target 조회 전 종료")
		void fail_source_owner_mismatch() {
			long sourceId = 10L;

			FolderMetadata source = folder(
				sourceId, 1L, 999L, 20L,
				"src", "/p/src/", "/20/10/", 7,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));

			FolderMoveDto dto = moveDto(100L, 30L, 1L, "x");
			assertThrows(CustomException.class, () -> folderService.moveFolder(sourceId, dto));
			then(folderMetadataJpaRepository).should(never()).findByIdNotDeleted(30L);
		}

		@Test
		@DisplayName("실패: target 폴더 없음 -> CustomException")
		void fail_target_not_found() {
			long sourceId = 10L;
			long targetId = 30L;

			FolderMetadata source = folder(
				sourceId, 1L, 100L, 20L,
				"src", "/p/src/", "/20/10/", 7,
				0L, CommonConstant.UNAVAILABLE_TIME
			);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.empty());

			FolderMoveDto dto = moveDto(100L, targetId, 1L, "x");
			assertThrows(CustomException.class, () -> folderService.moveFolder(sourceId, dto));
			then(folderMetadataJpaRepository).should(never()).findByIdNotDeleted(20L);
		}

		@Test
		@DisplayName("실패: target owner 불일치 -> CustomException")
		void fail_target_owner_mismatch() {
			long sourceId = 10L;
			long targetId = 30L;

			FolderMetadata source = folder(
				sourceId, 1L, 100L, 20L,
				"src", "/p/src/", "/20/10/", 7,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata target = folder(
				targetId, 1L, 999L, 5L,
				"t", "/t/", "/5/30/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));

			FolderMoveDto dto = moveDto(100L, targetId, 1L, "x");
			assertThrows(CustomException.class, () -> folderService.moveFolder(sourceId, dto));
			then(folderMetadataJpaRepository).should(never()).findByIdNotDeleted(20L);
		}

		@Test
		@DisplayName("실패: rootId mismatch(source/target root 다름) -> CustomException")
		void fail_root_mismatch_between_source_and_target() {
			long sourceId = 10L;
			long targetId = 30L;
			long parentId = 20L;
			long userId = 100L;

			FolderMetadata source = folder(
				sourceId, 999L, userId, parentId,
				"src", "/p/src/", "/20/10/", 7,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata target = folder(
				targetId, 1L, userId, 5L,
				"t", "/t/", "/5/30/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata parent = folder(
				parentId, 999L, userId, 1L,
				"p", "/p/", "/1/20/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));
			given(folderMetadataJpaRepository.findByIdNotDeleted(parentId)).willReturn(Optional.of(parent));

			FolderMoveDto dto = moveDto(userId, targetId, 1L, "x");
			assertThrows(CustomException.class, () -> folderService.moveFolder(sourceId, dto));

			then(folderMetadataJpaRepository).should(never())
				.existsByParentFolderIdAndUploadFolderName(anyLong(), anyString());
			then(folderMetadataJpaRepository).should(never()).save(any());
			then(publisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: 자기 자신으로 이동(source==target) -> CustomException")
		void fail_move_to_self() {
			long sourceId = 10L;
			long parentId = 20L;
			long rootId = 1L;
			long userId = 100L;

			FolderMetadata same = folder(
				sourceId, rootId, userId, parentId,
				"src", "/p/src/", "/20/10/", 7,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata parent = folder(
				parentId, rootId, userId, 1L,
				"p", "/p/", "/1/20/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(same));
			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(same)); // target 조회도 같은 id
			given(folderMetadataJpaRepository.findByIdNotDeleted(parentId)).willReturn(Optional.of(parent));

			FolderMoveDto dto = moveDto(userId, sourceId, rootId, "x");
			assertThrows(CustomException.class, () -> folderService.moveFolder(sourceId, dto));

			then(folderMetadataJpaRepository).should(never()).save(any());
			then(validateParentsUtil).shouldHaveNoInteractions();
			then(publisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: target이 source 하위(prefix startsWith)면 -> CustomException")
		void fail_cycle_descendant() {
			long sourceId = 10L;
			long targetId = 30L;
			long parentId = 20L;
			long rootId = 1L;
			long userId = 100L;

			FolderMetadata source = folder(
				sourceId, rootId, userId, parentId,
				"src", "/p/src/", "/20/10/", 7,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata target = folder(
				targetId, rootId, userId, 5L,
				"t", "/t/", "/20/10/30/", 9, // ✅ descendant
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata parent = folder(
				parentId, rootId, userId, 1L,
				"p", "/p/", "/1/20/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));
			given(folderMetadataJpaRepository.findByIdNotDeleted(parentId)).willReturn(Optional.of(parent));

			FolderMoveDto dto = moveDto(userId, targetId, rootId, "x");
			assertThrows(CustomException.class, () -> folderService.moveFolder(sourceId, dto));

			then(folderMetadataJpaRepository).should(never())
				.existsByParentFolderIdAndUploadFolderName(anyLong(), anyString());
			then(folderMetadataJpaRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("실패: target에 동일 이름 폴더 존재 -> CustomException")
		void fail_duplicated_name_in_target() {
			long sourceId = 10L;
			long targetId = 30L;
			long parentId = 20L;
			long rootId = 1L;
			long userId = 100L;

			FolderMetadata source = folder(
				sourceId, rootId, userId, parentId,
				"dup", "/p/dup/", "/20/10/", 7,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata target = folder(
				targetId, rootId, userId, 5L,
				"t", "/t/", "/5/30/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata parent = folder(
				parentId, rootId, userId, 1L,
				"p", "/p/", "/1/20/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));
			given(folderMetadataJpaRepository.findByIdNotDeleted(parentId)).willReturn(Optional.of(parent));

			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(targetId, "dup"))
				.willReturn(true);

			FolderMoveDto dto = moveDto(userId, targetId, rootId, "dtoNameIrrelevant");
			assertThrows(CustomException.class, () -> folderService.moveFolder(sourceId, dto));

			then(folderMetadataJpaRepository).should(never()).save(any());
			then(validateParentsUtil).shouldHaveNoInteractions();
			then(publisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: path too long이면 CustomException, save/publish 없이 종료")
		void fail_path_too_long() {
			ReflectionTestUtils.setField(folderService, "maxPathLength", 5);

			long sourceId = 10L;
			long targetId = 30L;
			long parentId = 20L;
			long rootId = 1L;
			long userId = 100L;

			FolderMetadata source = folder(
				sourceId, rootId, userId, parentId,
				"src", "/very/long/source/", "/20/10/", 18,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata target = folder(
				targetId, rootId, userId, 5L,
				"t", "/t/", "/5/30/", 4,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata parentNotDeleted = folder(
				parentId, rootId, userId, 1L,
				"p", "/p/", "/1/20/", 1,
				0L, CommonConstant.UNAVAILABLE_TIME
			);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));
			given(folderMetadataJpaRepository.findByIdNotDeleted(parentId)).willReturn(Optional.of(parentNotDeleted));

			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(targetId, "src"))
				.willReturn(false);

			FolderMoveDto dto = moveDto(userId, targetId, rootId, "x");
			assertThrows(CustomException.class, () -> folderService.moveFolder(sourceId, dto));

			then(validateParentsUtil).shouldHaveNoInteractions();
			then(folderMetadataJpaRepository).should(never()).save(any());
			then(publisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: validateParentsUtil에서 CustomException 던지면 save/publish 없이 종료")
		void fail_validate_parents_util_throws() {
			long sourceId = 10L;
			long targetId = 30L;
			long parentId = 20L;
			long rootId = 1L;
			long userId = 100L;

			stubJobLockSuccess(sourceId);

			FolderMetadata source = folder(
				sourceId, rootId, userId, parentId,
				"src", "/p/src/", "/20/10/", 7,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata target = folder(
				targetId, rootId, userId, 5L,
				"t", "/t/", "/5/30/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata parentNotDeleted = folder(
				parentId, rootId, userId, 1L,
				"p", "/p/", "/1/20/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));
			given(folderMetadataJpaRepository.findByIdNotDeleted(parentId)).willReturn(Optional.of(parentNotDeleted));

			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(targetId, "src"))
				.willReturn(false);

			willThrow(ErrorCode.PARENT_LOCKED.baseException())
				.given(validateParentsUtil).validateParentsFolderLock(parentNotDeleted, target);

			FolderMoveDto dto = moveDto(userId, targetId, rootId, "x");
			assertThrows(CustomException.class, () -> folderService.moveFolder(sourceId, dto));

			then(folderMetadataJpaRepository).should(never()).save(any());
			then(publisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: save에서 예외면 이벤트는 발행되지 않는다")
		void fail_save_throws_no_publish() {
			long sourceId = 10L;
			long targetId = 30L;
			long parentId = 20L;
			long rootId = 1L;
			long userId = 100L;

			stubJobLockSuccess(sourceId);

			FolderMetadata source = folder(
				sourceId, rootId, userId, parentId,
				"src", "/p/src/", "/20/10/", 7,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata target = folder(
				targetId, rootId, userId, 5L,
				"t", "/t/", "/5/30/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata parentNotDeleted = folder(
				parentId, rootId, userId, 1L,
				"p", "/p/", "/1/20/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));
			given(folderMetadataJpaRepository.findByIdNotDeleted(parentId)).willReturn(Optional.of(parentNotDeleted));

			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(targetId, "src"))
				.willReturn(false);

			willDoNothing().given(validateParentsUtil).validateParentsFolderLock(parentNotDeleted, target);
			willThrow(new RuntimeException("db")).given(folderMetadataJpaRepository).save(any(FolderMetadata.class));

			FolderMoveDto dto = moveDto(userId, targetId, rootId, "x");
			assertThrows(RuntimeException.class, () -> folderService.moveFolder(sourceId, dto));

			then(publisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("호출 순서: source/target/parent 조회 이후 getFolderJobLock이 수행된다")
		void order_fetch_before_joblock() {
			long sourceId = 10L;
			long targetId = 30L;
			long parentId = 20L;
			long userId = 100L;

				given(folderJobJpaRepository.insert(any(FolderJobInsertCommand.class))).willReturn(1);

			FolderMetadata source = folder(
				sourceId, 1L, userId, parentId,
				"src", "/p/src/", "/20/10/", 7,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata target = folder(
				targetId, 1L, userId, 5L,
				"t", "/t/", "/5/30/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			FolderMetadata parent = folder(
				parentId, 999L, userId, 1L,
				"p", "/p/", "/1/20/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));
			given(folderMetadataJpaRepository.findByIdNotDeleted(parentId)).willReturn(Optional.of(parent));
			given(folderMetadataJpaRepository.save(any(FolderMetadata.class))).willAnswer(inv -> inv.getArgument(0));

			FolderMoveDto dto = moveDto(userId, targetId, 1L, "x");
			assertDoesNotThrow(() -> folderService.moveFolder(sourceId, dto));

			InOrder inOrder = inOrder(folderMetadataJpaRepository, folderOperationStateService, folderJobJpaRepository);
			inOrder.verify(folderMetadataJpaRepository).findByIdNotDeleted(sourceId);
			inOrder.verify(folderMetadataJpaRepository).findByIdNotDeleted(targetId);
			inOrder.verify(folderMetadataJpaRepository).findByIdNotDeleted(parentId);
			inOrder.verify(folderOperationStateService).insertActiveMove(
				anyLong(), eq(sourceId), anyString(), anyInt(), eq(sourceId));
				inOrder.verify(folderJobJpaRepository).insert(any(FolderJobInsertCommand.class));
			}
		}

	// =========================================================
	// createFolder
	// =========================================================
	@Nested
	@DisplayName("createFolder")
	class CreateFolderTests {

		@Test
		@DisplayName("성공: save 2번 호출 + idFullPath는 /parentPath/{id}/ 형태로 최종 업데이트")
		void success_save_twice_and_update_id_full_path() {
			long userId = 100L;
			long rootId = 1L;
			long parentId = 20L;

			CreateFolderReqDto req = createReq(userId, parentId, "new", userId);

			User user = mock(User.class);
			given(userRepository.findById(userId)).willReturn(Optional.of(user));
			given(user.getId()).willReturn(userId);
			given(user.getRootFolderId()).willReturn(rootId);

			FolderMetadata parent = folder(
				parentId, rootId, userId, 1L,
				"parent", "/p/", "/20/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			given(folderMetadataJpaRepository.findById(parentId)).willReturn(Optional.of(parent));

			// duplicate false
			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(parentId, "new"))
				.willReturn(false);

			// save: 1st -> id 부여된 새 엔티티 리턴, 2nd -> 그대로 리턴
			given(folderMetadataJpaRepository.save(any(FolderMetadata.class))).willAnswer(inv -> {
				FolderMetadata arg = inv.getArgument(0);
				// 첫 save는 id가 없을 것. JPA처럼 id가 생긴 managed 객체를 "리턴용"으로 새로 만든다.
				if (arg.getId() == null) {
					LocalDateTime now = LocalDateTime.now();
					return FolderMetadata.builder()
						.id(999L)
						.rootId(arg.getRootId())
						.ownerId(arg.getOwnerId())
						.creatorId(arg.getCreatorId())
						.createdAt(now)
						.updatedAt(now)
						.parentFolderId(arg.getParentFolderId())
						.uploadFolderName(arg.getUploadFolderName())
						.size(arg.getSize())
						.sharingExpiredAt(arg.getSharingExpiredAt())
						.permissionType(arg.getPermissionType())
						.isDeleted(arg.isDeleted())
						.version(arg.getVersion())
						.nameFullPath(arg.getNameFullPath())
						.idFullPath(arg.getIdFullPath())
						.namePathLength(arg.getNamePathLength())
						.isMoving(arg.isMoving())
						.build();
				}
				return arg;
			});

			Long id = folderService.createFolder(req);
			assertEquals(999L, id);
			then(validateParentsUtil).should()
				.validateMaxNamePathLengthAndReserveForCreate(parent, "new".length(), 250);

			ArgumentCaptor<FolderMetadata> saveCaptor = ArgumentCaptor.forClass(FolderMetadata.class);
			then(folderMetadataJpaRepository).should(times(2)).save(saveCaptor.capture());

			FolderMetadata secondArg = saveCaptor.getAllValues().get(1); // id 부여된 객체

			// 두번째 save 대상은 id가 존재하고, updateIdFullPath가 적용되어야 함
			assertNotNull(secondArg.getId());
			assertEquals("/20/999/", secondArg.getIdFullPath());
		}

		@Test
		@DisplayName("실패: user 없으면 CustomException, parent 조회/저장 없음")
		void fail_user_not_found() {
			CreateFolderReqDto req = createReq(100L, 20L, "x", 100L);
			given(userRepository.findById(100L)).willReturn(Optional.empty());

			assertThrows(CustomException.class, () -> folderService.createFolder(req));
			then(folderMetadataJpaRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: parent 없으면 CustomException, 저장 없음")
		void fail_parent_not_found() {
			long userId = 100L;
			long parentId = 20L;

			CreateFolderReqDto req = createReq(userId, parentId, "x", userId);

			given(userRepository.findById(userId)).willReturn(Optional.of(mock(User.class)));
			given(folderMetadataJpaRepository.findById(parentId)).willReturn(Optional.empty());

			assertThrows(CustomException.class, () -> folderService.createFolder(req));
			then(folderMetadataJpaRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("실패: 금칙어 포함(CustomException) - duplicate/save 호출 안 됨")
		void fail_blacklist() {
			long userId = 100L;
			long parentId = 20L;
			char bad = CommonConstant.FILE_NAME_BLACK_LIST[0];

			CreateFolderReqDto req = createReq(userId, parentId, "ab" + bad + "cd", userId);

			given(userRepository.findById(userId)).willReturn(Optional.of(mock(User.class)));
			given(folderMetadataJpaRepository.findById(parentId)).willReturn(Optional.of(
				folder(parentId, 1L, userId, 1L, "p", "/p/", "/20/", 3, 0L, CommonConstant.UNAVAILABLE_TIME)
			));

			assertThrows(CustomException.class, () -> folderService.createFolder(req));

			then(folderMetadataJpaRepository).should(never())
				.existsByParentFolderIdAndUploadFolderName(anyLong(), anyString());
			then(folderMetadataJpaRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("실패: 같은 depth 동일 이름(CustomException)")
		void fail_duplicate_name() {
			long userId = 100L;
			long parentId = 20L;

			CreateFolderReqDto req = createReq(userId, parentId, "dup", userId);

			User user = mock(User.class);
			given(userRepository.findById(userId)).willReturn(Optional.of(user));

			FolderMetadata parent = folder(parentId, 1L, userId, 1L, "p", "/p/", "/20/", 3, 0L, CommonConstant.UNAVAILABLE_TIME);
			given(folderMetadataJpaRepository.findById(parentId)).willReturn(Optional.of(parent));

			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(parentId, "dup"))
				.willReturn(true);

			assertThrows(CustomException.class, () -> folderService.createFolder(req));
			then(folderMetadataJpaRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("실패: parent owner 불일치(CustomException)")
		void fail_parent_owner_mismatch() {
			long userId = 100L;
			long parentId = 20L;

			CreateFolderReqDto req = createReq(userId, parentId, "ok", userId);

			User user = mock(User.class);
			given(userRepository.findById(userId)).willReturn(Optional.of(user));

			FolderMetadata parent = folder(parentId, 1L, 999L, 1L, "p", "/p/", "/20/", 3, 0L, CommonConstant.UNAVAILABLE_TIME);
			given(folderMetadataJpaRepository.findById(parentId)).willReturn(Optional.of(parent));

			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(parentId, "ok"))
				.willReturn(false);

			assertThrows(CustomException.class, () -> folderService.createFolder(req));
			then(folderMetadataJpaRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("실패: 경로 길이 초과(CustomException)")
		void fail_path_too_long() {
			ReflectionTestUtils.setField(folderService, "maxPathLength", 10);

			long userId = 100L;
			long parentId = 20L;

			CreateFolderReqDto req = createReq(userId, parentId, "veryLongName", userId);

			User user = mock(User.class);
			given(userRepository.findById(userId)).willReturn(Optional.of(user));

			// parent namePathLength를 크게 만들어 바로 초과
			FolderMetadata parent = folder(parentId, 1L, userId, 1L, "p", "/p/", "/20/", 9, 0L, CommonConstant.UNAVAILABLE_TIME);
			given(folderMetadataJpaRepository.findById(parentId)).willReturn(Optional.of(parent));

			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(parentId, "veryLongName"))
				.willReturn(false);

			assertThrows(CustomException.class, () -> folderService.createFolder(req));
			then(folderMetadataJpaRepository).should(never()).save(any());
			then(validateParentsUtil).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: moving 예약 길이 검증에서 예외가 발생하면 저장하지 않는다")
		void fail_when_reservation_validation_throws() {
			long userId = 100L;
			long parentId = 20L;

			CreateFolderReqDto req = createReq(userId, parentId, "ok", userId);

			User user = mock(User.class);
			given(userRepository.findById(userId)).willReturn(Optional.of(user));

			FolderMetadata parent = folder(parentId, 1L, userId, 1L, "p", "/p/", "/20/", 3, 0L,
				CommonConstant.UNAVAILABLE_TIME);
			given(folderMetadataJpaRepository.findById(parentId)).willReturn(Optional.of(parent));
			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(parentId, "ok"))
				.willReturn(false);
			willThrow(ErrorCode.EXCEED_MAX_PATH_LENGTH.baseException())
				.given(validateParentsUtil)
				.validateMaxNamePathLengthAndReserveForCreate(parent, "ok".length(), 250);

			assertThrows(CustomException.class, () -> folderService.createFolder(req));

			then(folderMetadataJpaRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("실패: 첫 번째 save에서 예외면 updateIdFullPath/두번째 save는 호출되지 않는다")
		void fail_first_save_throws() {
			long userId = 100L;
			long parentId = 20L;

			CreateFolderReqDto req = createReq(userId, parentId, "ok", userId);

			User user = mock(User.class);
			given(userRepository.findById(userId)).willReturn(Optional.of(user));
			given(user.getId()).willReturn(userId);
			given(user.getRootFolderId()).willReturn(1L);

			FolderMetadata parent = folder(parentId, 1L, userId, 1L, "p", "/p/", "/20/", 3, 0L, CommonConstant.UNAVAILABLE_TIME);
			given(folderMetadataJpaRepository.findById(parentId)).willReturn(Optional.of(parent));
			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(parentId, "ok"))
				.willReturn(false);

			willThrow(new RuntimeException("db")).given(folderMetadataJpaRepository).save(any(FolderMetadata.class));

			assertThrows(RuntimeException.class, () -> folderService.createFolder(req));
			then(folderMetadataJpaRepository).should(times(1)).save(any(FolderMetadata.class));
		}
	}

	// =========================================================
	// updateFolderSize
	// =========================================================
	@Nested
	@DisplayName("updateFolderSize")
	class UpdateFolderSizeTests {

		@Test
		@DisplayName("실패: folder 없으면 CustomException")
		void fail_folder_not_found() {
			given(folderMetadataJpaRepository.findById(10L)).willReturn(Optional.empty());
			assertThrows(CustomException.class, () -> folderService.updateFolderSize(1L, 10L, 100L));
		}

		@Test
		@DisplayName("성공: pending 메시지 없으면 1 반환(업데이트/이벤트 없음)")
		void no_pending_return_1_no_events() {
			FolderMetadata f = folder(
				10L, 1L, 100L, 20L,
				"a", "/a/", "/10/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);
			given(folderMetadataJpaRepository.findById(10L)).willReturn(Optional.of(f));
			// ✅ 수정: SENT, PENDING 둘 다 체크
			given(messageInfoJpaRepository.existsByIdAndStatusIn(1L, List.of(MessageStatus.SENT, MessageStatus.PENDING))).willReturn(false);

			int result = folderService.updateFolderSize(1L, 10L, 100L);

			assertEquals(1, result);
			then(folderMetadataJpaRepository).should(never())
				.updateFolderSizeWithVersion(anyLong(), anyLong(), anyLong());
			then(publisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("성공: update 결과 0이면 0 반환(완료 이벤트/상위 전파 없음)")
		void update_conflict_returns_0_no_events() {
			FolderMetadata f = folder(
				10L, 1L, 100L, 20L,
				"a", "/a/", "/10/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);

			given(folderMetadataJpaRepository.findById(10L)).willReturn(Optional.of(f));
			// ✅ 수정: SENT, PENDING 둘 다 체크
			given(messageInfoJpaRepository.existsByIdAndStatusIn(1L, List.of(MessageStatus.SENT, MessageStatus.PENDING))).willReturn(true);
			given(folderMetadataJpaRepository.updateFolderSizeWithVersion(100L, 10L, 0L)).willReturn(0);

			int result = folderService.updateFolderSize(1L, 10L, 100L);

			assertEquals(0, result);
			then(publisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("성공: update 성공 + parent=null이면 MessageInfoEvent만 발행하고 1 반환")
		void update_success_parent_null_publish_message_only() {
			FolderMetadata f = folder(
				10L, 1L, 100L, null,
				"a", "/a/", "/10/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);

			given(folderMetadataJpaRepository.findById(10L)).willReturn(Optional.of(f));
			// ✅ 수정: SENT, PENDING 둘 다 체크
			given(messageInfoJpaRepository.existsByIdAndStatusIn(1L, List.of(MessageStatus.SENT, MessageStatus.PENDING))).willReturn(true);
			given(folderMetadataJpaRepository.updateFolderSizeWithVersion(100L, 10L, 0L)).willReturn(1);

			int result = folderService.updateFolderSize(1L, 10L, 100L);

			assertEquals(1, result);

			ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
			then(publisher).should(times(1)).publishEvent(captor.capture());
			assertTrue(captor.getValue() instanceof MessageInfoEvent);
		}

		@Test
		@DisplayName("성공: update 성공 + parent!=null이면 MessageInfoEvent + FolderSizeEvent 발행하고 1 반환")
		void update_success_publish_two_events() {
			FolderMetadata f = folder(
				10L, 1L, 100L, 20L,
				"a", "/a/", "/10/", 3,
				0L, CommonConstant.UNAVAILABLE_TIME
			);

			given(folderMetadataJpaRepository.findById(10L)).willReturn(Optional.of(f));
			// ✅ 수정: SENT, PENDING 둘 다 체크
			given(messageInfoJpaRepository.existsByIdAndStatusIn(1L, List.of(MessageStatus.SENT, MessageStatus.PENDING))).willReturn(true);
			given(folderMetadataJpaRepository.updateFolderSizeWithVersion(100L, 10L, 0L)).willReturn(1);

			int result = folderService.updateFolderSize(1L, 10L, 100L);

			assertEquals(1, result);

			ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
			then(publisher).should(times(2)).publishEvent(captor.capture());

			assertTrue(captor.getAllValues().get(0) instanceof MessageInfoEvent);
			assertTrue(captor.getAllValues().get(1) instanceof FolderSizeEvent);
		}
	}
}
