package com.woowacamp.storage.domain.folder.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.dto.message.FolderSizeMessageDto;
import com.woowacamp.storage.domain.folder.dto.request.FolderMoveDto;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.event.FolderSizeEvent;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.message.entity.MessageInfo;
import com.woowacamp.storage.domain.message.repository.MessageInfoJpaRepository;
import com.woowacamp.storage.domain.message.util.EventType;
import com.woowacamp.storage.domain.message.util.JsonSerializer;
import com.woowacamp.storage.global.constant.PermissionType;
import com.woowacamp.storage.global.error.CustomException;
import com.woowacamp.storage.global.error.ErrorCode;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FolderServiceTest extends ContainerBaseConfig {

	@Autowired
	private FolderMetadataJpaRepository folderMetadataRepository;

	@Autowired
	private FileMetadataJpaRepository fileMetadataJpaRepository;

	@Autowired
	private FolderTreeSetUp folderTreeSetUp;

	@Autowired
	private FolderService folderService;

	@Autowired
	private RedisLockService redisLockService;

	@Autowired
	private JsonSerializer jsonSerializer;

	@Autowired
	private MessageInfoJpaRepository messageInfoJpaRepository;

	private long userId = 1L;

	@BeforeEach
	void setUp() {
		folderTreeSetUp.folderTreeSetUp();
	}

	@AfterEach
	void afterEach() {
		fileMetadataJpaRepository.deleteAllInBatch();
		folderMetadataRepository.deleteAllInBatch();
	}

	@Nested
	@DisplayName("폴더 이동 테스트")
	class FolderMoveTest {

		@Test
		@DisplayName("source folder가 없는 경우 FOLDER_NOT_FOUND 예외를 던진다.")
		void source_id_not_exist_test() {
			long sourceId = 1000L;
			long targetId = folderTreeSetUp.getSubFolders().get(1).getId();
			FolderMoveDto dto = new FolderMoveDto(userId, targetId);
			CustomException customException = assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, dto));

			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("target folder가 없는 경우 FOLDER_NOT_FOUND 예외를 던진다.")
		void target_id_not_exist_test() {
			long sourceId = folderTreeSetUp.getSubFolders().get(1).getId();
			long targetId = 1000L;
			FolderMoveDto dto = new FolderMoveDto(userId, targetId);
			CustomException customException = assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, dto));

			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("source id와 target id가 동일한 경우 FOLDER_MOVE_NOT_AVAILABLE 예외를 던진다.")
		void source_id_equals_target_id_test() {
			long sourceId = folderTreeSetUp.getSubFolders().get(1).getId();
			long targetId = folderTreeSetUp.getSubFolders().get(1).getId();
			FolderMoveDto dto = new FolderMoveDto(userId, targetId);
			CustomException customException = assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, dto));

			assertEquals(ErrorCode.FOLDER_MOVE_NOT_AVAILABLE.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("폴더 이동 성공 테스트")
		void folder_move_success_test() throws InterruptedException {
			long sourceId = folderTreeSetUp.getSubFolders().get(1).getId();
			long targetId = folderTreeSetUp.getSubFolders().get(2).getId();
			FolderMoveDto dto = new FolderMoveDto(userId, targetId);

			long moveSize = folderTreeSetUp.getSubFolders().get(1).getSize();
			long targetSize = folderTreeSetUp.getSubFolders().get(2).getSize();

			// source 폴더의 상위, target부터 상위 폴더 사이즈를 전부 기록
			Map<Long, Long> sourceInfo = new HashMap<>();
			Map<Long, Long> targetInfo = new HashMap<>();
			Long parentFolderId = folderTreeSetUp.getSubFolders().get(1).getParentFolderId();
			Long id = parentFolderId;
			findSize(sourceInfo, id);
			id = targetId;
			findSize(targetInfo, id);

			folderService.moveFolder(sourceId, dto);
			Thread.sleep(5000);

			FolderMetadata targetFolder = folderMetadataRepository.findById(targetId).get();
			FolderMetadata sourceFolder = folderMetadataRepository.findById(sourceId).get();

			assertEquals(moveSize + targetSize, targetFolder.getSize());
			assertEquals(targetId, sourceFolder.getParentFolderId());

			Map<Long, Long> movedSizeInfo = new HashMap<>();
			findSize(movedSizeInfo, parentFolderId); // 기존 부모부터 탐색
			id = targetFolder.getId(); // 이동 부모부터 탐색
			findSize(movedSizeInfo, id);

			for (Map.Entry<Long, Long> entry : sourceInfo.entrySet()) {
				Long key = entry.getKey();
				Long value = entry.getValue();
				if (targetInfo.get(key) == null) {
					assertEquals(movedSizeInfo.get(key), value - moveSize);
				}
			}
			for (Map.Entry<Long, Long> entry : targetInfo.entrySet()) {
				Long key = entry.getKey();
				Long value = entry.getValue();
				if (sourceInfo.get(key) == null) {
					assertEquals(movedSizeInfo.get(key), value + moveSize);
				}
			}
		}

		void findSize(Map<Long, Long> map, Long id) {
			while (id != null) {
				FolderMetadata folderMetadata = folderMetadataRepository.findById(id).get();
				map.put(id, folderMetadata.getSize());
				id = folderMetadata.getParentFolderId();
			}
		}

		@Test
		@DisplayName("자신의 하위 폴더 트리로 이동하면 FOLDER_MOVE_NOT_AVAILABLE 예외를 발생한다.")
		void folder_move_to_child_folder_test() {
			FolderMetadata targetFolder = folderTreeSetUp.getSubSubFolder();
			long targetId = targetFolder.getId();
			FolderMetadata sourceFolder = folderMetadataRepository.findParentByParentFolderId(
				targetFolder.getParentFolderId()).get();
			long sourceId = folderMetadataRepository.findById(sourceFolder.getId()).get().getId();
			FolderMoveDto dto = new FolderMoveDto(userId, targetId);

			CustomException customException = assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, dto));

			assertEquals(ErrorCode.FOLDER_MOVE_NOT_AVAILABLE.getMessage(), customException.getMessage());

		}

		@Test
		@DisplayName("source 폴더의 상위 폴더가 이동 중이면 하위 폴더는 이동 작업을 할 수 없다.")
		void source_folder_move_conflict_test() {
			FolderMetadata childFolder = folderTreeSetUp.getSubSubFolder();
			long childId = childFolder.getId();
			FolderMetadata parentFolder = folderMetadataRepository.findParentByParentFolderId(
				childFolder.getParentFolderId()).get();
			long parentId = folderMetadataRepository.findById(parentFolder.getId()).get().getId();

			String lockName = parentId + "";
			redisLockService.tryLock(lockName);

			long targetId = folderTreeSetUp.getSubFolders().get(2).getId();
			FolderMoveDto dto = new FolderMoveDto(userId, targetId);

			CustomException customException = assertThrows(CustomException.class,
				() -> folderService.moveFolder(childId, dto));
			redisLockService.unlock(lockName);

			assertEquals(ErrorCode.PARENT_LOCKED.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("target 폴더의 상위 폴더가 이동 중이면 하위 폴더는 이동 작업을 할 수 없다.")
		void target_folder_move_conflict_test() {
			FolderMetadata childFolder = folderTreeSetUp.getSubSubFolder();
			long childId = childFolder.getId();
			FolderMetadata parentFolder = folderMetadataRepository.findParentByParentFolderId(
				childFolder.getParentFolderId()).get();
			long parentId = folderMetadataRepository.findById(parentFolder.getId()).get().getId();

			String lockName = parentId + "";
			redisLockService.tryLock(lockName);

			long sourceId = folderTreeSetUp.getSubFolders().get(2).getId();
			FolderMoveDto dto = new FolderMoveDto(userId, childId);

			CustomException customException = assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, dto));
			redisLockService.unlock(lockName);

			assertEquals(ErrorCode.PARENT_LOCKED.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("폴더 깊이가 최대치를 초과하면 폴더 이동에 실패한다.")
		void folder_move_maximum_depth_test() {
			FolderMetadata targetFolder = folderTreeSetUp.getSubSubFolder();
			long targetId = targetFolder.getId();
			long sourceId = folderTreeSetUp.getSubFolders().get(2).getId();
			long parentId = sourceId;
			LocalDateTime now = LocalDateTime.now();

			for (int i = 0; i < 48; i++) {
				FolderMetadata folder = folderMetadataRepository.save(FolderMetadata.builder()
					.rootId(folderTreeSetUp.getRootFolder().getId())
					.creatorId(userId)
					.createdAt(now.minusDays(1))
					.updatedAt(now)
					.parentFolderId(parentId)
					.uploadFolderName("folder " + i)
					.sharingExpiredAt(now)
					.size(1000)
					.ownerId(userId)
					.permissionType(PermissionType.WRITE)
					.build());

				parentId = folder.getId();
			}

			FolderMoveDto dto = new FolderMoveDto(userId, targetId);

			CustomException customException = assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, dto));

			assertEquals(ErrorCode.EXCEED_MAX_FOLDER_DEPTH.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("target 폴더의 부모 폴더가 아닌 상위 폴더로 이동할 수 있다.")
		void target_folder_move_success_test() {
			FolderMetadata childFolder = folderTreeSetUp.getSubSubFolder();
			long childId = childFolder.getId();
			FolderMetadata parentFolder = folderMetadataRepository.findParentByParentFolderId(
				childFolder.getParentFolderId()).get();
			FolderMetadata targetFolder = folderMetadataRepository.findById(parentFolder.getParentFolderId()).get();
			long targetId = targetFolder.getId();

			FolderMoveDto dto = new FolderMoveDto(userId, targetId);
			folderService.moveFolder(childId, dto);

			FolderMetadata folder = folderMetadataRepository.findById(childId).get();
			assertEquals(targetId, folder.getParentFolderId());
		}

		@Test
		@DisplayName("용량 업데이트와 폴더 이동이 충돌해도 3회 재시도하여 성공한다.")
		void retry_size_update_success_test() {
			FolderMetadata childFolder = folderTreeSetUp.getSubSubFolder();
			FolderMetadata parentFolder = folderMetadataRepository.findById(childFolder.getParentFolderId()).get();
			FolderMetadata rootFolder = folderMetadataRepository.findById(parentFolder.getParentFolderId()).get();
			FolderMetadata otherFolder = folderTreeSetUp.getSubFolders().get(2);

			long originVersion = parentFolder.getVersion();

			folderMetadataRepository.updateParentInfoWithVersion(parentFolder.getId(),otherFolder.getId(),parentFolder.getVersion());

			FolderMetadata temp = folderMetadataRepository.findById(parentFolder.getId()).get();
			System.out.println("version = "+temp.getVersion());
			long parentSize = parentFolder.getSize();
			long rootSize = rootFolder.getSize();

			long size = 100;
			FolderSizeEvent event = new FolderSizeEvent(childFolder.getParentFolderId(), size);
			MessageInfo messageInfo = event.toEntity(jsonSerializer.serialize(event));
			messageInfoJpaRepository.save(messageInfo);

			folderService.updateFolderSize(messageInfo.getId(), childFolder.getParentFolderId(), size);

			parentFolder = folderMetadataRepository.findById(childFolder.getParentFolderId()).get();
			rootFolder = folderMetadataRepository.findById(rootFolder.getId()).get();

			assertEquals(parentSize + size, parentFolder.getSize());
			assertEquals(rootSize, rootFolder.getSize());
			assertEquals(originVersion+2 , parentFolder.getVersion());
		}
	}

	@Nested
	@DisplayName("폴더 삭제 테스트")
	class FolderDeleteTest {

		@Test
		@DisplayName("존재하지 않는 폴더를 제거하면 FOLDER_NOT_FOUND 예외가 발생한다")
		void delete_not_exists_test() {
			Long folderId = 1000L;

			CustomException customException = assertThrows(CustomException.class,
				() -> folderService.deleteFolder(folderId, userId));

			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("soft delete가 된 폴더를 제거하면 FOLDER_NOT_FOUND 예외가 발생한다")
		void delete_if_soft_deleted_folder_test() {
			Long folderId = 2L;
			folderMetadataRepository.softDeleteById(folderId);

			CustomException customException = assertThrows(CustomException.class,
				() -> folderService.deleteFolder(folderId, userId));

			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("권한이 없는 폴더를 제거하면 ACCESS_DENIED 예외가 발생한다")
		void delete_if_not_folder_owner_test() {
			Long folderId = folderTreeSetUp.getRootFolder().getId();
			long notOwnerId = 3L;

			CustomException customException = assertThrows(CustomException.class,
				() -> folderService.deleteFolder(folderId, notOwnerId));

			assertEquals(ErrorCode.ACCESS_DENIED.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("루트 폴더를 제거하면 INVALID_DELETE_REQUEST 예외가 발생한다")
		void delete_root_folder_test() {
			Long folderId = folderTreeSetUp.getRootFolder().getId();

			CustomException customException = assertThrows(CustomException.class,
				() -> folderService.deleteFolder(folderId, userId));

			assertEquals(ErrorCode.INVALID_DELETE_REQUEST.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("폴더 삭제시 하위 파일 트리 제거와 용량 계산을 한다")
		void delete_folder_test() throws InterruptedException {
			Long deleteFolderId = folderTreeSetUp.getSubFolders().get(0).getId();
			long rootSize = folderTreeSetUp.getRootFolder().getSize();
			long minusSize = folderTreeSetUp.getSubFolders().get(0).getSize();

			folderService.deleteFolder(deleteFolderId, userId);

			Thread.sleep(5000);

			FolderMetadata findRootFolder = folderMetadataRepository.findById(folderTreeSetUp.getRootFolder().getId())
				.get();
			long findRootSize = findRootFolder.getSize();
			List<FileMetadata> deletedParentFileList = fileMetadataJpaRepository.findByParentFolderId(deleteFolderId,
				10);
			int fileListSize = deletedParentFileList.size();
			int deletedCnt = 0;
			for (FileMetadata f : deletedParentFileList) {
				if (f.isDeleted()) {
					deletedCnt++;
				}
			}

			assertEquals(rootSize - minusSize, findRootSize);
			assertEquals(fileListSize, deletedCnt);
		}

		@Test
		@DisplayName("폴더 hard delete를 하면 조회되지 않는다.")
		void hard_delete_test() {
			FolderMetadata targetFolder = folderTreeSetUp.getSubSubFolder();
			folderMetadataRepository.softDeleteById(targetFolder.getId());
			FolderMetadata softDeletedFolder = folderMetadataRepository.findById(targetFolder.getId()).get();
			softDeletedFolder.updateUpdatedAt(softDeletedFolder.getUpdatedAt().minusYears(1));
			folderMetadataRepository.save(softDeletedFolder);
			folderMetadataRepository.flush();

			// when
			folderService.doHardDelete();

			// then
			Optional<FolderMetadata> findFolder = folderMetadataRepository.findById(targetFolder.getId());

			assertTrue(findFolder.isEmpty());
		}

		@Test
		@DisplayName("고아 파일은 제거된다")
		void orphan_folder_find_test() throws InterruptedException {
			FolderMetadata childFolder = folderTreeSetUp.getSubSubFolder();
			FolderMetadata parentFolder = folderMetadataRepository.findParentByParentFolderId(childFolder.getId())
				.get();
			folderMetadataRepository.softDeleteById(parentFolder.getId());

			folderService.findOrphanFolderAndSoftDelete();
			Thread.sleep(5000);

			FolderMetadata folderMetadata = folderMetadataRepository.findById(childFolder.getId()).get();
			assertTrue(folderMetadata.isDeleted());
		}
	}

	@Nested
	@DisplayName("동시성 테스트")
	class ConcurrentFolderMoveTest {

		@Test
		@DisplayName("폴더 이동 동시성 테스트")
		void concurrent_folder_move_test() throws InterruptedException {
			FolderMetadata folderA = folderTreeSetUp.getSubSubFolder();
			FolderMetadata folderB = folderMetadataRepository.findById(folderA.getParentFolderId()).get();
			long aId = folderA.getId();
			long bId = folderB.getId();
			long targetFolderId = folderTreeSetUp.getSubFolders().get(5).getId();

			long aParent = folderA.getParentFolderId();
			long bParent = folderB.getParentFolderId();

			long rootId = folderTreeSetUp.getRootFolder().getId();
			long rootSize = folderTreeSetUp.getRootFolder().getSize();

			int threadCount = 10;
			ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
			CountDownLatch countDownLatch = new CountDownLatch(threadCount);
			AtomicInteger successCount = new AtomicInteger(0);
			AtomicInteger failedCount = new AtomicInteger(0);

			long startTime = System.currentTimeMillis();
			for (int i = 0; i < threadCount; i++) {
				executorService.submit(() -> {
					try {
						folderService.moveFolder(aId, new FolderMoveDto(userId, targetFolderId));
						successCount.incrementAndGet();
					} catch (Exception e) {
						System.out.println("id = " + aId + ", error occured = " + e.getMessage());
						failedCount.incrementAndGet();
					} finally {
						countDownLatch.countDown();
					}
				});
				executorService.submit(() -> {
					try {
						folderService.moveFolder(bId, new FolderMoveDto(userId, targetFolderId));
						successCount.incrementAndGet();
					} catch (Exception e) {
						System.out.println("id = " + bId + ", error occured = " + e.getMessage());
						failedCount.incrementAndGet();
					} finally {
						countDownLatch.countDown();
					}
				});
				executorService.submit(() -> {
					try {
						folderService.moveFolder(aId, new FolderMoveDto(userId, aParent));
						successCount.incrementAndGet();
					} catch (Exception e) {
						System.out.println("id = " + aId + ", error occured = " + e.getMessage());
						failedCount.incrementAndGet();
					} finally {
						countDownLatch.countDown();
					}
				});
				executorService.submit(() -> {
					try {
						folderService.moveFolder(bId, new FolderMoveDto(userId, bParent));
						successCount.incrementAndGet();
					} catch (Exception e) {
						System.out.println("id = " + bId + ", error occured = " + e.getMessage());
						failedCount.incrementAndGet();
					} finally {
						countDownLatch.countDown();
					}
				});
			}

			countDownLatch.await();
			Thread.sleep(10000);

			FolderMetadata findRootFolder = folderMetadataRepository.findById(rootId).get();

			assertEquals(rootSize, findRootFolder.getSize());

		}
	}
}
