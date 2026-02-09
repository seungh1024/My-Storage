package com.woowacamp.storage.domain.folder.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.woowacamp.storage.config.IntegrationTestBase;
import com.woowacamp.storage.domain.folder.dto.request.FolderMoveDto;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.global.error.CustomException;
import com.woowacamp.storage.global.error.ErrorCode;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FolderServiceIntegrationTest extends IntegrationTestBase {

	@Autowired
	private FolderMetadataJpaRepository folderMetadataJpaRepository;

	@Autowired
	private FolderService folderService;

	private final long userId = 1L;
	private final String defaultFolderName = "default folder";

	@BeforeEach
	void setUp() {
		// 테스트 간 간섭 방지: moveFolder가 folder_job, message_info를 남길 수 있으니 먼저 청소
		cleanup();
		folderTreeSetUp.setupFolderTree();
	}

	private FolderMoveDto moveDto(long userId, long targetFolderId, long rootId, String folderName) {
		return new FolderMoveDto(userId, targetFolderId, rootId, folderName);
	}

	/**
	 * ✅ “상위 폴더가 이동중” 상태를 DB에서 만든다.
	 * - folder_metadata.is_moving = true (getMovingLock)
	 * - folder_job row 생성
	 *
	 */
	private void markMovingInDb(long folderId) {
		folderService.getFolderJobLock(folderId);
	}

	private void await(BooleanSupplierWithException condition, long timeoutMs, long intervalMs) throws Exception {
		long start = System.currentTimeMillis();
		while (System.currentTimeMillis() - start < timeoutMs) {
			if (condition.getAsBoolean())
				return;
			Thread.sleep(intervalMs);
		}
		fail("condition not satisfied within timeout: " + timeoutMs + "ms");
	}

	@FunctionalInterface
	private interface BooleanSupplierWithException {
		boolean getAsBoolean() throws Exception;
	}

	// =========================================================
	// 폴더 이동 테스트
	// =========================================================
	@Nested
	@DisplayName("폴더 이동 테스트")
	class FolderMoveTest {

		@Test
		@DisplayName("source folder가 없는 경우 FAILED_TO_GET_FOLDER_LOCK 예외를 던진다.")
		void source_id_not_exist_test() {
			long sourceId = folderTreeSetUp.getLongestFolder().getId();
			folderMetadataJpaRepository.delete(folderTreeSetUp.getLongestFolder());
			folderMetadataJpaRepository.flush();

			FolderMetadata targetFolder = folderTreeSetUp.getSubFolders().get(1);
			long targetId = targetFolder.getId();

			FolderMoveDto dto = moveDto(userId, targetId, targetFolder.getRootId(), defaultFolderName);

			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, dto));

			assertEquals(ErrorCode.FAILED_TO_GET_FOLDER_LOCK.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("target folder가 없는 경우 FOLDER_NOT_FOUND 예외를 던진다.")
		void target_id_not_exist_test() {
			FolderMetadata sourceFolder = folderTreeSetUp.getSubFolders().get(1);
			long sourceId = sourceFolder.getId();

			long targetId = 1000L;
			FolderMoveDto dto = moveDto(userId, targetId, sourceFolder.getRootId(), defaultFolderName);

			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, dto));

			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("source id와 target id가 동일한 경우 FOLDER_MOVE_NOT_AVAILABLE 예외를 던진다.")
		void source_id_equals_target_id_test() {
			FolderMetadata sourceFolder = folderTreeSetUp.getSubFolders().get(1);
			long sourceId = sourceFolder.getId();
			long targetId = sourceId;

			FolderMoveDto dto = moveDto(userId, targetId, sourceFolder.getRootId(), defaultFolderName);

			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, dto));

			assertEquals(ErrorCode.FOLDER_MOVE_NOT_AVAILABLE.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("자신의 하위 폴더 트리로 이동하면 FOLDER_MOVE_NOT_AVAILABLE 예외를 발생한다.")
		void folder_move_to_child_folder_test() {
			FolderMetadata targetFolder = folderTreeSetUp.getSubSubFolder();
			long targetId = targetFolder.getId();

			FolderMetadata sourceFolder = folderMetadataJpaRepository
				.findById(targetFolder.getParentFolderId())
				.orElseThrow();
			long sourceId = sourceFolder.getId();

			FolderMoveDto dto = moveDto(userId, targetId, sourceFolder.getRootId(), defaultFolderName);

			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, dto));

			assertEquals(ErrorCode.FOLDER_MOVE_NOT_AVAILABLE.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("폴더 이동 성공 테스트(용량 전파 포함)")
		void folder_move_success_test() throws Exception {
			FolderMetadata sourceFolder = folderTreeSetUp.getSubFolders().get(1);
			long sourceId = sourceFolder.getId();

			FolderMetadata targetFolder = folderTreeSetUp.getSubFolders().get(2);
			long targetId = targetFolder.getId();

			FolderMoveDto dto = moveDto(userId, targetId, sourceFolder.getRootId(), defaultFolderName);

			long moveSize = sourceFolder.getSize();
			long targetSize = targetFolder.getSize();

			Map<Long, Long> sourceInfo = new HashMap<>();
			Map<Long, Long> targetInfo = new HashMap<>();

			Long originalParentId = sourceFolder.getParentFolderId();
			findSize(sourceInfo, originalParentId);
			findSize(targetInfo, targetId);

			folderService.moveFolder(sourceId, dto);

			// ✅ 용량 전파를 위한 대기 시간 증가 (비동기 처리 + MQ 왕복)
			await(() -> {
				FolderMetadata refreshedTarget = folderMetadataJpaRepository.findById(targetId).orElseThrow();
				System.out.println("Waiting for size update. Current target size: " + refreshedTarget.getSize() + ", Expected: " + (moveSize + targetSize));
				return refreshedTarget.getSize() == moveSize + targetSize;
			}, 20_000, 500);  // ✅ 20초로 증가, 체크 간격 500ms

			FolderMetadata refreshedTarget = folderMetadataJpaRepository.findById(targetId).orElseThrow();
			FolderMetadata refreshedSource = folderMetadataJpaRepository.findById(sourceId).orElseThrow();

			assertEquals(moveSize + targetSize, refreshedTarget.getSize());
			assertEquals(targetId, refreshedSource.getParentFolderId());

			Map<Long, Long> movedSizeInfo = new HashMap<>();
			findSize(movedSizeInfo, originalParentId);
			findSize(movedSizeInfo, targetId);

			for (Map.Entry<Long, Long> entry : sourceInfo.entrySet()) {
				Long key = entry.getKey();
				Long value = entry.getValue();
				if (targetInfo.get(key) == null) {
					assertEquals(value - moveSize, movedSizeInfo.get(key));
				}
			}

			for (Map.Entry<Long, Long> entry : targetInfo.entrySet()) {
				Long key = entry.getKey();
				Long value = entry.getValue();
				if (sourceInfo.get(key) == null) {
					assertEquals(value + moveSize, movedSizeInfo.get(key));
				}
			}

			// ✅ 이동 처리 완료 후 is_moving 해제 확인
			await(() -> {
				FolderMetadata movingCheck = folderMetadataJpaRepository.findById(sourceId).orElseThrow();
				return !movingCheck.isMoving();
			}, 20_000, 500);

			FolderMetadata afterMove = folderMetadataJpaRepository.findById(sourceId).orElseThrow();
			assertFalse(afterMove.isMoving());
		}

		private void findSize(Map<Long, Long> map, Long id) {
			while (id != null) {
				FolderMetadata folderMetadata = folderMetadataJpaRepository.findById(id).orElseThrow();
				map.put(id, folderMetadata.getSize());
				id = folderMetadata.getParentFolderId();
			}
		}

		// =========================================================
		// ✅ DB 기반 상위 이동중(is_moving) 탐지 테스트로 변경
		// =========================================================

		@Test
		@DisplayName("source 폴더의 상위 폴더가 DB에서 이동 중이면(PARENT_LOCKED) 하위 폴더 이동이 불가능하다")
		void source_folder_parent_moving_conflict_db_is_moving_test() {
			// child(하위)를 이동시키려는데, parent(상위)가 moving 상태면 PARENT_LOCKED
			FolderMetadata child = folderTreeSetUp.getSubSubFolder();
			long childId = child.getId();

			FolderMetadata parent = folderMetadataJpaRepository
				.findById(child.getParentFolderId())
				.orElseThrow();
			long parentId = parent.getId();

			// ✅ Redis 락 대신 DB에서 moving 상태 생성
			markMovingInDb(parentId);

			FolderMetadata targetFolder = folderTreeSetUp.getSubFolders().get(2);
			long targetId = targetFolder.getId();

			FolderMoveDto dto = moveDto(userId, targetId, targetFolder.getRootId(), defaultFolderName);

			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.moveFolder(childId, dto));

			assertEquals(ErrorCode.PARENT_LOCKED.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("target 폴더의 상위 폴더가 DB에서 이동 중이면(PARENT_LOCKED) 이동이 불가능하다")
		void target_folder_parent_moving_conflict_db_is_moving_test() {
			// source를 target으로 이동시키려는데, target의 parent가 moving이면 PARENT_LOCKED
			FolderMetadata targetChild = folderTreeSetUp.getSubSubFolder();
			long targetChildId = targetChild.getId();

			FolderMetadata targetParent = folderMetadataJpaRepository
				.findById(targetChild.getParentFolderId())
				.orElseThrow();
			long targetParentId = targetParent.getId();

			// ✅ target의 상위(부모)를 moving으로 만든다
			markMovingInDb(targetParentId);

			FolderMetadata sourceFolder = folderTreeSetUp.getSubFolders().get(2);
			long sourceId = sourceFolder.getId();

			FolderMoveDto dto = moveDto(userId, targetChildId, sourceFolder.getRootId(), defaultFolderName);

			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, dto));

			assertEquals(ErrorCode.PARENT_LOCKED.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("이미 DB에서 이동 중으로 마킹된(source itself) 폴더는 다시 이동할 수 없다(FOLDER_LOCK_CONFLICT 또는 FOLDER_JOB_CONFLICT)")
		void source_already_moving_conflict_test() {
			FolderMetadata sourceFolder = folderTreeSetUp.getSubFolders().get(1);
			long sourceId = sourceFolder.getId();

			FolderMetadata targetFolder = folderTreeSetUp.getSubFolders().get(2);
			long targetId = targetFolder.getId();

			// source 자체를 moving으로 만들어둠
			markMovingInDb(sourceId);

			FolderMoveDto dto = moveDto(userId, targetId, sourceFolder.getRootId(), defaultFolderName);

			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, dto));

			// 구현에 따라 lock 단계에서 막히면 FOLDER_LOCK_CONFLICT,
			// job insert unique에 막히면 FOLDER_JOB_CONFLICT가 나올 수 있음
			String msg = ex.getMessage();
			assertTrue(
				msg.equals(ErrorCode.FAILED_TO_GET_FOLDER_LOCK.getMessage())
					|| msg.equals(ErrorCode.FOLDER_JOB_CONFLICT.getMessage()),
				"unexpected message: " + msg
			);
		}

		@Test
		@DisplayName("폴더 이름 길이가 최대치를 초과하면 폴더 이동에 실패한다.")
		void folder_move_maximum_depth_test() {
			FolderMetadata sourceFolder = folderTreeSetUp.getSubFolders().get(1);

			FolderMetadata targetFolder = folderTreeSetUp.getLongestFolder();
			long targetId = targetFolder.getId();

			FolderMoveDto dto = moveDto(userId, targetId, targetFolder.getRootId(), sourceFolder.getUploadFolderName());

			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceFolder.getId(), dto));

			assertEquals(ErrorCode.EXCEED_MAX_PATH_LENGTH.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("target 폴더의 부모 폴더가 아닌 상위 폴더로 이동할 수 있다.")
		void target_folder_move_success_test() {
			FolderMetadata childFolder = folderTreeSetUp.getSubSubFolder();
			long childId = childFolder.getId();

			FolderMetadata parentFolder = folderMetadataJpaRepository
				.findById(childFolder.getParentFolderId())
				.orElseThrow();

			FolderMetadata targetFolder = folderMetadataJpaRepository
				.findById(parentFolder.getParentFolderId())
				.orElseThrow();

			long targetId = targetFolder.getId();

			FolderMoveDto dto = moveDto(userId, targetId, childFolder.getRootId(), defaultFolderName);
			folderService.moveFolder(childId, dto);

			FolderMetadata moved = folderMetadataJpaRepository.findById(childId).orElseThrow();
			assertEquals(targetId, moved.getParentFolderId());
		}
	}

	@Nested
	@DisplayName("폴더 이동 실패 케이스")
	class FolderMoveFailureTest {

		@Test
		@DisplayName("source 폴더의 상위 폴더가 DB에서 이동 중이면(PARENT_LOCKED) 이동이 불가능하다")
		void source_folder_parent_moving_conflict_db_is_moving_test() {
			FolderMetadata child = folderTreeSetUp.getSubSubFolder();
			long childId = child.getId();

			FolderMetadata parent = folderMetadataJpaRepository
				.findById(child.getParentFolderId())
				.orElseThrow();
			long parentId = parent.getId();

			// ✅ Redis 락 대신 DB에서 moving 상태 생성
			markMovingInDb(parentId);

			FolderMetadata targetFolder = folderTreeSetUp.getSubFolders().get(2);
			long targetId = targetFolder.getId();

			FolderMoveDto dto = moveDto(userId, targetId, targetFolder.getRootId(), defaultFolderName);

			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.moveFolder(childId, dto));

			assertEquals(ErrorCode.PARENT_LOCKED.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("target 폴더의 상위 폴더가 DB에서 이동 중이면(PARENT_LOCKED) 이동이 불가능하다")
		void target_folder_parent_moving_conflict_db_is_moving_test() {
			FolderMetadata targetChild = folderTreeSetUp.getSubSubFolder();
			long targetChildId = targetChild.getId();

			FolderMetadata targetParent = folderMetadataJpaRepository
				.findById(targetChild.getParentFolderId())
				.orElseThrow();
			long targetParentId = targetParent.getId();

			// ✅ target의 상위(부모)를 moving으로 만든다
			markMovingInDb(targetParentId);

			FolderMetadata sourceFolder = folderTreeSetUp.getSubFolders().get(2);
			long sourceId = sourceFolder.getId();

			FolderMoveDto dto = moveDto(userId, targetChildId, sourceFolder.getRootId(), defaultFolderName);

			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, dto));

			assertEquals(ErrorCode.PARENT_LOCKED.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("이미 DB에서 이동 중으로 마킹된(source itself) 폴더는 다시 이동할 수 없다(FOLDER_LOCK_CONFLICT 또는 FOLDER_JOB_CONFLICT)")
		void source_already_moving_conflict_test() {
			FolderMetadata sourceFolder = folderTreeSetUp.getSubFolders().get(1);
			long sourceId = sourceFolder.getId();

			FolderMetadata targetFolder = folderTreeSetUp.getSubFolders().get(2);
			long targetId = targetFolder.getId();

			// source 자체를 moving으로 만들어둠
			markMovingInDb(sourceId);

			FolderMoveDto dto = moveDto(userId, targetId, sourceFolder.getRootId(), defaultFolderName);

			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, dto));

			// 구현에 따라 lock 단계에서 막히면 FOLDER_LOCK_CONFLICT,
			// job insert unique에 막히면 FOLDER_JOB_CONFLICT가 나올 수 있음
			String msg = ex.getMessage();
			assertTrue(
				msg.equals(ErrorCode.FAILED_TO_GET_FOLDER_LOCK.getMessage())
					|| msg.equals(ErrorCode.FOLDER_JOB_CONFLICT.getMessage()),
				"unexpected message: " + msg
			);
		}

		@Test
		@DisplayName("폴더 이름 길이가 최대치를 초과하면 폴더 이동에 실패한다.")
		void folder_move_maximum_depth_test() {
			FolderMetadata sourceFolder = folderTreeSetUp.getSubFolders().get(1);

			FolderMetadata targetFolder = folderTreeSetUp.getLongestFolder();
			long targetId = targetFolder.getId();

			FolderMoveDto dto = moveDto(userId, targetId, targetFolder.getRootId(), sourceFolder.getUploadFolderName());

			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceFolder.getId(), dto));

			assertEquals(ErrorCode.EXCEED_MAX_PATH_LENGTH.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("target 폴더의 부모 폴더가 아닌 상위 폴더로 이동할 수 있다.")
		void target_folder_move_success_test() {
			FolderMetadata childFolder = folderTreeSetUp.getSubSubFolder();
			long childId = childFolder.getId();

			FolderMetadata parentFolder = folderMetadataJpaRepository
				.findById(childFolder.getParentFolderId())
				.orElseThrow();

			FolderMetadata targetFolder = folderMetadataJpaRepository
				.findById(parentFolder.getParentFolderId())
				.orElseThrow();

			long targetId = targetFolder.getId();

			FolderMoveDto dto = moveDto(userId, targetId, childFolder.getRootId(), defaultFolderName);
			folderService.moveFolder(childId, dto);

			FolderMetadata moved = folderMetadataJpaRepository.findById(childId).orElseThrow();
			assertEquals(targetId, moved.getParentFolderId());
		}

		@Test
		@DisplayName("검증 실패 시 is_moving 해제 및 folder_job 삭제가 수행된다")
		void validation_failure_releases_moving_lock_and_deletes_job() {
			FolderMetadata sourceFolder = folderTreeSetUp.getSubFolders().get(1);
			long sourceId = sourceFolder.getId();

			long invalidTargetId = 999999L; // 존재하지 않는 target
			FolderMoveDto dto = moveDto(userId, invalidTargetId, sourceFolder.getRootId(), defaultFolderName);

			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, dto));

			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), ex.getMessage());

			FolderMetadata refreshedSource = folderMetadataJpaRepository.findById(sourceId).orElseThrow();
			assertFalse(refreshedSource.isMoving());
			assertTrue(folderJobJpaRepository.findById(sourceId).isEmpty());
		}
	}

	@Nested
	@DisplayName("동시성 테스트")
	class ConcurrentFolderMoveTest {

		@Test
		@DisplayName("폴더 이동 동시성 테스트: 루트 용량은 깨지지 않는다")
		void concurrent_folder_move_test() throws Exception {
			FolderMetadata folderA = folderTreeSetUp.getSubSubFolder();
			FolderMetadata folderB = folderMetadataJpaRepository.findById(folderA.getParentFolderId()).orElseThrow();

			long aId = folderA.getId();
			long bId = folderB.getId();

			long aParent = folderA.getParentFolderId();
			long bParent = folderB.getParentFolderId();

			long targetFolderId = folderTreeSetUp.getSubFolders().get(5).getId();

			long rootId = folderTreeSetUp.getRootFolder().getId();
			long rootSize = folderTreeSetUp.getRootFolder().getSize();

			int rounds = 10;

			// ✅ 라운드마다 4개 task 제출하니 latch는 rounds*4가 되어야 함
			int totalTasks = rounds * 4;

			ExecutorService executorService = Executors.newFixedThreadPool(16);
			CountDownLatch latch = new CountDownLatch(totalTasks);

			for (int i = 0; i < rounds; i++) {
				executorService.submit(() -> {
					try {
						folderService.moveFolder(aId, moveDto(userId, targetFolderId, rootId, defaultFolderName));
					} catch (Exception ignored) {
					} finally {
						latch.countDown();
					}
				});

				executorService.submit(() -> {
					try {
						folderService.moveFolder(bId, moveDto(userId, targetFolderId, rootId, defaultFolderName));
					} catch (Exception ignored) {
					} finally {
						latch.countDown();
					}
				});

				executorService.submit(() -> {
					try {
						folderService.moveFolder(aId, moveDto(userId, aParent, rootId, defaultFolderName));
					} catch (Exception ignored) {
					} finally {
						latch.countDown();
					}
				});

				executorService.submit(() -> {
					try {
						folderService.moveFolder(bId, moveDto(userId, bParent, rootId, defaultFolderName));
					} catch (Exception ignored) {
					} finally {
						latch.countDown();
					}
				});
			}

			latch.await();
			executorService.shutdown();

			// ✅ 비동기 용량 반영 안정화 대기 (폴링)
			await(() -> {
				FolderMetadata root = folderMetadataJpaRepository.findById(rootId).orElseThrow();
				return root.getSize() == rootSize;
			}, 15_000, 300);

			FolderMetadata findRootFolder = folderMetadataJpaRepository.findById(rootId).orElseThrow();
			assertEquals(rootSize, findRootFolder.getSize());
		}
	}

	// =========================================================
	// 폴더 삭제 통합 테스트
	// =========================================================
	@Nested
	@DisplayName("폴더 삭제 통합 테스트")
	class FolderDeleteIntegrationTest {

		@Test
		@DisplayName("폴더 삭제 성공: soft delete 후 is_deleted가 true가 된다")
		void delete_folder_success_test() {
			// given
			FolderMetadata targetFolder = folderTreeSetUp.getSubFolders().get(1);
			long folderId = targetFolder.getId();

			// when
			folderService.deleteFolder(folderId, userId);

			// then
			FolderMetadata deleted = folderMetadataJpaRepository.findById(folderId).orElseThrow();
			assertTrue(deleted.isDeleted(), "폴더가 soft delete 되어야 합니다");
		}

		@Test
		@DisplayName("폴더 삭제 실패: 존재하지 않는 폴더")
		void delete_folder_not_found_test() {
			// given
			long nonExistentId = 999999L;

			// when & then
			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.deleteFolder(nonExistentId, userId));

			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("폴더 삭제 실패: 다른 사용자의 폴더")
		void delete_folder_access_denied_test() {
			// given
			FolderMetadata targetFolder = folderTreeSetUp.getSubFolders().get(1);
			long folderId = targetFolder.getId();
			long wrongUserId = 999L;

			// when & then
			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.deleteFolder(folderId, wrongUserId));

			assertEquals(ErrorCode.ACCESS_DENIED.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("폴더 삭제 실패: root 폴더는 삭제할 수 없다")
		void delete_root_folder_fail_test() {
			// given
			FolderMetadata rootFolder = folderTreeSetUp.getRootFolder();
			long rootId = rootFolder.getId();

			// when & then
			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.deleteFolder(rootId, userId));

			assertEquals(ErrorCode.INVALID_DELETE_REQUEST.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("폴더 삭제 실패: 이미 삭제된 폴더")
		void delete_already_deleted_folder_test() {
			// given
			FolderMetadata targetFolder = folderTreeSetUp.getSubFolders().get(1);
			long folderId = targetFolder.getId();

			// 먼저 삭제
			folderService.deleteFolder(folderId, userId);

			// when & then
			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.deleteFolder(folderId, userId));

			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), ex.getMessage());
		}

	}

	// =========================================================
	// 폴더 조회 통합 테스트 (getFolderContents)
	// =========================================================
	@Nested
	@DisplayName("폴더 내용 조회 통합 테스트")
	class FolderContentsRetrievalTest {

		@Test
		@DisplayName("폴더 조회 성공: CursorType.FOLDER로 하위 폴더만 조회")
		void get_folder_contents_folders_only_test() {
			// given
			FolderMetadata rootFolder = folderTreeSetUp.getRootFolder();
			long folderId = rootFolder.getId();
			int limit = 10;

			// when
			var result = folderService.getFolderContents(
				folderId,
				0L,  // cursorId
				com.woowacamp.storage.domain.folder.dto.CursorType.FOLDER,
				limit,
				com.woowacamp.storage.domain.folder.dto.FolderContentsSortField.CREATED_AT,
				org.springframework.data.domain.Sort.Direction.DESC,
				java.time.LocalDateTime.now(),
				null,
				true  // ownerRequested
			);

			// then
			assertNotNull(result);
			assertFalse(result.folderMetadataList().isEmpty(), "하위 폴더가 있어야 합니다");

			// 모든 폴더가 root의 자식인지 확인
			for (FolderMetadata folder : result.folderMetadataList()) {
				assertEquals(folderId, folder.getParentFolderId(),
					"조회된 폴더들은 모두 root의 직접 자식이어야 합니다");
			}
		}

		@Test
		@DisplayName("폴더 조회: FOLDER 타입인데 폴더가 limit보다 적으면 파일로 채운다")
		void get_folder_contents_fills_with_files_test() {
			// given
			FolderMetadata folder = folderTreeSetUp.getSubFolders().get(0);
			long folderId = folder.getId();
			int limit = 100;  // 큰 limit으로 폴더를 다 소진시킴

			// when
			var result = folderService.getFolderContents(
				folderId,
				0L,
				com.woowacamp.storage.domain.folder.dto.CursorType.FOLDER,
				limit,
				com.woowacamp.storage.domain.folder.dto.FolderContentsSortField.CREATED_AT,
				org.springframework.data.domain.Sort.Direction.DESC,
				java.time.LocalDateTime.now(),
				null,
				true
			);

			// then
			assertNotNull(result);
			int totalItems = result.folderMetadataList().size() + result.fileMetadataList().size();
			assertTrue(totalItems <= limit, "총 아이템 수는 limit 이하여야 합니다");
		}

		@Test
		@DisplayName("폴더 조회: 정렬 - CREATED_AT DESC로 최신순 정렬")
		void get_folder_contents_sort_by_created_at_desc_test() {
			// given
			FolderMetadata rootFolder = folderTreeSetUp.getRootFolder();
			long folderId = rootFolder.getId();

			// when
			var result = folderService.getFolderContents(
				folderId,
				0L,
				com.woowacamp.storage.domain.folder.dto.CursorType.FOLDER,
				10,
				com.woowacamp.storage.domain.folder.dto.FolderContentsSortField.CREATED_AT,
				org.springframework.data.domain.Sort.Direction.DESC,
				java.time.LocalDateTime.now(),
				null,
				true
			);

			// then: 생성일자가 내림차순으로 정렬되어 있어야 함
			var folders = result.folderMetadataList();
			for (int i = 0; i < folders.size() - 1; i++) {
				java.time.LocalDateTime current = folders.get(i).getCreatedAt();
				java.time.LocalDateTime next = folders.get(i + 1).getCreatedAt();

				assertTrue(current.isAfter(next) || current.isEqual(next),
					"생성일자가 내림차순으로 정렬되어야 합니다");
			}
		}
	}

	// =========================================================
	// 폴더 소유권 체크 통합 테스트
	// =========================================================
	@Nested
	@DisplayName("폴더 소유권 체크 통합 테스트")
	class FolderOwnershipCheckTest {

		@Test
		@DisplayName("checkFolderOwnedBy 성공: 소유자가 맞으면 통과")
		void check_folder_owned_by_success_test() {
			// given
			FolderMetadata folder = folderTreeSetUp.getSubFolders().get(0);
			long folderId = folder.getId();
			long ownerId = folder.getOwnerId();

			// when & then: 예외가 발생하지 않아야 함
			assertDoesNotThrow(() ->
				folderService.checkFolderOwnedBy(folderId, ownerId)
			);
		}

		@Test
		@DisplayName("checkFolderOwnedBy 실패: 폴더가 존재하지 않음")
		void check_folder_owned_by_not_found_test() {
			// given
			long nonExistentId = 999999L;

			// when & then
			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.checkFolderOwnedBy(nonExistentId, userId));

			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("checkFolderOwnedBy 실패: 소유자가 아님")
		void check_folder_owned_by_access_denied_test() {
			// given
			FolderMetadata folder = folderTreeSetUp.getSubFolders().get(0);
			long folderId = folder.getId();
			long wrongUserId = 999L;

			// when & then
			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.checkFolderOwnedBy(folderId, wrongUserId));

			assertEquals(ErrorCode.ACCESS_DENIED.getMessage(), ex.getMessage());
		}
	}
}
