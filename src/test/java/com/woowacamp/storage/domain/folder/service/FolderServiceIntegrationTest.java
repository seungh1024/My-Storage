package com.woowacamp.storage.domain.folder.service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.woowacamp.storage.config.IntegrationTestBase;
import com.woowacamp.storage.domain.folder.dto.command.MovePlan;
import com.woowacamp.storage.domain.folder.dto.command.MoveLockContext;
import com.woowacamp.storage.domain.folder.dto.request.FolderMoveDto;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.utils.FolderJobStatus;
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

	private MoveLockContext moveLockContext(long sourceFolderId, FolderMoveDto dto) {
		return new MoveLockContext(sourceFolderId, dto.targetFolderId(), dto.rootId(), dto.folderName());
	}

	/**
	 * 상위 폴더를 ACTIVE MOVE 상태로 만든다.
	 * - folder_operation_state ACTIVE MOVE row 생성
	 * - folder_job row 생성
	 */
	private void markActiveMoveInDb(long folderId) {
		FolderMetadata folder = folderMetadataJpaRepository.findById(folderId)
			.orElseThrow();
		Long rootId = folder.getRootId() == null ? folder.getId() : folder.getRootId();
		MovePlan movePlan = new MovePlan(
			0,
			folder.getNamePathLength(),
			folder.getIdFullPath(),
			folder.getNameFullPath(),
			folder.getNamePathLength()
		);
		folderService.getFolderJobLock(rootId, folderId, movePlan);
	}

	@FunctionalInterface
	private interface BooleanSupplierWithException {
		boolean getAsBoolean() throws Exception;
	}

	private void await(BooleanSupplierWithException condition, long timeoutMs, long intervalMs) {
		Awaitility.await()
			.atMost(Duration.ofMillis(timeoutMs))
			.pollInterval(Duration.ofMillis(intervalMs))
			.until(() -> {
				try {
					return condition.getAsBoolean();
				} catch (Exception e) {
					return false;
				}
			});
	}

	private void assertFolderTreeIntegrity(long rootFolderId) {
		List<FolderMetadata> folders = folderMetadataJpaRepository.findAll().stream()
			.filter(folder -> !folder.isDeleted())
			.filter(folder -> folder.getId() == rootFolderId || Long.valueOf(rootFolderId).equals(folder.getRootId()))
			.toList();

		Map<Long, FolderMetadata> folderById = new HashMap<>();
		Map<Long, List<FolderMetadata>> childrenByParentId = new HashMap<>();
		for (FolderMetadata folder : folders) {
			folderById.put(folder.getId(), folder);
			if (folder.getParentFolderId() != null) {
				childrenByParentId.computeIfAbsent(folder.getParentFolderId(), key -> new ArrayList<>())
					.add(folder);
			}
		}

		assertTrue(folderById.containsKey(rootFolderId), "루트 폴더가 존재해야 합니다");

		Set<Long> visited = new HashSet<>();
		ArrayDeque<Long> queue = new ArrayDeque<>();
		queue.add(rootFolderId);
		while (!queue.isEmpty()) {
			Long currentId = queue.poll();
			if (!visited.add(currentId)) {
				continue;
			}
			for (FolderMetadata child : childrenByParentId.getOrDefault(currentId, List.of())) {
				queue.add(child.getId());
			}
		}

		assertEquals(folderById.size(), visited.size(), "고아 폴더 없이 모든 폴더가 루트에서 탐색되어야 합니다");

		for (FolderMetadata folder : folders) {
			assertTrue(folder.getIdFullPath().startsWith("/"));
			assertTrue(folder.getIdFullPath().endsWith("/"));
			assertTrue(folder.getNameFullPath().startsWith("/"));
			assertTrue(folder.getNameFullPath().endsWith("/"));

			if (folder.getParentFolderId() == null) {
				assertEquals(rootFolderId, folder.getId(), "부모가 없는 폴더는 루트만 허용");
				assertEquals("/", folder.getIdFullPath(), "루트의 idFullPath는 '/' 이어야 합니다");
				assertEquals("/", folder.getNameFullPath(), "루트의 nameFullPath는 '/' 이어야 합니다");
				continue;
			}

			assertTrue(folder.getIdFullPath().endsWith("/" + folder.getId() + "/"));

			FolderMetadata parent = folderById.get(folder.getParentFolderId());
			assertNotNull(parent, "모든 부모는 같은 루트 트리 내부에 존재해야 합니다");
			assertTrue(folder.getIdFullPath().startsWith(parent.getIdFullPath()),
				"idFullPath는 부모 prefix를 포함해야 합니다");
			assertTrue(folder.getNameFullPath().startsWith(parent.getNameFullPath()),
				"nameFullPath는 부모 prefix를 포함해야 합니다");
		}
	}

	private List<FolderMetadata> findActiveFoldersInRoot(long rootFolderId) {
		return folderMetadataJpaRepository.findAll().stream()
			.filter(folder -> !folder.isDeleted())
			.filter(folder -> folder.getId() == rootFolderId || Long.valueOf(rootFolderId).equals(folder.getRootId()))
			.toList();
	}

	// =========================================================
	// 폴더 이동 테스트
	// =========================================================
	@Nested
	@DisplayName("폴더 이동 테스트")
	class FolderMoveTest {

		@Test
		@DisplayName("source folder가 없는 경우 FOLDER_NOT_FOUND 예외를 던진다.")
		void source_id_not_exist_test() {
			long sourceId = folderTreeSetUp.getLongestFolder().getId();
			folderMetadataJpaRepository.delete(folderTreeSetUp.getLongestFolder());
			folderMetadataJpaRepository.flush();

			FolderMetadata targetFolder = folderTreeSetUp.getSubFolders().get(1);
			long targetId = targetFolder.getId();

			FolderMoveDto dto = moveDto(userId, targetId, targetFolder.getRootId(), defaultFolderName);

			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.moveFolder(moveLockContext(sourceId, dto), dto));

			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("target folder가 없는 경우 FOLDER_NOT_FOUND 예외를 던진다.")
		void target_id_not_exist_test() {
			FolderMetadata sourceFolder = folderTreeSetUp.getSubFolders().get(1);
			long sourceId = sourceFolder.getId();

			long targetId = 1000L;
			FolderMoveDto dto = moveDto(userId, targetId, sourceFolder.getRootId(), defaultFolderName);

			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.moveFolder(moveLockContext(sourceId, dto), dto));

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
				() -> folderService.moveFolder(moveLockContext(sourceId, dto), dto));

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
				() -> folderService.moveFolder(moveLockContext(sourceId, dto), dto));

			assertEquals(ErrorCode.FOLDER_MOVE_NOT_AVAILABLE.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("폴더 이동 성공 테스트(용량 전파 포함)")
		void folder_move_success_test() {
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

			folderService.moveFolder(moveLockContext(sourceId, dto), dto);

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

		}

		private void findSize(Map<Long, Long> map, Long id) {
			while (id != null) {
				FolderMetadata folderMetadata = folderMetadataJpaRepository.findById(id).orElseThrow();
				map.put(id, folderMetadata.getSize());
				id = folderMetadata.getParentFolderId();
			}
		}

		// =========================================================
		// ✅ DB 기반 상위 ACTIVE MOVE 상태 탐지 테스트
		// =========================================================

		@Test
		@DisplayName("source 폴더의 상위 폴더가 DB에서 ACTIVE MOVE 상태면(PARENT_LOCKED) 하위 폴더 이동이 불가능하다")
		void source_folder_parent_moving_conflict_db_active_move_test() {
			// child(하위)를 이동시키려는데, parent(상위)가 ACTIVE MOVE 상태면 PARENT_LOCKED
			FolderMetadata child = folderTreeSetUp.getSubSubFolder();
			long childId = child.getId();

			FolderMetadata parent = folderMetadataJpaRepository
				.findById(child.getParentFolderId())
				.orElseThrow();
			long parentId = parent.getId();

			// ✅ DB에서 ACTIVE MOVE 상태 생성
			markActiveMoveInDb(parentId);

			FolderMetadata targetFolder = folderTreeSetUp.getSubFolders().get(2);
			long targetId = targetFolder.getId();

			FolderMoveDto dto = moveDto(userId, targetId, targetFolder.getRootId(), defaultFolderName);

			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.moveFolder(moveLockContext(childId, dto), dto));

			assertEquals(ErrorCode.PARENT_LOCKED.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("target 폴더의 상위 폴더가 DB에서 ACTIVE MOVE 상태면(PARENT_LOCKED) 이동이 불가능하다")
		void target_folder_parent_moving_conflict_db_active_move_test() {
			// source를 target으로 이동시키려는데, target의 parent가 ACTIVE MOVE 상태면 PARENT_LOCKED
			FolderMetadata targetChild = folderTreeSetUp.getSubSubFolder();
			long targetChildId = targetChild.getId();

			FolderMetadata targetParent = folderMetadataJpaRepository
				.findById(targetChild.getParentFolderId())
				.orElseThrow();
			long targetParentId = targetParent.getId();

			// ✅ target의 상위(부모)를 ACTIVE MOVE 상태로 만든다
			markActiveMoveInDb(targetParentId);

			FolderMetadata sourceFolder = folderTreeSetUp.getSubFolders().get(2);
			long sourceId = sourceFolder.getId();

			FolderMoveDto dto = moveDto(userId, targetChildId, sourceFolder.getRootId(), defaultFolderName);

			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.moveFolder(moveLockContext(sourceId, dto), dto));

			assertEquals(ErrorCode.PARENT_LOCKED.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("이미 ACTIVE MOVE 상태(source itself)인 폴더는 다시 이동할 수 없다(PARENT_LOCKED)")
		void source_already_moving_conflict_test() {
			FolderMetadata sourceFolder = folderTreeSetUp.getSubFolders().get(1);
			long sourceId = sourceFolder.getId();

			FolderMetadata targetFolder = folderTreeSetUp.getSubFolders().get(2);
			long targetId = targetFolder.getId();

			// source 자체를 moving으로 만들어둠
			markActiveMoveInDb(sourceId);

			FolderMoveDto dto = moveDto(userId, targetId, sourceFolder.getRootId(), defaultFolderName);

			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.moveFolder(moveLockContext(sourceId, dto), dto));

			// source(rootId, folderId)에 ACTIVE MOVE row가 이미 있으므로 상위/하위 작업 충돌로 차단
			String msg = ex.getMessage();
			assertEquals(ErrorCode.PARENT_LOCKED.getMessage(), msg);
		}

		@Test
		@DisplayName("폴더 이름 길이가 최대치를 초과하면 폴더 이동에 실패한다.")
		void folder_move_maximum_depth_test() {
			FolderMetadata sourceFolder = folderTreeSetUp.getSubFolders().get(1);
			long sourceId = sourceFolder.getId();

			FolderMetadata targetFolder = folderTreeSetUp.getLongestFolder();
			long targetId = targetFolder.getId();

			FolderMoveDto dto = moveDto(userId, targetId, targetFolder.getRootId(), sourceFolder.getUploadFolderName());

			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.moveFolder(moveLockContext(sourceId, dto), dto));

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
			folderService.moveFolder(moveLockContext(childId, dto), dto);

			FolderMetadata moved = folderMetadataJpaRepository.findById(childId).orElseThrow();
			assertEquals(targetId, moved.getParentFolderId());
		}
	}

	@Nested
	@DisplayName("폴더 이동 실패 케이스")
	class FolderMoveFailureTest {

		@Test
		@DisplayName("검증 단계에서 실패하면 source 상태가 유지되고 folder_job이 생성되지 않는다")
		void validation_failure_before_job_creation_keeps_source_stable() {
			FolderMetadata sourceFolder = folderTreeSetUp.getSubFolders().get(1);
			long sourceId = sourceFolder.getId();

			long invalidTargetId = 999999L; // 존재하지 않는 target
			FolderMoveDto dto = moveDto(userId, invalidTargetId, sourceFolder.getRootId(), defaultFolderName);

			CustomException ex = assertThrows(CustomException.class,
				() -> folderService.moveFolder(moveLockContext(sourceId, dto), dto));

			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), ex.getMessage());

			assertTrue(folderJobJpaRepository.findById(sourceId).isEmpty());
		}
	}

	@Nested
	@DisplayName("동시성 테스트")
	class ConcurrentFolderMoveTest {

		@Test
		@DisplayName("동시 이동 후에도 전체 트리는 고아 없이 prefix 일관성을 유지한다")
		void concurrent_folder_move_tree_integrity_test() {
			long rootId = folderTreeSetUp.getRootFolder().getId();
			long rootSize = folderTreeSetUp.getRootFolder().getSize();
			int totalTasks = 120;
			int maxPickAttemptsPerTask = 8;

			ExecutorService executorService = Executors.newFixedThreadPool(16);
			CountDownLatch latch = new CountDownLatch(totalTasks);
			AtomicInteger successCount = new AtomicInteger(0);
			ConcurrentLinkedQueue<Throwable> unexpectedErrors = new ConcurrentLinkedQueue<>();

			for (int i = 0; i < totalTasks; i++) {
				executorService.submit(() -> {
					try {
						for (int attempt = 0; attempt < maxPickAttemptsPerTask; attempt++) {
							List<FolderMetadata> folders = findActiveFoldersInRoot(rootId);
							List<FolderMetadata> movableSources = folders.stream()
								.filter(folder -> folder.getId() != rootId)
								.toList();

							if (movableSources.isEmpty()) {
								return;
							}

							FolderMetadata source = movableSources.get(
								ThreadLocalRandom.current().nextInt(movableSources.size()));

							List<FolderMetadata> targets = folders.stream()
								.filter(target -> !target.getId().equals(source.getId()))
								.filter(target -> !target.getId().equals(source.getParentFolderId()))
								.filter(target -> !target.getIdFullPath().startsWith(source.getIdFullPath()))
								.toList();

							if (targets.isEmpty()) {
								continue;
							}

								FolderMetadata target = targets.get(
									ThreadLocalRandom.current().nextInt(targets.size()));

								try {
									FolderMoveDto request = moveDto(userId, target.getId(), rootId, defaultFolderName);
									folderService.moveFolder(moveLockContext(source.getId(), request), request);
									successCount.incrementAndGet();
									return;
								} catch (CustomException ignored) {
								// 동시성 충돌/검증 실패는 허용하고 다음 후보로 재시도
							}
						}
					} catch (Throwable t) {
						unexpectedErrors.add(t);
					} finally {
						latch.countDown();
					}
				});
			}

			try {
				latch.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				fail("Concurrent move integrity test interrupted");
			}
			executorService.shutdown();

			if (!unexpectedErrors.isEmpty()) {
				Throwable first = unexpectedErrors.peek();
				fail("동시 이동 중 비정상 오류 발생: " + first.getClass().getSimpleName());
			}
			assertTrue(successCount.get() > 0, "랜덤 이동이 최소 1건 이상 성공해야 합니다");

			await(() -> folderJobJpaRepository.findAll().stream()
					.noneMatch(job -> job.getStatus() == FolderJobStatus.WAITING
						|| job.getStatus() == FolderJobStatus.RUNNING),
				20_000, 300);

			await(() -> folderMetadataJpaRepository.findById(rootId)
				.map(root -> root.getSize() == rootSize)
				.orElse(false), 15_000, 300);

			FolderMetadata refreshedRoot = folderMetadataJpaRepository.findById(rootId).orElseThrow();
			assertEquals(rootSize, refreshedRoot.getSize());
			assertFolderTreeIntegrity(rootId);
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
				com.woowacamp.storage.domain.folder.dto.type.CursorType.FOLDER,
				limit,
				com.woowacamp.storage.domain.folder.dto.type.FolderContentsSortField.CREATED_AT,
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
				com.woowacamp.storage.domain.folder.dto.type.CursorType.FOLDER,
				limit,
				com.woowacamp.storage.domain.folder.dto.type.FolderContentsSortField.CREATED_AT,
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
				com.woowacamp.storage.domain.folder.dto.type.CursorType.FOLDER,
				10,
				com.woowacamp.storage.domain.folder.dto.type.FolderContentsSortField.CREATED_AT,
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
