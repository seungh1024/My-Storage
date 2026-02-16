package com.woowacamp.storage.domain.file.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.config.IntegrationTestBase;
import com.woowacamp.storage.domain.file.dto.FileMoveDto;
import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderJobJpaRepository;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.global.error.CustomException;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FileServiceIntegrationTest extends IntegrationTestBase {

	@Autowired
	private FileMetadataJpaRepository fileMetadataJpaRepository;

	@Autowired
	private FolderMetadataJpaRepository folderMetadataJpaRepository;

	@Autowired
	private FolderJobJpaRepository folderJobJpaRepository;

	@Autowired
	private FileService fileService;

	@BeforeEach
	void setUp() {
		cleanup();
		folderTreeSetUp.setupFolderTree();
	}

	private FolderMetadata folder(long folderId) {
		return folderMetadataJpaRepository.findById(folderId)
			.orElseThrow(() -> new AssertionError("Folder not found: " + folderId));
	}

	@Nested
	@DisplayName("파일 이동 테스트")
	class FileMoveTest {

		@Test
		@DisplayName("파일 이동 성공: source/target 폴더 용량이 동기 처리로 즉시 반영된다")
		void file_move_success_test() {
			// given
			List<FileMetadata> files = folderTreeSetUp.getFiles();
			List<FolderMetadata> subFolders = folderTreeSetUp.getSubFolders();
			assertFalse(files.isEmpty(), "files must not be empty");
			assertFalse(subFolders.isEmpty(), "subFolders must not be empty");

			// files.get(0)은 subFolders.get(0)에 속함
			FileMetadata sourceFile = files.get(0);

			// source의 부모 폴더
			FolderMetadata sourceFolderBefore = folder(sourceFile.getParentFolderId());
			long sourceFolderBeforeSize = sourceFolderBefore.getSize();

			// source와 다른 폴더를 target으로 선택 (subFolders.get(1) 사용)
			// files.get(0)은 subFolders.get(0)의 파일이므로, subFolders.get(1)로 이동
			FolderMetadata targetFolder = subFolders.get(1);

			// 검증: source 부모와 target이 다른지 확인
			assertNotEquals(sourceFile.getParentFolderId(), targetFolder.getId(),
				"Source parent and target must be different");

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

			// then 2) 용량은 동기 배치 업데이트로 즉시 반영
			assertEquals(sourceFolderBeforeSize - fileSize, folder(sourceFolderBefore.getId()).getSize());
			assertEquals(targetFolderBeforeSize + fileSize, folder(targetFolderBefore.getId()).getSize());
		}

		@Test
		@DisplayName("실패: 존재하지 않는 target 폴더")
		void moveFile_TargetNotFound_ThrowsException() {
			// given
			FileMetadata sourceFile = folderTreeSetUp.getFiles().get(0);
			long nonExistentFolderId = 999999L;

			FileMoveDto dto = new FileMoveDto(
				nonExistentFolderId,
				folderTreeSetUp.getUserId(),
				sourceFile.getRootId(),
				sourceFile.getUploadFileName()
			);

			// when & then
			long sourceFileId = sourceFile.getId();
			assertThatThrownBy(() -> fileService.moveFile(sourceFileId, dto))
				.isInstanceOf(CustomException.class);
		}
	}
}
