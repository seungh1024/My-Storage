package com.woowacamp.storage.domain.folder.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.dto.request.FolderMoveDto;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.global.constant.PermissionType;
import com.woowacamp.storage.global.constant.UploadStatus;
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
	private FolderService folderService;
	private FolderMetadata rootFolder;
	private List<FolderMetadata> subFolders;
	private List<FileMetadata> files;
	private LocalDateTime now;
	private long userId = 1L;
	private FolderMetadata subSubFolder;

	@AfterEach
	void afterEach() {
		fileMetadataJpaRepository.deleteAllInBatch();
		folderMetadataRepository.deleteAllInBatch();
	}

	@BeforeEach
	void setUp() {
		now = LocalDateTime.now();
		fileMetadataJpaRepository.deleteAll();
		folderMetadataRepository.deleteAll();

		rootFolder = folderMetadataRepository.save(
			FolderMetadata.builder()
				.createdAt(now)
				.updatedAt(now)
				.uploadFolderName("Parent Folder")
				.sharingExpiredAt(now)
				.ownerId(userId)
				.permissionType(
					PermissionType.WRITE)
				.build());

		subFolders = new ArrayList<>();
		files = new ArrayList<>();

		for (int i = 0; i < 7; i++) {
			FolderMetadata folderMetadata = folderMetadataRepository.save(FolderMetadata.builder()
				.rootId(1L)
				.creatorId(userId)
				.createdAt(now.minusDays(i))
				.updatedAt(now)
				.parentFolderId(rootFolder.getId())
				.uploadFolderName("Sub Folder " + (i + 1))
				.sharingExpiredAt(now)
				.size(1000)
				.ownerId(userId)
				.permissionType(PermissionType.WRITE)
				.build());
			folderMetadataRepository.save(folderMetadata);
			subFolders.add(folderMetadata);
		}
		subSubFolder = folderMetadataRepository.save(FolderMetadata.builder()
			.rootId(1L)
			.creatorId(userId)
			.createdAt(now.minusDays(1))
			.updatedAt(now)
			.parentFolderId(subFolders.get(0).getId())
			.uploadFolderName("Sub Folder's Sub Folder")
			.sharingExpiredAt(now)
			.size(1000)
			.ownerId(userId)
			.permissionType(PermissionType.WRITE)
			.build());

		folderMetadataRepository.save(subSubFolder);

		for (int i = 0; i < 7; i++) {
			for (int j = 0; j < 2; j++) {
				FileMetadata fileMetadata = fileMetadataJpaRepository.save(FileMetadata.builder()
					.rootId(1L)
					.uuidFileName("uuidFileName" + i + j)
					.creatorId(userId)
					.fileType("file")
					.ownerId(userId)
					.createdAt(now.minusHours(i))
					.updatedAt(now)
					.fileSize(500L)
					.parentFolderId(subFolders.get(i).getId())
					.uploadStatus(UploadStatus.SUCCESS)
					.uploadFileName("File " + i + j)
					.sharingExpiredAt(now)
					.permissionType(PermissionType.WRITE)
					.build());
				fileMetadataJpaRepository.save(fileMetadata);
				files.add(fileMetadata);
			}
		}
		for (int i = 0; i < 2; i++) {
			FileMetadata fileMetadata = fileMetadataJpaRepository.save(FileMetadata.builder()
				.rootId(1L)
				.uuidFileName("uuidFileName Sub " + i)
				.creatorId(userId)
				.fileType("file")
				.ownerId(userId)
				.createdAt(now.minusHours(i))
				.updatedAt(now)
				.fileSize(500L)
				.parentFolderId(subSubFolder.getId())
				.uploadStatus(UploadStatus.SUCCESS)
				.uploadFileName("File Sub " + i)
				.sharingExpiredAt(now)
				.permissionType(PermissionType.WRITE)
				.build());
			fileMetadataJpaRepository.save(fileMetadata);
		}

		FolderMetadata sub1 = subFolders.get(0);
		sub1.addSize(1000);
		folderMetadataRepository.save(sub1);
	}

	@Nested
	@DisplayName("폴더 이동 테스트")
	class FolderMoveTest {

		@Test
		@DisplayName("source folder가 없는 경우 FOLDER_NOT_FOUND 예외를 던진다.")
		void source_id_not_exist_test() {
			long sourceId = 1000L;
			long targetId = subFolders.get(1).getId();
			FolderMoveDto dto = new FolderMoveDto(userId, targetId);
			CustomException customException = assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, dto));

			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("target folder가 없는 경우 FOLDER_NOT_FOUND 예외를 던진다.")
		void target_id_not_exist_test() {
			long sourceId = subFolders.get(1).getId();
			long targetId = 1000L;
			FolderMoveDto dto = new FolderMoveDto(userId, targetId);
			CustomException customException = assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, dto));

			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("source id와 target id가 동일한 경우 FOLDER_MOVE_NOT_AVAILABLE 예외를 던진다.")
		void source_id_equals_target_id_test() {
			long sourceId = subFolders.get(1).getId();
			long targetId = subFolders.get(1).getId();
			FolderMoveDto dto = new FolderMoveDto(userId, targetId);
			CustomException customException = assertThrows(CustomException.class,
				() -> folderService.moveFolder(sourceId, dto));

			assertEquals(ErrorCode.FOLDER_MOVE_NOT_AVAILABLE.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("폴더 이동 성공 테스트")
		void folder_move_success_test() throws InterruptedException {
			long sourceId = subFolders.get(1).getId();
			long targetId = subFolders.get(2).getId();
			FolderMoveDto dto = new FolderMoveDto(userId, targetId);

			long moveSize = subFolders.get(1).getSize();
			long targetSize = subFolders.get(2).getSize();
			folderService.moveFolder(sourceId, dto);
			Thread.sleep(1000);

			FolderMetadata targetFolder = folderMetadataRepository.findById(targetId).get();
			FolderMetadata sourceFolder = folderMetadataRepository.findById(sourceId).get();

			assertEquals(moveSize + targetSize, targetFolder.getSize());
			assertEquals(targetId, sourceFolder.getParentFolderId());
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
			Long folderId = rootFolder.getId();
			long notOwnerId = 3L;

			CustomException customException = assertThrows(CustomException.class,
				() -> folderService.deleteFolder(folderId, notOwnerId));

			assertEquals(ErrorCode.ACCESS_DENIED.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("루트 폴더를 제거하면 INVALID_DELETE_REQUEST 예외가 발생한다")
		void delete_root_folder_test() {
			Long folderId = rootFolder.getId();

			CustomException customException = assertThrows(CustomException.class,
				() -> folderService.deleteFolder(folderId, userId));

			assertEquals(ErrorCode.INVALID_DELETE_REQUEST.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("폴더 삭제시 하위 파일 트리 제거와 용량 계산을 한다")
		void delete_folder_test() throws InterruptedException {
			Long deleteFolderId = subFolders.get(0).getId();
			long rootSize = rootFolder.getSize();
			long minusSize = subFolders.get(0).getSize();

			folderService.deleteFolder(deleteFolderId, userId);

			Thread.sleep(5000);

			FolderMetadata findRootFolder = folderMetadataRepository.findById(rootFolder.getId()).get();
			long findRootSize = findRootFolder.getSize();
			List<FolderMetadata> byParentFolderId = folderMetadataRepository.findByParentFolderId(deleteFolderId, 10);
			byParentFolderId.forEach(f -> System.out.println(f.getId()));
			List<FileMetadata> deletedParentFileList = fileMetadataJpaRepository.findByParentFolderId(deleteFolderId,
				10);
			List<FileMetadata> deletedSubParentFileList = fileMetadataJpaRepository.findByParentFolderId(
				subSubFolder.getId(),
				0);

			assertEquals(rootSize - minusSize, findRootSize);
			assertTrue(deletedParentFileList.isEmpty());
			assertTrue(deletedParentFileList.isEmpty());
			assertTrue(deletedSubParentFileList.isEmpty());
		}
	}
}
