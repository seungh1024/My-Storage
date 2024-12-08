package com.woowacamp.storage.domain.folder.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
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
import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.dto.request.FolderMoveDto;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.global.error.CustomException;
import com.woowacamp.storage.global.error.ErrorCode;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FolderServiceTest {

	@Autowired
	private FolderMetadataJpaRepository folderMetadataRepository;

	@Autowired
	private FileMetadataJpaRepository fileMetadataJpaRepository;

	@Autowired
	private FolderTreeSetUp folderTreeSetUp;

	@Autowired
	private FolderService folderService;
	private long userId = 1L;

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
			folderService.moveFolder(sourceId, dto);
			Thread.sleep(1000);

			FolderMetadata targetFolder = folderMetadataRepository.findById(targetId).get();
			FolderMetadata sourceFolder = folderMetadataRepository.findById(sourceId).get();

			assertEquals(moveSize + targetSize, targetFolder.getSize());
			assertEquals(targetId, sourceFolder.getParentFolderId());
		}

		@Test
		@DisplayName("자신의 하위 폴더 트리로 이동하면 FOLDER_MOVE_NOT_AVAILABLE 예외를 발생한다.")
		void folder_move_to_child_folder_test(){
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
			List<FolderMetadata> byParentFolderId = folderMetadataRepository.findByParentFolderId(deleteFolderId, 10);
			byParentFolderId.forEach(f -> System.out.println(f.getId()));
			List<FileMetadata> deletedParentFileList = fileMetadataJpaRepository.findByParentFolderId(deleteFolderId,
				10);
			List<FileMetadata> deletedSubParentFileList = fileMetadataJpaRepository.findByParentFolderId(
				folderTreeSetUp.getSubSubFolder().getId(),
				0);

			assertEquals(rootSize - minusSize, findRootSize);
			assertTrue(deletedParentFileList.isEmpty());
			assertTrue(deletedParentFileList.isEmpty());
			assertTrue(deletedSubParentFileList.isEmpty());
		}

		@Test
		@DisplayName("폴더 hard delete를 하면 조회되지 않는다.")
		void hard_delete_test(){
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
			FolderMetadata parentFolder = folderMetadataRepository.findParentByParentFolderId(childFolder.getId()).get();
			folderMetadataRepository.softDeleteById(parentFolder.getId());

			folderService.findOrphanFolderAndSoftDelete();
			Thread.sleep(5000);

			FolderMetadata folderMetadata = folderMetadataRepository.findById(childFolder.getId()).get();
			assertTrue(folderMetadata.isDeleted());
		}
	}
}
