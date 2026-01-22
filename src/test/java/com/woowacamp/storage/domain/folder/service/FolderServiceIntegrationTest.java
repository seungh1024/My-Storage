package com.woowacamp.storage.domain.folder.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.woowacamp.storage.config.FolderTreeSetUp;
import com.woowacamp.storage.container.ContainerBaseConfig;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.dto.request.FolderMoveDto;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderJobJpaRepository;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.message.repository.MessageInfoJpaRepository;
import com.woowacamp.storage.domain.message.util.JsonSerializer;
import com.woowacamp.storage.global.error.CustomException;
import com.woowacamp.storage.global.error.ErrorCode;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FolderServiceIntegrationTest extends ContainerBaseConfig {

	@Autowired
	private FolderMetadataJpaRepository folderMetadataRepository;

	@Autowired
	private FileMetadataJpaRepository fileMetadataJpaRepository;

	@Autowired
	private FolderJobJpaRepository folderJobJpaRepository;

	@Autowired
	private MessageInfoJpaRepository messageInfoJpaRepository;

	@Autowired
	private FolderTreeSetUp folderTreeSetUp;

	@Autowired
	private FolderService folderService;

	@Autowired
	private JsonSerializer jsonSerializer;

	private final long userId = 1L;
	private final String defaultFolderName = "default folder";

	@BeforeEach
	void setUp() {
		// 테스트 간 간섭 방지: moveFolder가 folder_job, message_info를 남길 수 있으니 먼저 청소
		safeCleanup();
		folderTreeSetUp.folderTreeSetUp();
	}

	@AfterEach
	void afterEach() {
		safeCleanup();
	}

	private void safeCleanup() {
		// FK가 걸려있을 수 있으니 “자식 → 부모” 순서로 정리
		try { messageInfoJpaRepository.deleteAllInBatch(); } catch (Exception ignored) {}
		try { folderJobJpaRepository.deleteAllInBatch(); } catch (Exception ignored) {}
		try { fileMetadataJpaRepository.deleteAllInBatch(); } catch (Exception ignored) {}
		try { folderMetadataRepository.deleteAllInBatch(); } catch (Exception ignored) {}
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
			if (condition.getAsBoolean()) return;
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
			folderMetadataRepository.delete(folderTreeSetUp.getLongestFolder());
			folderMetadataRepository.flush();

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

			FolderMetadata sourceFolder = folderMetadataRepository
				.findParentByParentFolderId(targetFolder.getParentFolderId())
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

			// 이벤트 기반 용량 반영이 비동기일 수 있으니 polling으로 안정화
			// TODO 컨슈머 만들어야 함. 이동 전파도 생김
			await(() -> {
				FolderMetadata refreshedTarget = folderMetadataRepository.findById(targetId).orElseThrow();
				return refreshedTarget.getSize() == moveSize + targetSize;
			}, 12_000, 200);

			FolderMetadata refreshedTarget = folderMetadataRepository.findById(targetId).orElseThrow();
			FolderMetadata refreshedSource = folderMetadataRepository.findById(sourceId).orElseThrow();

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
		}

		private void findSize(Map<Long, Long> map, Long id) {
			while (id != null) {
				FolderMetadata folderMetadata = folderMetadataRepository.findById(id).orElseThrow();
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

			FolderMetadata parent = folderMetadataRepository
				.findParentByParentFolderId(child.getParentFolderId())
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

			FolderMetadata targetParent = folderMetadataRepository
				.findParentByParentFolderId(targetChild.getParentFolderId())
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

			FolderMetadata parentFolder = folderMetadataRepository
				.findParentByParentFolderId(childFolder.getParentFolderId())
				.orElseThrow();

			FolderMetadata targetFolder = folderMetadataRepository
				.findById(parentFolder.getParentFolderId())
				.orElseThrow();

			long targetId = targetFolder.getId();

			FolderMoveDto dto = moveDto(userId, targetId, childFolder.getRootId(), defaultFolderName);
			folderService.moveFolder(childId, dto);

			FolderMetadata moved = folderMetadataRepository.findById(childId).orElseThrow();
			assertEquals(targetId, moved.getParentFolderId());
		}
	}

	// =========================================================
	// 동시성 테스트(버그 수정 + 안정화)
	// =========================================================
	@Nested
	@DisplayName("동시성 테스트")
	class ConcurrentFolderMoveTest {

		@Test
		@DisplayName("폴더 이동 동시성 테스트: 루트 용량은 깨지지 않는다(CountDownLatch 버그 수정)")
		void concurrent_folder_move_test() throws Exception {
			FolderMetadata folderA = folderTreeSetUp.getSubSubFolder();
			FolderMetadata folderB = folderMetadataRepository.findById(folderA.getParentFolderId()).orElseThrow();

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
				FolderMetadata root = folderMetadataRepository.findById(rootId).orElseThrow();
				return root.getSize() == rootSize;
			}, 15_000, 300);

			FolderMetadata findRootFolder = folderMetadataRepository.findById(rootId).orElseThrow();
			assertEquals(rootSize, findRootFolder.getSize());
		}
	}
}