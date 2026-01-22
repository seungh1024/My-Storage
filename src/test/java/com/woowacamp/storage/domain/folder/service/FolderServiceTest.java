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
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.file.repository.FileMetadataRepository;
import com.woowacamp.storage.domain.folder.dto.CursorType;
import com.woowacamp.storage.domain.folder.dto.FolderContentsDto;
import com.woowacamp.storage.domain.folder.dto.FolderContentsSortField;
import com.woowacamp.storage.domain.folder.dto.request.CreateFolderReqDto;
import com.woowacamp.storage.domain.folder.dto.request.FolderMoveDto;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.entity.FolderMetadataFactory;
import com.woowacamp.storage.domain.folder.event.FolderMoveEvent;
import com.woowacamp.storage.domain.folder.event.FolderSizeEvent;
import com.woowacamp.storage.domain.folder.repository.FolderJobJpaRepository;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataRepository;
import com.woowacamp.storage.domain.folder.utils.FolderJobStatus;
import com.woowacamp.storage.domain.message.event.MessageInfoEvent;
import com.woowacamp.storage.domain.message.repository.MessageInfoJpaRepository;
import com.woowacamp.storage.domain.message.util.MessageStatus;
import com.woowacamp.storage.domain.user.entity.User;
import com.woowacamp.storage.domain.user.repository.UserRepository;
import com.woowacamp.storage.global.background.BackgroundJob;
import com.woowacamp.storage.global.constant.CommonConstant;
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
	@Mock
	private FileMetadataJpaRepository fileMetadataJpaRepository;
	@Mock
	private FileMetadataRepository fileMetadataRepository;
	@Mock
	private FolderMetadataJpaRepository folderMetadataJpaRepository;
	@Mock
	private FolderMetadataRepository folderMetadataRepository;
	@Mock
	private UserRepository userRepository;
	@Mock
	private Executor searchThreadPoolExecutor;
	@Mock
	private BackgroundJob backgroundJob;
	@Mock
	private LockKeys lockKeys;
	@Mock
	private FolderJobJpaRepository folderJobJpaRepository;
	@Mock
	private ValidateParentsUtil validateParentsUtil;

	@Mock
	private org.springframework.context.ApplicationEventPublisher publisher;
	@Mock
	private MessageInfoJpaRepository messageInfoJpaRepository;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(folderService, "pageSize", 100);
		ReflectionTestUtils.setField(folderService, "retryCnt", 3);
		ReflectionTestUtils.setField(folderService, "maxPathLength", 250);
	}

	// ====== DTO helpers (record는 mock 금지) ======
	private FolderMoveDto moveDto(long userId, long targetFolderId, long rootId, String folderName) {
		return new FolderMoveDto(userId, targetFolderId, rootId, folderName);
	}

	private CreateFolderReqDto createReq(long userId, long parentFolderId, String uploadFolderName, long creatorId) {
		return new CreateFolderReqDto(userId, parentFolderId, uploadFolderName, creatorId);
	}

	// ====== FolderContentsDto 접근 (구현체 몰라도 필드로 확인) ======
	@SuppressWarnings("unchecked")
	private static <T> List<T> readListField(Object dto, String fieldName) {
		Object v = ReflectionTestUtils.getField(dto, fieldName);
		assertNotNull(v, "field '" + fieldName + "' should exist");
		return (List<T>)v;
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
			FolderMetadata folder = mock(FolderMetadata.class);
			given(folder.getOwnerId()).willReturn(100L);
			given(folderMetadataJpaRepository.findByIdForUpdate(10L)).willReturn(Optional.of(folder));

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
			FolderMetadata folder = mock(FolderMetadata.class);
			given(folder.getOwnerId()).willReturn(999L);
			given(folderMetadataJpaRepository.findByIdForUpdate(10L)).willReturn(Optional.of(folder));

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

			// ✅ Reflection 금지: 실제 컴포넌트 접근
			List<FileMetadata> files = dto.fileMetadataList();

			assertEquals(1, files.size());
			assertSame(alive, files.get(0));

			// FILE이면 폴더 조회가 없어야 함
			then(folderMetadataJpaRepository).should(never())
				.selectFoldersWithPagination(anyLong(), anyLong(), any(), any(), anyInt(), any(), any());
		}

		@Test
		@DisplayName("cursorType=FOLDER: 폴더가 limit 미만이면 INITIAL_CURSOR_ID(0)로 파일 추가 조회")
		void folder_then_files_when_not_enough() {
			FolderMetadata folder = mock(FolderMetadata.class);

			given(folderMetadataJpaRepository.selectFoldersWithPagination(
				eq(10L), eq(777L), any(), any(), eq(3), any(), any()
			)).willReturn(List.of(folder)); // 1개만 -> 2개 부족

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

			assertNotNull(dto);

			// ✅ 결과도 컴포넌트로 검증
			assertEquals(1, dto.folderMetadataList().size());
			assertEquals(2, dto.fileMetadataList().size());

			then(fileMetadataJpaRepository).should()
				.selectFilesWithPagination(eq(10L), eq(0L), any(), any(), eq(2), any(), any());
		}

		@Test
		@DisplayName("cursorType=FOLDER + ownerRequested=false: 필터링 후 폴더가 limit 미만이면 부족분을 파일로 채운다")
		void folder_filter_causes_extra_file_fetch() {
			FolderMetadata alive = mock(FolderMetadata.class);
			FolderMetadata expired = mock(FolderMetadata.class);
			given(alive.isSharingExpired()).willReturn(false);
			given(expired.isSharingExpired()).willReturn(true);

			// limit=2, repo는 2개 주지만 expired가 걸러져 1개만 남음 -> 파일 1개 추가조회 발생
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

			assertEquals(1, dto.folderMetadataList().size()); // alive만
			assertEquals(1, dto.fileMetadataList().size());   // 부족분 채움
			then(fileMetadataJpaRepository).should()
				.selectFilesWithPagination(eq(10L), eq(0L), any(), any(), eq(1), any(), any());
		}
	}

	// =========================================================
	// getFolderJobLock
	// =========================================================
	@Nested
	@DisplayName("getFolderJobLock")
	class GetFolderJobLockTests {

		@Test
		@DisplayName("성공: moving lock=1 + insert=1이면 통과")
		void success_lock_and_insert() {
			given(folderMetadataJpaRepository.getMovingLock(10L)).willReturn(1);
			given(folderJobJpaRepository.insert(eq(10L), eq(10L), eq(0L), eq(FolderJobStatus.WAITING.name())))
				.willReturn(1);

			assertDoesNotThrow(() -> folderService.getFolderJobLock(10L));
		}

		@Test
		@DisplayName("실패: moving lock=0이면 CustomException, insert는 호출되지 않는다")
		void fail_lock_conflict() {
			given(folderMetadataJpaRepository.getMovingLock(10L)).willReturn(0);

			assertThrows(CustomException.class, () -> folderService.getFolderJobLock(10L));
			then(folderJobJpaRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: insert 결과가 1이 아니면 CustomException")
		void fail_insert_not_1() {
			given(folderMetadataJpaRepository.getMovingLock(10L)).willReturn(1);
			given(folderJobJpaRepository.insert(eq(10L), eq(10L), eq(0L), eq(FolderJobStatus.WAITING.name())))
				.willReturn(0);

			assertThrows(CustomException.class, () -> folderService.getFolderJobLock(10L));
		}

		@Test
		@DisplayName("실패: insert에서 DataIntegrityViolationException이면 CustomException")
		void fail_insert_duplicate() {
			given(folderMetadataJpaRepository.getMovingLock(10L)).willReturn(1);
			given(folderJobJpaRepository.insert(eq(10L), eq(10L), eq(0L), eq(FolderJobStatus.WAITING.name())))
				.willThrow(new DataIntegrityViolationException("dup"));

			assertThrows(CustomException.class, () -> folderService.getFolderJobLock(10L));
		}

		@Test
		@DisplayName("실패: insert에서 기타 예외면 CustomException")
		void fail_insert_other_exception() {
			given(folderMetadataJpaRepository.getMovingLock(10L)).willReturn(1);
			given(folderJobJpaRepository.insert(eq(10L), eq(10L), eq(0L), eq(FolderJobStatus.WAITING.name())))
				.willThrow(new RuntimeException("db"));

			assertThrows(CustomException.class, () -> folderService.getFolderJobLock(10L));
		}
	}

	// =========================================================
	// moveFolder
	// =========================================================
	@Nested
	@DisplayName("moveFolder")
	class MoveFolderTests {

		private void stubJobLockSuccess(long sourceId) {
			given(folderMetadataJpaRepository.getMovingLock(sourceId)).willReturn(1);
			given(folderJobJpaRepository.insert(eq(sourceId), eq(sourceId), eq(0L), eq(FolderJobStatus.WAITING.name())))
				.willReturn(1);
		}

		@Test
		@DisplayName("성공: job lock + 검증 통과 + save + 이벤트 3개 발행")
		void success_save_and_publish_3_events() {
			long sourceId = 10L;
			long targetId = 30L;
			long rootId = 1L;
			long userId = 100L;

			stubJobLockSuccess(sourceId);

			FolderMetadata source = mock(FolderMetadata.class);
			FolderMetadata target = mock(FolderMetadata.class);

			// validateFolderOwner
			given(source.getOwnerId()).willReturn(userId);
			given(target.getOwnerId()).willReturn(userId);

			// validateInvalidMove
			given(source.getRootId()).willReturn(rootId);
			given(target.getRootId()).willReturn(rootId);
			given(source.getId()).willReturn(sourceId);
			given(target.getId()).willReturn(targetId);
			given(source.getParentFolderId()).willReturn(20L);
			given(source.getIdFullPath()).willReturn("/20/10/");
			given(target.getIdFullPath()).willReturn("/5/30/");
			given(source.getUploadFolderName()).willReturn("source-name");
			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(targetId, "source-name"))
				.willReturn(false);

			// validatePathLength
			given(source.getNameFullPath()).willReturn("/p/source/");
			given(source.getNamePathLength()).willReturn(10);
			given(target.getNameFullPath()).willReturn("/t/");
			given(target.getNamePathLength()).willReturn(3);

			FolderMetadata parent = mock(FolderMetadata.class);
			given(parent.getNamePathLength()).willReturn(3);
			given(folderMetadataJpaRepository.findById(20L)).willReturn(Optional.of(parent));
			given(folderMetadataJpaRepository.findDeepestFolderByPrefix(eq(rootId), eq("/p/source/")))
				.willReturn(Optional.empty());
			given(fileMetadataJpaRepository.findDeepestFileByPrefix(eq(rootId), eq("/p/source/")))
				.willReturn(Optional.empty());

			// validateParentsUtil
			willDoNothing().given(validateParentsUtil).validateParentsFolderLock(source, target);

			// events
			given(source.getSize()).willReturn(123L);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));

			FolderMoveDto dto = moveDto(userId, targetId, rootId, "lock-key-name-only");

			assertDoesNotThrow(() -> folderService.moveFolder(sourceId, dto));

			// 저장/업데이트 호출 확인
			then(source).should().updateParentFolderId(targetId);
			then(source).should().updateIdFullPath("/5/30/");
			then(source).should().updateNameFullPath("/t/");
			then(source).should().updateNamePathLength(anyInt());
			then(source).should().markMoving();
			then(folderMetadataJpaRepository).should().save(source);

			// 이벤트 3개
			ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
			then(publisher).should(times(3)).publishEvent(captor.capture());

			assertEquals(3, captor.getAllValues().size());
			assertTrue(captor.getAllValues().get(0) instanceof FolderSizeEvent);
			assertTrue(captor.getAllValues().get(1) instanceof FolderSizeEvent);
			assertTrue(captor.getAllValues().get(2) instanceof FolderMoveEvent);
		}

		@Test
		@DisplayName("실패: moving lock 못 잡으면 CustomException, 이후 로직 진행 안 함")
		void fail_moving_lock_conflict() {
			given(folderMetadataJpaRepository.getMovingLock(10L)).willReturn(0);

			assertThrows(CustomException.class, () -> folderService.moveFolder(10L, moveDto(100L, 30L, 1L, "x")));

			then(folderJobJpaRepository).shouldHaveNoInteractions();
			then(folderMetadataJpaRepository).should(never()).findByIdNotDeleted(anyLong());
			then(publisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: job insert!=1이면 CustomException, source/target 조회 전 종료")
		void fail_job_insert_not_1() {
			given(folderMetadataJpaRepository.getMovingLock(10L)).willReturn(1);
			given(folderJobJpaRepository.insert(eq(10L), eq(10L), eq(0L), eq(FolderJobStatus.WAITING.name())))
				.willReturn(0);

			assertThrows(CustomException.class, () -> folderService.moveFolder(10L, moveDto(100L, 30L, 1L, "x")));
			then(folderMetadataJpaRepository).should(never()).findByIdNotDeleted(anyLong());
		}

		@Test
		@DisplayName("실패: job insert에서 DataIntegrityViolationException이면 CustomException, source/target 조회 전 종료")
		void fail_job_insert_duplicate() {
			given(folderMetadataJpaRepository.getMovingLock(10L)).willReturn(1);
			given(folderJobJpaRepository.insert(eq(10L), eq(10L), eq(0L), eq(FolderJobStatus.WAITING.name())))
				.willThrow(new DataIntegrityViolationException("dup"));

			assertThrows(CustomException.class, () -> folderService.moveFolder(10L, moveDto(100L, 30L, 1L, "x")));
			then(folderMetadataJpaRepository).should(never()).findByIdNotDeleted(anyLong());
		}

		@Test
		@DisplayName("실패: source 폴더 없음 -> CustomException")
		void fail_source_not_found() {
			stubJobLockSuccess(10L);
			given(folderMetadataJpaRepository.findByIdNotDeleted(10L)).willReturn(Optional.empty());

			assertThrows(CustomException.class, () -> folderService.moveFolder(10L, moveDto(100L, 30L, 1L, "x")));
			then(folderMetadataJpaRepository).should(never()).findByIdNotDeleted(30L);
		}

		@Test
		@DisplayName("실패: source owner 불일치 -> CustomException, target 조회 전 종료")
		void fail_source_owner_mismatch() {
			stubJobLockSuccess(10L);

			FolderMetadata source = mock(FolderMetadata.class);
			given(source.getOwnerId()).willReturn(999L);
			given(folderMetadataJpaRepository.findByIdNotDeleted(10L)).willReturn(Optional.of(source));

			assertThrows(CustomException.class, () -> folderService.moveFolder(10L, moveDto(100L, 30L, 1L, "x")));
			then(folderMetadataJpaRepository).should(never()).findByIdNotDeleted(30L);
		}

		@Test
		@DisplayName("실패: target 폴더 없음 -> CustomException")
		void fail_target_not_found() {
			stubJobLockSuccess(10L);

			FolderMetadata source = mock(FolderMetadata.class);
			given(source.getOwnerId()).willReturn(100L);
			given(folderMetadataJpaRepository.findByIdNotDeleted(10L)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(30L)).willReturn(Optional.empty());

			assertThrows(CustomException.class, () -> folderService.moveFolder(10L, moveDto(100L, 30L, 1L, "x")));
		}

		@Test
		@DisplayName("실패: target owner 불일치 -> CustomException")
		void fail_target_owner_mismatch() {
			stubJobLockSuccess(10L);

			FolderMetadata source = mock(FolderMetadata.class);
			FolderMetadata target = mock(FolderMetadata.class);

			given(source.getOwnerId()).willReturn(100L);
			given(target.getOwnerId()).willReturn(999L);

			given(folderMetadataJpaRepository.findByIdNotDeleted(10L)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(30L)).willReturn(Optional.of(target));

			assertThrows(CustomException.class, () -> folderService.moveFolder(10L, moveDto(100L, 30L, 1L, "x")));
		}

		@Test
		@DisplayName("실패: rootId mismatch(dto.rootId != source/target.rootId) -> CustomException (중복/경로검증 전 종료)")
		void fail_root_mismatch() {
			long sourceId = 10L;
			long targetId = 30L;
			long dtoRootId = 1L;
			long userId = 100L;

			stubJobLockSuccess(sourceId);

			FolderMetadata source = mock(FolderMetadata.class);
			FolderMetadata target = mock(FolderMetadata.class);

			given(source.getOwnerId()).willReturn(userId);
			given(target.getOwnerId()).willReturn(userId);

			given(source.getRootId()).willReturn(999L); // mismatch
			given(target.getRootId()).willReturn(dtoRootId);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));

			assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, moveDto(userId, targetId, dtoRootId, "x")));

			then(folderMetadataJpaRepository).should(never())
				.existsByParentFolderIdAndUploadFolderName(anyLong(), anyString());
			then(folderMetadataJpaRepository).should(never()).save(any());
			then(publisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: 자기 자신으로 이동(source==target) -> CustomException")
		void fail_move_to_self() {
			long sourceId = 10L;
			long userId = 100L;
			long rootId = 1L;

			stubJobLockSuccess(sourceId);

			FolderMetadata same = mock(FolderMetadata.class);
			given(same.getOwnerId()).willReturn(userId);
			given(same.getRootId()).willReturn(rootId);
			given(same.getId()).willReturn(sourceId);

			// ✅ 한 번만 stubbing하면 두 번 호출돼도 계속 동일 값 리턴됨 (중복 stubbing 금지)
			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(same));

			assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, moveDto(userId, sourceId, rootId, "x")));

			then(folderMetadataJpaRepository).should(times(2)).findByIdNotDeleted(sourceId);
			then(folderMetadataJpaRepository).should(never()).save(any());
			then(folderMetadataJpaRepository).should(never()).existsByParentFolderIdAndUploadFolderName(anyLong(), anyString());
			then(validateParentsUtil).shouldHaveNoInteractions();
			then(publisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: source가 root(parentFolderId==null) -> CustomException")
		void fail_source_is_root() {
			long sourceId = 10L;
			long targetId = 30L;
			long userId = 100L;
			long rootId = 1L;

			stubJobLockSuccess(sourceId);

			FolderMetadata source = mock(FolderMetadata.class);
			FolderMetadata target = mock(FolderMetadata.class);

			given(source.getOwnerId()).willReturn(userId);
			given(target.getOwnerId()).willReturn(userId);

			given(source.getRootId()).willReturn(rootId);
			given(target.getRootId()).willReturn(rootId);

			given(source.getId()).willReturn(sourceId);
			given(target.getId()).willReturn(targetId);

			given(source.getParentFolderId()).willReturn(null);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));

			assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, moveDto(userId, targetId, rootId, "x")));

			then(folderMetadataJpaRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("실패: source의 parent가 target이면(이미 그 폴더에 있음) -> CustomException")
		void fail_same_parent_target() {
			long sourceId = 10L;
			long targetId = 30L;
			long userId = 100L;
			long rootId = 1L;

			stubJobLockSuccess(sourceId);

			FolderMetadata source = mock(FolderMetadata.class);
			FolderMetadata target = mock(FolderMetadata.class);

			given(source.getOwnerId()).willReturn(userId);
			given(target.getOwnerId()).willReturn(userId);

			given(source.getRootId()).willReturn(rootId);
			given(target.getRootId()).willReturn(rootId);

			given(source.getId()).willReturn(sourceId);
			given(target.getId()).willReturn(targetId);

			given(source.getParentFolderId()).willReturn(targetId); // already in target

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));

			assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, moveDto(userId, targetId, rootId, "x")));

			then(folderMetadataJpaRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("실패: target이 source 하위(prefix startsWith)면 -> CustomException")
		void fail_cycle_descendant() {
			long sourceId = 10L;
			long targetId = 30L;
			long userId = 100L;
			long rootId = 1L;

			stubJobLockSuccess(sourceId);

			FolderMetadata source = mock(FolderMetadata.class);
			FolderMetadata target = mock(FolderMetadata.class);

			given(source.getOwnerId()).willReturn(userId);
			given(target.getOwnerId()).willReturn(userId);

			given(source.getRootId()).willReturn(rootId);
			given(target.getRootId()).willReturn(rootId);

			given(source.getId()).willReturn(sourceId);
			given(target.getId()).willReturn(targetId);

			given(source.getParentFolderId()).willReturn(20L);

			given(source.getIdFullPath()).willReturn("/20/10/");
			given(target.getIdFullPath()).willReturn("/20/10/30/"); // descendant

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));

			assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, moveDto(userId, targetId, rootId, "x")));

			then(folderMetadataJpaRepository).should(never())
				.existsByParentFolderIdAndUploadFolderName(anyLong(), anyString());
			then(folderMetadataJpaRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("실패: target에 동일 이름 폴더 존재 -> CustomException")
		void fail_duplicated_name_in_target() {
			long sourceId = 10L;
			long targetId = 30L;
			long userId = 100L;
			long rootId = 1L;

			stubJobLockSuccess(sourceId);

			FolderMetadata source = mock(FolderMetadata.class);
			FolderMetadata target = mock(FolderMetadata.class);

			given(source.getOwnerId()).willReturn(userId);
			given(target.getOwnerId()).willReturn(userId);

			given(source.getRootId()).willReturn(rootId);
			given(target.getRootId()).willReturn(rootId);

			given(source.getId()).willReturn(sourceId);
			given(target.getId()).willReturn(targetId);

			given(source.getParentFolderId()).willReturn(20L);

			given(source.getIdFullPath()).willReturn("/20/10/");
			given(target.getIdFullPath()).willReturn("/5/30/"); // not descendant

			given(source.getUploadFolderName()).willReturn("dup");
			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(targetId, "dup"))
				.willReturn(true);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));

			assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, moveDto(userId, targetId, rootId, "x")));

			then(folderMetadataJpaRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("실패: validatePathLength에서 parent 폴더 못 찾으면 CustomException, validateParentsUtil/save/publish 없이 종료")
		void fail_pathlength_parent_not_found() {
			long sourceId = 10L;
			long targetId = 30L;
			long userId = 100L;
			long rootId = 1L;
			long parentId = 20L;

			stubJobLockSuccess(sourceId);

			FolderMetadata source = mock(FolderMetadata.class);
			FolderMetadata target = mock(FolderMetadata.class);

			given(source.getOwnerId()).willReturn(userId);
			given(target.getOwnerId()).willReturn(userId);

			given(source.getRootId()).willReturn(rootId);
			given(target.getRootId()).willReturn(rootId);

			given(source.getId()).willReturn(sourceId);
			given(target.getId()).willReturn(targetId);

			given(source.getParentFolderId()).willReturn(parentId);

			given(source.getIdFullPath()).willReturn("/20/10/");
			given(target.getIdFullPath()).willReturn("/5/30/"); // not descendant

			given(source.getUploadFolderName()).willReturn("ok");
			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(targetId, "ok"))
				.willReturn(false);

			given(folderMetadataJpaRepository.findById(parentId)).willReturn(Optional.empty());

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));

			assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, moveDto(userId, targetId, rootId, "x")));

			then(validateParentsUtil).shouldHaveNoInteractions();
			then(folderMetadataJpaRepository).should(never()).save(any());
			then(publisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: path too long이면 CustomException, save/publish 없이 종료")
		void fail_path_too_long() {
			ReflectionTestUtils.setField(folderService, "maxPathLength", 5);

			long sourceId = 10L;
			long targetId = 30L;
			long rootId = 1L;
			long userId = 100L;

			stubJobLockSuccess(sourceId);

			FolderMetadata source = mock(FolderMetadata.class);
			FolderMetadata target = mock(FolderMetadata.class);

			given(source.getOwnerId()).willReturn(userId);
			given(target.getOwnerId()).willReturn(userId);

			given(source.getRootId()).willReturn(rootId);
			given(target.getRootId()).willReturn(rootId);

			given(source.getId()).willReturn(sourceId);
			given(target.getId()).willReturn(targetId);

			given(source.getParentFolderId()).willReturn(20L);

			given(source.getIdFullPath()).willReturn("/20/10/");
			given(target.getIdFullPath()).willReturn("/5/30/"); // not descendant

			given(source.getUploadFolderName()).willReturn("ok");
			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(targetId, "ok"))
				.willReturn(false);

			// validatePathLength inputs
			given(source.getNameFullPath()).willReturn("/p/source/");
			given(source.getNamePathLength()).willReturn(9);
			FolderMetadata parent = mock(FolderMetadata.class);
			given(parent.getNamePathLength()).willReturn(0);
			given(folderMetadataJpaRepository.findById(20L)).willReturn(Optional.of(parent));
			given(folderMetadataJpaRepository.findDeepestFolderByPrefix(eq(rootId), eq("/p/source/")))
				.willReturn(Optional.empty());
			given(fileMetadataJpaRepository.findDeepestFileByPrefix(eq(rootId), eq("/p/source/")))
				.willReturn(Optional.empty());
			// (parent 조회 성공 후) target.getNamePathLength이 호출되므로 여기서만 스텁 필요
			given(target.getNamePathLength()).willReturn(3);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));

			assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, moveDto(userId, targetId, rootId, "x")));

			then(validateParentsUtil).shouldHaveNoInteractions();
			then(folderMetadataJpaRepository).should(never()).save(any());
			then(publisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: validateParentsUtil에서 CustomException 던지면 save/publish 없이 종료")
		void fail_validate_parents_util_throws() {
			long sourceId = 10L;
			long targetId = 30L;
			long rootId = 1L;
			long userId = 100L;

			stubJobLockSuccess(sourceId);

			FolderMetadata source = mock(FolderMetadata.class);
			FolderMetadata target = mock(FolderMetadata.class);

			given(source.getOwnerId()).willReturn(userId);
			given(target.getOwnerId()).willReturn(userId);

			given(source.getRootId()).willReturn(rootId);
			given(target.getRootId()).willReturn(rootId);

			given(source.getId()).willReturn(sourceId);
			given(target.getId()).willReturn(targetId);

			given(source.getParentFolderId()).willReturn(20L);

			given(source.getIdFullPath()).willReturn("/20/10/");
			given(target.getIdFullPath()).willReturn("/5/30/");
			given(source.getUploadFolderName()).willReturn("ok");
			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(targetId, "ok"))
				.willReturn(false);

			given(source.getNameFullPath()).willReturn("/p/source/");
			given(source.getNamePathLength()).willReturn(10);
			FolderMetadata parent = mock(FolderMetadata.class);
			given(parent.getNamePathLength()).willReturn(3);
			given(folderMetadataJpaRepository.findById(20L)).willReturn(Optional.of(parent));
			given(folderMetadataJpaRepository.findDeepestFolderByPrefix(eq(rootId), eq("/p/source/")))
				.willReturn(Optional.empty());
			given(fileMetadataJpaRepository.findDeepestFileByPrefix(eq(rootId), eq("/p/source/")))
				.willReturn(Optional.empty());

			given(target.getNamePathLength()).willReturn(3);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));

			willThrow(ErrorCode.PARENT_LOCKED.baseException())
				.given(validateParentsUtil).validateParentsFolderLock(source, target);

			assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, moveDto(userId, targetId, rootId, "x")));

			then(folderMetadataJpaRepository).should(never()).save(any());
			then(publisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: save에서 예외면 이벤트는 발행되지 않는다")
		void fail_save_throws_no_publish() {
			long sourceId = 10L;
			long targetId = 30L;
			long rootId = 1L;
			long userId = 100L;

			stubJobLockSuccess(sourceId);

			FolderMetadata source = mock(FolderMetadata.class);
			FolderMetadata target = mock(FolderMetadata.class);

			given(source.getOwnerId()).willReturn(userId);
			given(target.getOwnerId()).willReturn(userId);

			given(source.getRootId()).willReturn(rootId);
			given(target.getRootId()).willReturn(rootId);

			given(source.getId()).willReturn(sourceId);
			given(target.getId()).willReturn(targetId);

			given(source.getParentFolderId()).willReturn(20L);

			given(source.getIdFullPath()).willReturn("/20/10/");
			given(target.getIdFullPath()).willReturn("/5/30/");
			given(source.getUploadFolderName()).willReturn("ok");
			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(targetId, "ok"))
				.willReturn(false);

			given(source.getNameFullPath()).willReturn("/p/source/");
			given(source.getNamePathLength()).willReturn(10);
			FolderMetadata parent = mock(FolderMetadata.class);
			given(parent.getNamePathLength()).willReturn(3);
			given(folderMetadataJpaRepository.findById(20L)).willReturn(Optional.of(parent));
			given(folderMetadataJpaRepository.findDeepestFolderByPrefix(eq(rootId), eq("/p/source/")))
				.willReturn(Optional.empty());
			given(fileMetadataJpaRepository.findDeepestFileByPrefix(eq(rootId), eq("/p/source/")))
				.willReturn(Optional.empty());

			given(target.getNameFullPath()).willReturn("/t/");
			given(target.getNamePathLength()).willReturn(3);

			willDoNothing().given(validateParentsUtil).validateParentsFolderLock(source, target);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));

			willThrow(new RuntimeException("db")).given(folderMetadataJpaRepository).save(source);

			assertThrows(RuntimeException.class,
				() -> folderService.moveFolder(sourceId, moveDto(userId, targetId, rootId, "x")));
			then(publisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("호출 순서: getFolderJobLock이 source/target 조회보다 먼저 수행된다")
		void order_joblock_before_fetch() {
			long sourceId = 10L;
			long targetId = 30L;
			long rootId = 1L;
			long userId = 100L;

			given(folderMetadataJpaRepository.getMovingLock(sourceId)).willReturn(1);
			given(folderJobJpaRepository.insert(eq(sourceId), eq(sourceId), eq(0L), eq(FolderJobStatus.WAITING.name())))
				.willReturn(1);

			FolderMetadata source = mock(FolderMetadata.class);
			FolderMetadata target = mock(FolderMetadata.class);

			given(source.getOwnerId()).willReturn(userId);
			given(target.getOwnerId()).willReturn(userId);

			given(source.getRootId()).willReturn(999L); // root mismatch로 빠르게 종료(이후 스텁 필요 없음)
			given(target.getRootId()).willReturn(rootId);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceId)).willReturn(Optional.of(source));
			given(folderMetadataJpaRepository.findByIdNotDeleted(targetId)).willReturn(Optional.of(target));

			assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, moveDto(userId, targetId, rootId, "x")));

			InOrder inOrder = inOrder(folderMetadataJpaRepository, folderJobJpaRepository);
			inOrder.verify(folderMetadataJpaRepository).getMovingLock(sourceId);
			inOrder.verify(folderJobJpaRepository)
				.insert(eq(sourceId), eq(sourceId), eq(0L), eq(FolderJobStatus.WAITING.name()));
			inOrder.verify(folderMetadataJpaRepository).findByIdNotDeleted(sourceId);
		}
	}

	// =========================================================
	// createFolder
	// =========================================================
	@Nested
	@DisplayName("createFolder")
	class CreateFolderTests {

		@Test
		@DisplayName("성공: save 2번 호출 + updateIdFullPath는 첫 save 이후에 호출된다")
		void success_save_twice_update_after_first_save() {
			long userId = 100L;
			long parentId = 20L;
			String name = "new";

			CreateFolderReqDto req = createReq(userId, parentId, name, userId);

			User user = mock(User.class);
			given(userRepository.findById(userId)).willReturn(Optional.of(user));

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(parentId)).willReturn(Optional.of(parent));

			// validateFolderOwner + validateFolder(pathLength)
			given(parent.getOwnerId()).willReturn(userId);
			given(parent.getNamePathLength()).willReturn(3);

			// validateFolder(duplicate)
			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(parentId, name))
				.willReturn(false);

			// updateIdFullPath argument
			given(parent.getIdFullPath()).willReturn("/20/");

			FolderMetadata toSave = mock(FolderMetadata.class);
			FolderMetadata saved = mock(FolderMetadata.class);

			given(folderMetadataJpaRepository.save(toSave)).willReturn(saved);
			given(folderMetadataJpaRepository.save(saved)).willReturn(saved);
			given(saved.getId()).willReturn(999L); // return까지 가므로 필요

			try (MockedStatic<FolderMetadataFactory> st = mockStatic(FolderMetadataFactory.class)) {
				st.when(() -> FolderMetadataFactory.createFolderMetadata(user, parent, req)).thenReturn(toSave);

				Long id = folderService.createFolder(req);
				assertEquals(999L, id);

				InOrder inOrder = inOrder(folderMetadataJpaRepository, saved);
				inOrder.verify(folderMetadataJpaRepository).save(toSave);
				inOrder.verify(saved).updateIdFullPath("/20/");
				inOrder.verify(folderMetadataJpaRepository).save(saved);
			}
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
		@DisplayName("실패: validateFolder 2번째 findById에서 parent가 없어지면 CustomException")
		void fail_parent_missing_on_second_find_in_validateFolder() {
			long userId = 100L;
			long parentId = 20L;
			CreateFolderReqDto req = createReq(userId, parentId, "ok", userId);

			User user = mock(User.class);
			given(userRepository.findById(userId)).willReturn(Optional.of(user));

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(parentId))
				.willReturn(Optional.of(parent), Optional.empty()); // 첫 호출(부모 조회) OK, 두 번째(경로 체크) EMPTY

			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(parentId, "ok"))
				.willReturn(false);

			assertThrows(CustomException.class, () -> folderService.createFolder(req));
			then(folderMetadataJpaRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("실패: parent owner 불일치(CustomException) - validateFolder는 통과 후 validateFolderOwner에서 실패")
		void fail_parent_owner_mismatch() {
			long userId = 100L;
			long parentId = 20L;
			CreateFolderReqDto req = createReq(userId, parentId, "ok", userId);

			User user = mock(User.class);
			given(userRepository.findById(userId)).willReturn(Optional.of(user));

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(parentId)).willReturn(Optional.of(parent));

			// validateFolder 통과에 필요한 최소 스텁
			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(parentId, "ok"))
				.willReturn(false);
			given(parent.getNamePathLength()).willReturn(1);

			// validateFolderOwner에서 실패
			given(parent.getOwnerId()).willReturn(999L);

			assertThrows(CustomException.class, () -> folderService.createFolder(req));
			then(folderMetadataJpaRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("실패: 금칙어 포함(CustomException) - duplicate/pathLength 체크 전에 실패")
		void fail_blacklist() {
			long userId = 100L;
			long parentId = 20L;
			char bad = CommonConstant.FILE_NAME_BLACK_LIST[0];

			CreateFolderReqDto req = createReq(userId, parentId, "ab" + bad + "cd", userId);

			given(userRepository.findById(userId)).willReturn(Optional.of(mock(User.class)));
			given(folderMetadataJpaRepository.findById(parentId)).willReturn(Optional.of(mock(FolderMetadata.class)));

			assertThrows(CustomException.class, () -> folderService.createFolder(req));

			then(folderMetadataJpaRepository).should(never())
				.existsByParentFolderIdAndUploadFolderName(anyLong(), anyString());
			then(folderMetadataJpaRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("실패: 같은 depth 동일 이름(CustomException) - pathLength 체크 전에 실패")
		void fail_duplicate_name() {
			long userId = 100L;
			long parentId = 20L;
			CreateFolderReqDto req = createReq(userId, parentId, "dup", userId);

			given(userRepository.findById(userId)).willReturn(Optional.of(mock(User.class)));
			given(folderMetadataJpaRepository.findById(parentId)).willReturn(Optional.of(mock(FolderMetadata.class)));

			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(parentId, "dup"))
				.willReturn(true);

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

			given(userRepository.findById(userId)).willReturn(Optional.of(mock(User.class)));

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(parentId)).willReturn(Optional.of(parent));

			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(parentId, "veryLongName"))
				.willReturn(false);

			// pathLength 계산에 필요
			given(parent.getNamePathLength()).willReturn(9);

			assertThrows(CustomException.class, () -> folderService.createFolder(req));
			then(folderMetadataJpaRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("실패: createFolderMetadata(static)에서 예외면 save는 호출되지 않는다")
		void fail_factory_throws_no_save() {
			long userId = 100L;
			long parentId = 20L;
			CreateFolderReqDto req = createReq(userId, parentId, "ok", userId);

			User user = mock(User.class);
			given(userRepository.findById(userId)).willReturn(Optional.of(user));

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(parentId)).willReturn(Optional.of(parent));

			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(parentId, "ok"))
				.willReturn(false);

			// validateFolder(pathLength) + validateFolderOwner 최소
			given(parent.getNamePathLength()).willReturn(1);
			given(parent.getOwnerId()).willReturn(userId);

			try (MockedStatic<FolderMetadataFactory> st = mockStatic(FolderMetadataFactory.class)) {
				st.when(() -> FolderMetadataFactory.createFolderMetadata(user, parent, req))
					.thenThrow(new RuntimeException("boom"));

				assertThrows(RuntimeException.class, () -> folderService.createFolder(req));
				then(folderMetadataJpaRepository).should(never()).save(any());
			}
		}

		@Test
		@DisplayName("실패: 첫 번째 save에서 예외면 updateIdFullPath/두번째 save는 호출되지 않는다")
		void fail_first_save_throws() {
			long userId = 100L;
			long parentId = 20L;
			CreateFolderReqDto req = createReq(userId, parentId, "ok", userId);

			User user = mock(User.class);
			given(userRepository.findById(userId)).willReturn(Optional.of(user));

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(parentId)).willReturn(Optional.of(parent));

			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(parentId, "ok"))
				.willReturn(false);

			given(parent.getNamePathLength()).willReturn(1);
			given(parent.getOwnerId()).willReturn(userId);

			FolderMetadata toSave = mock(FolderMetadata.class);

			try (MockedStatic<FolderMetadataFactory> st = mockStatic(FolderMetadataFactory.class)) {
				st.when(() -> FolderMetadataFactory.createFolderMetadata(user, parent, req)).thenReturn(toSave);

				willThrow(new RuntimeException("db")).given(folderMetadataJpaRepository).save(toSave);

				assertThrows(RuntimeException.class, () -> folderService.createFolder(req));
				then(folderMetadataJpaRepository).should(times(1)).save(toSave);
			}
		}

		@Test
		@DisplayName("실패: updateIdFullPath에서 예외면 두번째 save는 호출되지 않는다 (STRICT_STUBS 대응)")
		void fail_updateIdFullPath_throws() {
			long userId = 100L;
			long parentId = 20L;
			CreateFolderReqDto req = createReq(userId, parentId, "ok", userId);

			User user = mock(User.class);
			given(userRepository.findById(userId)).willReturn(Optional.of(user));

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(parentId)).willReturn(Optional.of(parent));

			// validateFolder 통과
			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(parentId, "ok"))
				.willReturn(false);
			given(parent.getNamePathLength()).willReturn(1);

			// validateFolderOwner 통과
			given(parent.getOwnerId()).willReturn(userId);

			// updateIdFullPath 인자
			given(parent.getIdFullPath()).willReturn("/20/");

			FolderMetadata toSave = mock(FolderMetadata.class);
			FolderMetadata saved = mock(FolderMetadata.class);

			given(folderMetadataJpaRepository.save(toSave)).willReturn(saved);
			willThrow(new RuntimeException("boom")).given(saved).updateIdFullPath("/20/");

			try (MockedStatic<FolderMetadataFactory> st = mockStatic(FolderMetadataFactory.class)) {
				st.when(() -> FolderMetadataFactory.createFolderMetadata(user, parent, req)).thenReturn(toSave);

				assertThrows(RuntimeException.class, () -> folderService.createFolder(req));

				then(folderMetadataJpaRepository).should(times(1)).save(toSave);
				then(folderMetadataJpaRepository).should(never()).save(saved);
				then(saved).should(never()).getId(); // return까지 못 감(= 불필요 stubbing 방지)
			}
		}

		@Test
		@DisplayName("실패: 두번째 save에서 예외면 예외 전파된다")
		void fail_second_save_throws_propagates() {
			long userId = 100L;
			long parentId = 20L;
			CreateFolderReqDto req = createReq(userId, parentId, "ok", userId);

			User user = mock(User.class);
			given(userRepository.findById(userId)).willReturn(Optional.of(user));

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(parentId)).willReturn(Optional.of(parent));

			given(folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(parentId, "ok"))
				.willReturn(false);

			given(parent.getNamePathLength()).willReturn(1);
			given(parent.getOwnerId()).willReturn(userId);
			given(parent.getIdFullPath()).willReturn("/20/");

			FolderMetadata toSave = mock(FolderMetadata.class);
			FolderMetadata saved = mock(FolderMetadata.class);

			given(folderMetadataJpaRepository.save(toSave)).willReturn(saved);
			willThrow(new RuntimeException("db2")).given(folderMetadataJpaRepository).save(saved);

			try (MockedStatic<FolderMetadataFactory> st = mockStatic(FolderMetadataFactory.class)) {
				st.when(() -> FolderMetadataFactory.createFolderMetadata(user, parent, req)).thenReturn(toSave);

				assertThrows(RuntimeException.class, () -> folderService.createFolder(req));
			}
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
		@DisplayName("성공: 최상위 폴더(parent=null)면 MessageInfoEvent만 발행하고 1 반환")
		void root_folder_publish_message_done() {
			FolderMetadata folder = mock(FolderMetadata.class);
			given(folder.getParentFolderId()).willReturn(null);
			given(folderMetadataJpaRepository.findById(10L)).willReturn(Optional.of(folder));

			int result = folderService.updateFolderSize(1L, 10L, 100L);

			assertEquals(1, result);
			then(publisher).should(times(1)).publishEvent(any(MessageInfoEvent.class));
			then(publisher).should(never()).publishEvent(any(FolderSizeEvent.class));
			then(messageInfoJpaRepository).shouldHaveNoInteractions();
			then(folderMetadataJpaRepository).should(never())
				.updateFolderSizeWithVersion(anyLong(), anyLong(), anyLong());
		}

		@Test
		@DisplayName("성공: pending 메시지 없으면 1 반환(업데이트/이벤트 없음)")
		void no_pending_return_1() {
			FolderMetadata folder = mock(FolderMetadata.class);
			given(folder.getParentFolderId()).willReturn(20L);
			given(folderMetadataJpaRepository.findById(10L)).willReturn(Optional.of(folder));

			given(messageInfoJpaRepository.existsByIdAndStatus(1L, MessageStatus.PENDING)).willReturn(false);

			int result = folderService.updateFolderSize(1L, 10L, 100L);

			assertEquals(1, result);
			then(folderMetadataJpaRepository).should(never())
				.updateFolderSizeWithVersion(anyLong(), anyLong(), anyLong());
			then(publisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("성공: update 결과 0이면 0 반환(완료 이벤트/상위 전파 없음)")
		void update_conflict_returns_0() {
			FolderMetadata folder = mock(FolderMetadata.class);
			given(folder.getParentFolderId()).willReturn(20L);
			given(folder.getId()).willReturn(10L);
			given(folder.getVersion()).willReturn(3L);

			given(folderMetadataJpaRepository.findById(10L)).willReturn(Optional.of(folder));
			given(messageInfoJpaRepository.existsByIdAndStatus(1L, MessageStatus.PENDING)).willReturn(true);
			given(folderMetadataJpaRepository.updateFolderSizeWithVersion(100L, 10L, 3L)).willReturn(0);

			int result = folderService.updateFolderSize(1L, 10L, 100L);

			assertEquals(0, result);
			then(publisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("성공: update 성공하면 MessageInfoEvent + FolderSizeEvent 발행하고 1 반환")
		void update_success_publish_two_events() {
			FolderMetadata folder = mock(FolderMetadata.class);
			given(folder.getParentFolderId()).willReturn(20L);
			given(folder.getId()).willReturn(10L);
			given(folder.getVersion()).willReturn(3L);

			given(folderMetadataJpaRepository.findById(10L)).willReturn(Optional.of(folder));
			given(messageInfoJpaRepository.existsByIdAndStatus(1L, MessageStatus.PENDING)).willReturn(true);
			given(folderMetadataJpaRepository.updateFolderSizeWithVersion(100L, 10L, 3L)).willReturn(1);

			int result = folderService.updateFolderSize(1L, 10L, 100L);

			assertEquals(1, result);

			ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
			then(publisher).should(times(2)).publishEvent(captor.capture());

			assertTrue(captor.getAllValues().get(0) instanceof MessageInfoEvent);
			assertTrue(captor.getAllValues().get(1) instanceof FolderSizeEvent);
		}
	}
}