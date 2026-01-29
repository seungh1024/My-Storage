package com.woowacamp.storage.domain.folder.service;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.woowacamp.storage.config.IntegrationTestBase;
import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.entity.FolderJob;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderJobJpaRepository;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.utils.FolderJobStatus;
import com.woowacamp.storage.global.constant.PermissionType;
import com.woowacamp.storage.global.constant.UploadStatus;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FolderMoveProcessorIntegrationTest extends IntegrationTestBase {

	@Autowired
	private FolderMoveProcessor folderMoveProcessor;

	@Autowired
	private FolderMetadataJpaRepository folderMetadataJpaRepository;

	@Autowired
	private FileMetadataJpaRepository fileMetadataJpaRepository;

	@Autowired
	private FolderJobJpaRepository folderJobJpaRepository;

	@BeforeEach
	void setUp() {
		cleanup();
		folderTreeSetUp.setupFolderTree();
	}

	private FolderMetadata createAndSaveFolder(Long parentId, Long rootId, String idPath, String namePath) {
		FolderMetadata folder = FolderMetadata.builder()
			.createdAt(LocalDateTime.now())
			.updatedAt(LocalDateTime.now())
			.uploadFolderName("folder")
			.ownerId(1L)
			.creatorId(1L)
			.rootId(rootId)
			.parentFolderId(parentId)
			.size(1000L)
			.permissionType(PermissionType.WRITE)
			.sharingExpiredAt(LocalDateTime.now().plusDays(1))
			.idFullPath(idPath)
			.nameFullPath(namePath)
			.namePathLength(namePath.length())
			.build();
		return folderMetadataJpaRepository.save(folder);
	}

	private FileMetadata createAndSaveFile(Long parentFolderId, Long rootId, String idPath, String namePath) {
		FileMetadata file = FileMetadata.builder()
			.createdAt(LocalDateTime.now())
			.updatedAt(LocalDateTime.now())
			.uploadFileName("file.txt")
			.uuidFileName("uuid")
			.fileType("txt")
			.ownerId(1L)
			.creatorId(1L)
			.rootId(rootId)
			.parentFolderId(parentFolderId)
			.fileSize(100L)
			.uploadStatus(UploadStatus.SUCCESS)
			.permissionType(PermissionType.WRITE)
			.sharingExpiredAt(LocalDateTime.now().plusDays(1))
			.idFullPath(idPath)
			.nameFullPath(namePath)
			.namePathLength(namePath.length())
			.build();
		return fileMetadataJpaRepository.save(file);
	}

	private FolderJob createFolderJob(Long folderId, FolderJobStatus status) {
		FolderJob job = FolderJob.builder()
			.id(folderId)
			.currentParentId(folderId)
			.lastFolderId(null)
			.lastFileId(null)
			.parentStack("[]")
			.updatedAt(LocalDateTime.now())
			.status(status)
			.build();
		return folderJobJpaRepository.save(job);
	}

	@Nested
	@DisplayName("기본 경로 업데이트")
	class BasicPathUpdateTest {

		@Test
		@DisplayName("단일 폴더의 경로를 업데이트하고 Job을 COMPLETED로 표시한다")
		void processMove_CompletesJob_SetsStatusCompleted() {
			// given
			FolderMetadata root = createAndSaveFolder(null, 1L, "/1/", "/root/");
			String folderName = root.getUploadFolderName();

			// 경로 변경
			root.updateIdFullPath("/");
			root.updateNameFullPath("/newroot/");
			root.updateNamePathLength("/newroot/".length());
			folderMetadataJpaRepository.save(root);

			createFolderJob(root.getId(), FolderJobStatus.WAITING);

			// when
			folderMoveProcessor.processMove(root.getId());

			// then
			FolderJob job = folderJobJpaRepository.findById(root.getId()).orElseThrow();
			assertThat(job.getStatus()).isEqualTo(FolderJobStatus.COMPLETED);

			FolderMetadata updated = folderMetadataJpaRepository.findById(root.getId()).orElseThrow();
			assertThat(updated.getNameFullPath()).isEqualTo("/newroot/"+folderName+"/");
			assertThat(updated.isMoving()).isFalse();
		}

		@Test
		@DisplayName("1개의 자식 폴더를 업데이트한다")
		void processMove_OneChild_UpdatesPath() {
			// given
			FolderMetadata root = createAndSaveFolder(null, 1L, "/1/", "/root/");
			FolderMetadata child = createAndSaveFolder(root.getId(), 1L, "/1/2/", "/root/child/");

			// Root 경로 변경
			root.updateIdFullPath("/");
			root.updateNameFullPath("/newroot/");
			root.updateNamePathLength("/newroot/".length());
			folderMetadataJpaRepository.save(root);

			createFolderJob(root.getId(), FolderJobStatus.WAITING);

			// when
			folderMoveProcessor.processMove(root.getId());

			// then
			FolderMetadata updatedChild = folderMetadataJpaRepository.findById(child.getId()).orElseThrow();
			assertThat(updatedChild.getNameFullPath()).startsWith("/newroot/");
			assertThat(updatedChild.getIdFullPath()).startsWith("/");
		}
	}

	@Nested
	@DisplayName("다단계 폴더 업데이트")
	class MultiDepthMoveTest {

		@Test
		@DisplayName("3단계 깊이의 폴더 트리를 올바르게 업데이트한다")
		void processMove_ThreeDepths_UpdatesAllCorrectly() {
			// given
			FolderMetadata root = createAndSaveFolder(null, 1L, "/1/", "/root/");
			FolderMetadata child1 = createAndSaveFolder(root.getId(), 1L, "/1/2/", "/root/child1/");
			FolderMetadata child2 = createAndSaveFolder(root.getId(), 1L, "/1/3/", "/root/child2/");
			FolderMetadata grandchild1 = createAndSaveFolder(child1.getId(), 1L, "/1/2/4/",
				"/root/child1/grandchild1/");

			// Root 이동
			root.updateIdFullPath("/");
			root.updateNameFullPath("/newroot/");
			root.updateNamePathLength("/newroot/".length());
			folderMetadataJpaRepository.save(root);

			createFolderJob(root.getId(), FolderJobStatus.WAITING);

			// when
			folderMoveProcessor.processMove(root.getId());

			// then
			FolderMetadata updatedChild1 = folderMetadataJpaRepository.findById(child1.getId()).orElseThrow();
			FolderMetadata updatedChild2 = folderMetadataJpaRepository.findById(child2.getId()).orElseThrow();
			FolderMetadata updatedGrandchild1 = folderMetadataJpaRepository.findById(grandchild1.getId()).orElseThrow();

			assertThat(updatedChild1.getNameFullPath()).startsWith("/newroot/");
			assertThat(updatedChild2.getNameFullPath()).startsWith("/newroot/");
			assertThat(updatedGrandchild1.getNameFullPath()).startsWith("/newroot/");

			FolderJob job = folderJobJpaRepository.findById(root.getId()).orElseThrow();
			assertThat(job.getStatus()).isEqualTo(FolderJobStatus.COMPLETED);
		}

		@Test
		@DisplayName("파일이 포함된 폴더 트리를 올바르게 업데이트한다")
		void processMove_WithFiles_UpdatesAllCorrectly() {
			// given
			FolderMetadata root = createAndSaveFolder(null, 1L, "/1/", "/root/");
			FolderMetadata child = createAndSaveFolder(root.getId(), 1L, "/1/2/", "/root/child/");

			FileMetadata file1 = createAndSaveFile(root.getId(), 1L, "/1/", "/root/file1.txt");
			FileMetadata file2 = createAndSaveFile(child.getId(), 1L, "/1/2/", "/root/child/file2.txt");

			// Root 이동
			root.updateIdFullPath("/");
			root.updateNameFullPath("/newroot/");
			root.updateNamePathLength("/newroot/".length());
			folderMetadataJpaRepository.save(root);

			createFolderJob(root.getId(), FolderJobStatus.WAITING);

			// when
			folderMoveProcessor.processMove(root.getId());

			// then
			FileMetadata updatedFile1 = fileMetadataJpaRepository.findById(file1.getId()).orElseThrow();
			FileMetadata updatedFile2 = fileMetadataJpaRepository.findById(file2.getId()).orElseThrow();

			assertThat(updatedFile1.getNameFullPath()).startsWith("/newroot/");
			assertThat(updatedFile2.getNameFullPath()).startsWith("/newroot/");
		}
	}

	@Nested
	@DisplayName("대용량 배치 처리")
	class LargeScaleMoveTest {

		@Test
		@DisplayName("100개의 하위 폴더를 배치로 처리한다")
		void processMove_100Children_ProcessesInBatches() {
			// given
			FolderMetadata root = createAndSaveFolder(null, 1L, "/1/", "/root/");

			// 100개의 자식 폴더 생성
			for (int i = 2; i <= 101; i++) {
				createAndSaveFolder(root.getId(), 1L, "/1/" + i + "/", "/root/child" + i + "/");
			}

			// Root 이동
			root = folderMetadataJpaRepository.findById(root.getId()).orElseThrow();
			root.updateIdFullPath("/");
			root.updateNameFullPath("/newroot/");
			root.updateNamePathLength("/newroot/".length());
			folderMetadataJpaRepository.save(root);

			createFolderJob(root.getId(), FolderJobStatus.WAITING);

			// when
			folderMoveProcessor.processMove(root.getId());

			// then
			FolderJob job = folderJobJpaRepository.findById(root.getId()).orElseThrow();
			assertThat(job.getStatus()).isEqualTo(FolderJobStatus.COMPLETED);

			// 샘플 체크 (findAll로 전체 조회 후 필터링)
			FolderMetadata finalRoot = root;
			FolderMetadata sample = folderMetadataJpaRepository.findAll().stream()
				.filter(f -> f.getParentFolderId() != null && f.getParentFolderId().equals(finalRoot.getId()))
				.findFirst()
				.orElseThrow();

			assertThat(sample.getNameFullPath()).startsWith("/newroot/");
		}
	}

	@Nested
	@DisplayName("Job 상태 관리")
	class JobStatusManagementTest {

		@Test
		@DisplayName("Job이 없으면 processMove가 실패한다")
		void processMove_NoJob_ThrowsException() {
			// given
			FolderMetadata root = createAndSaveFolder(null, 1L, "/1/", "/root/");

			// Job 생성하지 않음

			// when & then
			assertThatThrownBy(() -> folderMoveProcessor.processMove(root.getId()))
				.hasMessageContaining("폴더를 찾을 수 없습니다.");
		}
	}
}