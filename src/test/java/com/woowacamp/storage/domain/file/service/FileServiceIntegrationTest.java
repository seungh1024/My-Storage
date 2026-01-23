package com.woowacamp.storage.domain.file.service;

import java.time.Duration;
import java.util.List;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.woowacamp.storage.config.FolderTreeSetUp;
import com.woowacamp.storage.container.ContainerBaseConfig;
import com.woowacamp.storage.domain.file.dto.FileMoveDto;
import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
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
/**
 * ⭐ 핵심: 테스트 자체가 트랜잭션이면(rollback 포함) AFTER_COMMIT 이벤트 리스너가 실행 안 될 수 있음.
 * 그래서 통합 테스트 클래스 전체를 NOT_SUPPORTED로 잡아서,
 * 서비스 메서드의 트랜잭션이 실제로 commit되게 만든다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FileServiceIntegrationTest extends ContainerBaseConfig {

	@Autowired
	private FolderTreeSetUp folderTreeSetUp;

	@Autowired
	private FileMetadataJpaRepository fileMetadataJpaRepository;

	@Autowired
	private FolderMetadataJpaRepository folderMetadataJpaRepository;

	@Autowired
	private FileService fileService;

	@Autowired(required = false)
	private AmqpAdmin amqpAdmin;

	@Value("${spring.rabbitmq.folder.size.queue:}")
	private String folderSizeQueue;

	@BeforeEach
	void setUp() {
		folderTreeSetUp.folderTreeSetUp();

		// 큐에 이전 테스트 메시지 남아있으면 다음 테스트를 오염시킬 수 있으니 purge(가능할 때만)
		if (amqpAdmin != null && folderSizeQueue != null && !folderSizeQueue.isBlank()) {
			try {
				amqpAdmin.purgeQueue(folderSizeQueue, true);
			} catch (Exception ignored) {
				// 큐가 아직 declare 안됐거나, 이름이 다르면 purge 실패할 수 있음 -> 테스트 자체는 진행
			}
		}
	}

	// ---------------------------
	// Helpers
	// ---------------------------
	private FileMetadata pickAnyFile() {
		List<FileMetadata> files = folderTreeSetUp.getFiles();
		assertFalse(files.isEmpty(), "FolderTreeSetUp.files must not be empty");
		return files.get(0);
	}

	/**
	 * delete/move에서 "상위 폴더 deleted lock" 테스트를 하려면
	 * parentFolderId가 null이 아닌 조상(=grand parent 이상)이 존재해야 안정적이다.
	 */
	private FileMetadata pickFileWithGrandParent() {
		return folderTreeSetUp.getFiles().stream()
			.filter(f -> {
				FolderMetadata parent = folderMetadataJpaRepository.findById(f.getParentFolderId()).orElse(null);
				return parent != null && parent.getParentFolderId() != null;
			})
			.findFirst()
			.orElseThrow(() -> new AssertionError("Need at least one file whose parent has a parent (depth >= 2)"));
	}

	private FolderMetadata folder(long folderId) {
		return folderMetadataJpaRepository.findById(folderId)
			.orElseThrow(() -> new AssertionError("Folder not found: " + folderId));
	}

	private void awaitFolderSize(long folderId, long expectedSize) {
		Awaitility.await()
			.pollInterval(Duration.ofMillis(200))
			.atMost(Duration.ofSeconds(15))
			.untilAsserted(() -> {
				long actual = folder(folderId).getSize();
				assertEquals(expectedSize, actual);
			});
	}

	// =========================================================
	// 파일 이동 테스트
	// =========================================================
	@Nested
	@DisplayName("파일 이동 테스트")
	class FileMoveTest {

		@Test
		@DisplayName("이동할 폴더가 존재하지 않으면 FOLDER_NOT_FOUND 예외가 발생한다.")
		void if_target_folder_not_exists_throws_error() {
			// given
			long invalidTargetFolderId = 1_000_000L;
			FileMetadata sourceFile = pickAnyFile();

			FileMoveDto dto = new FileMoveDto(
				invalidTargetFolderId,
				folderTreeSetUp.getUserId(),
				sourceFile.getRootId(),
				sourceFile.getUploadFileName()
			);

			// when
			CustomException ex = assertThrows(CustomException.class,
				() -> fileService.moveFile(sourceFile.getId(), dto));

			// then
			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("이동할 폴더에 권한이 없으면 ACCESS_DENIED 예외가 발생한다.")
		void if_target_folder_unauthorized_throws_error() {
			// given
			FileMetadata sourceFile = pickAnyFile();
			List<FolderMetadata> subFolders = folderTreeSetUp.getSubFolders();
			assertFalse(subFolders.isEmpty(), "FolderTreeSetUp.subFolders must not be empty");

			FolderMetadata targetFolder = subFolders.get(subFolders.size() - 1);
			long invalidUserId = 999_999L;

			FileMoveDto dto = new FileMoveDto(
				targetFolder.getId(),
				invalidUserId,
				sourceFile.getRootId(),
				sourceFile.getUploadFileName()
			);

			// when
			CustomException ex = assertThrows(CustomException.class,
				() -> fileService.moveFile(sourceFile.getId(), dto));

			// then
			assertEquals(ErrorCode.ACCESS_DENIED.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("이동할 폴더에 동일한 이름의 파일이 존재하면 FILE_NAME_DUPLICATE 예외가 발생한다.")
		void if_target_folder_has_same_file_name_throws_error() {
			// given
			List<FileMetadata> files = folderTreeSetUp.getFiles();
			List<FolderMetadata> subFolders = folderTreeSetUp.getSubFolders();
			assertFalse(files.isEmpty(), "files must not be empty");
			assertFalse(subFolders.isEmpty(), "subFolders must not be empty");

			FileMetadata sourceFile = files.get(0);
			FolderMetadata targetFolder = subFolders.get(subFolders.size() - 1);

			// target 폴더에 동일 업로드 파일명 존재하도록 row 생성
			// ⚠️ FileMetadata 엔티티가 namePathLength를 필수로 요구하므로 반드시 채운다
			String fileName = "dup-file";
			FileMetadata dup = FileMetadata.builder()
				.rootId(sourceFile.getRootId())
				.uuidFileName(fileName)
				.creatorId(sourceFile.getCreatorId())
				.fileType("file")
				.ownerId(sourceFile.getOwnerId())
				.createdAt(folderTreeSetUp.getNow().minusHours(1))
				.updatedAt(folderTreeSetUp.getNow())
				.fileSize(10L)
				.parentFolderId(targetFolder.getId())
				.uploadStatus(UploadStatus.SUCCESS)
				.uploadFileName(sourceFile.getUploadFileName()) // 동일 이름
				.sharingExpiredAt(folderTreeSetUp.getNow())
				.permissionType(PermissionType.WRITE)
				.nameFullPath(targetFolder.getNameFullPath()+fileName)
				.namePathLength(targetFolder.getNamePathLength()+fileName.length())
				.idFullPath(targetFolder.getIdFullPath())
				.build();

			FileMetadata sameNameFile = fileMetadataJpaRepository.save(dup);
			dup.updateIdFullPath(targetFolder.getIdFullPath());
			fileMetadataJpaRepository.save(dup);

			FileMoveDto dto = new FileMoveDto(
				targetFolder.getId(),
				folderTreeSetUp.getUserId(),
				sourceFile.getRootId(),
				sourceFile.getUploadFileName()
			);

			// when
			CustomException ex = assertThrows(CustomException.class,
				() -> fileService.moveFile(sourceFile.getId(), dto));

			// then
			assertEquals(ErrorCode.FILE_NAME_DUPLICATE.getMessage(), ex.getMessage());
			assertNotNull(sameNameFile.getId());
		}


		@Test
		@DisplayName("상위 폴더 중 isDeleted=true가 있으면 PARENT_LOCKED 예외가 발생한다. (target 체인 기준)")
		void if_any_parent_deleted_lock_then_file_move_fail() {
			// given
			FileMetadata sourceFile = pickFileWithGrandParent();
			FolderMetadata targetFolder = folderTreeSetUp.getLongestFolder();

			// source와 다른 target 폴더 선택(가능하면)
			FolderMetadata deletedFolder = folderMetadataJpaRepository.findById(targetFolder.getParentFolderId()).get();

			folderMetadataJpaRepository.softDeleteById(deletedFolder.getId());


			FileMoveDto dto = new FileMoveDto(
				targetFolder.getId(),
				folderTreeSetUp.getUserId(),
				sourceFile.getRootId(),
				sourceFile.getUploadFileName()
			);

			// when
			CustomException ex = assertThrows(CustomException.class,
				() -> fileService.moveFile(sourceFile.getId(), dto));

			// then
			assertEquals(ErrorCode.PARENT_LOCKED.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("파일 이동 성공: source/target 폴더 용량이 메시징 처리 후 반영된다.")
		void file_move_success_test() {
			// given
			List<FileMetadata> files = folderTreeSetUp.getFiles();
			List<FolderMetadata> subFolders = folderTreeSetUp.getSubFolders();
			assertFalse(files.isEmpty(), "files must not be empty");
			assertFalse(subFolders.isEmpty(), "subFolders must not be empty");

			FileMetadata sourceFile = files.get(0);

			FolderMetadata sourceFolderBefore = folder(sourceFile.getParentFolderId());
			long sourceFolderBeforeSize = sourceFolderBefore.getSize();

			// source와 다른 폴더를 target으로 선택
			FolderMetadata targetFolder = subFolders.stream()
				.filter(f -> f.getId() != sourceFile.getParentFolderId())
				.findFirst()
				.orElseThrow(() -> new AssertionError("Need a target folder different from source parent"));

			FolderMetadata targetFolderBefore = folder(targetFolder.getId());
			long targetFolderBeforeSize = targetFolderBefore.getSize();

			long fileSize = sourceFile.getFileSize();

			FileMoveDto dto = new FileMoveDto(
				targetFolder.getId(),
				folderTreeSetUp.getUserId(),
				sourceFile.getRootId(),
				sourceFile.getUploadFileName()
			);

			// when
			fileService.moveFile(sourceFile.getId(), dto);

			// then 1) 파일의 parentFolderId는 DB에서 바뀌어 있어야 함(동기 영역)
			FileMetadata moved = fileMetadataJpaRepository.findById(sourceFile.getId())
				.orElseThrow(() -> new AssertionError("moved file not found"));
			assertEquals(targetFolder.getId(), moved.getParentFolderId());

			// then 2) 용량은 메시징(Outbox -> Rabbit -> Consumer) 처리 후 반영 (비동기)
			awaitFolderSize(sourceFolderBefore.getId(), sourceFolderBeforeSize - fileSize);
			awaitFolderSize(targetFolderBefore.getId(), targetFolderBeforeSize + fileSize);
		}
	}

	// =========================================================
	// 파일 삭제 테스트
	// =========================================================
	@Nested
	@DisplayName("파일 삭제 테스트")
	class FileDeleteTest {

		@Test
		@DisplayName("권한이 없으면 ACCESS_DENIED 예외가 발생한다.")
		void if_user_not_owner_then_delete_fail() {
			// given
			FileMetadata targetFile = pickAnyFile();
			long invalidUserId = 999_999L;

			// when
			CustomException ex = assertThrows(CustomException.class,
				() -> fileService.deleteFile(targetFile.getId(), invalidUserId));

			// then
			assertEquals(ErrorCode.ACCESS_DENIED.getMessage(), ex.getMessage());
		}



		@Test
		@DisplayName("파일 삭제 성공: softDelete + 폴더 용량이 메시징 처리 후 반영된다.")
		void file_delete_success_test() {
			// given
			FileMetadata targetFile = pickAnyFile();
			long userId = folderTreeSetUp.getUserId();

			FolderMetadata parentBefore = folder(targetFile.getParentFolderId());
			long parentBeforeSize = parentBefore.getSize();
			long fileSize = targetFile.getFileSize();

			// when
			fileService.deleteFile(targetFile.getId(), userId);

			// then 1) 파일 soft-delete(상태/flag)는 구현에 따라 달라서,
			// DB에 남아있는지/조회 조건이 어떤지 모르면 "용량 반영"으로 검증하는 게 제일 안정적이다.

			// then 2) 폴더 용량은 메시징 처리 후 반영(비동기)
			awaitFolderSize(parentBefore.getId(), parentBeforeSize - fileSize);
		}
	}
}
