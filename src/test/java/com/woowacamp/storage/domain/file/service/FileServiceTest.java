package com.woowacamp.storage.domain.file.service;

import java.util.List;

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
class FileServiceTest extends ContainerBaseConfig {

	@Autowired
	private FolderTreeSetUp folderTreeSetUp;
	@Autowired
	private FileMetadataJpaRepository fileMetadataJpaRepository;
	@Autowired
	private FolderMetadataJpaRepository folderMetadataJpaRepository;
	@Autowired
	private FileService fileService;

	@BeforeEach
	void setUp(){
		folderTreeSetUp.folderTreeSetUp();
	}

	@Nested
	@DisplayName("파일 이동 테스트")
	class FileMoveTest {

		@Test
		@DisplayName("이동할 폴더가 존재하지 않으면 FOLDER_NOT_FOUND 예외가 발생한다.")
		void if_target_folder_not_exists_throws_error(){
			long invalidTargetFolderId = 1000L;
			Long fileId = folderTreeSetUp.getFiles().get(0).getId();
			FileMoveDto fileMoveDto = new FileMoveDto(invalidTargetFolderId, folderTreeSetUp.getUserId());

			CustomException customException = assertThrows(CustomException.class,
				() -> fileService.moveFile(fileId, fileMoveDto));

			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("이동할 폴더에 권한이 없으면 ACCESS_DENIED 예외가 발생한다.")
		void if_target_folder_unauthorized_throws_error(){
			Long fileId = folderTreeSetUp.getFiles().get(0).getId();
			long invalidUserId = 1000L;
			List<FolderMetadata> subFolders = folderTreeSetUp.getSubFolders();
			FolderMetadata targetFolder =  subFolders.get(subFolders.size()-1);
			FileMoveDto fileMoveDto = new FileMoveDto(targetFolder.getId(), invalidUserId);

			CustomException customException = assertThrows(CustomException.class,
				() -> fileService.moveFile(fileId, fileMoveDto));

			assertEquals(ErrorCode.ACCESS_DENIED.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("이동할 폴더에 동일한 이름의 파일이 존재하면 예외가 발생한다.")
		void if_target_folder_has_same_file_name_throws_error(){
			List<FileMetadata> files = folderTreeSetUp.getFiles();
			List<FolderMetadata> subFolders = folderTreeSetUp.getSubFolders();
			FileMetadata sourceFile = files.get(0);
			FolderMetadata targetFolder =  subFolders.get(subFolders.size()-1);
			FileMoveDto fileMoveDto = new FileMoveDto(targetFolder.getId(), folderTreeSetUp.getUserId());
			FileMetadata sameNameFile = fileMetadataJpaRepository.save(FileMetadata.builder()
				.rootId(1L)
				.uuidFileName("test")
				.creatorId(sourceFile.getCreatorId())
				.fileType("file")
				.ownerId(sourceFile.getOwnerId())
				.createdAt(folderTreeSetUp.getNow().minusHours(1))
				.updatedAt(folderTreeSetUp.getNow())
				.fileSize(500L)
				.parentFolderId(targetFolder.getId())
				.uploadStatus(UploadStatus.SUCCESS)
				.uploadFileName(sourceFile.getUploadFileName())
				.sharingExpiredAt(folderTreeSetUp.getNow())
				.permissionType(PermissionType.WRITE)
				.build());
			fileMetadataJpaRepository.save(sameNameFile);

			CustomException customException = assertThrows(CustomException.class,
				() -> fileService.moveFile(sourceFile.getId(), fileMoveDto));

			assertEquals(ErrorCode.FILE_NAME_DUPLICATE.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("파일 이동 성공 테스트")
		void file_move_success_test() throws InterruptedException {
			List<FileMetadata> files = folderTreeSetUp.getFiles();
			List<FolderMetadata> subFolders = folderTreeSetUp.getSubFolders();
			FileMetadata sourceFile = files.get(0);
			long parentId = sourceFile.getParentFolderId();
			long targetIdx = 0;
			for (FolderMetadata f : subFolders) {
				if (parentId != f.getId()) {
					break;
				}
				targetIdx++;
			}
			long sourceFileSize = sourceFile.getFileSize();
			FolderMetadata sourceFolder = folderMetadataJpaRepository.findById(sourceFile.getParentFolderId()).get();
			long sourceSize = sourceFolder.getSize();
			FolderMetadata targetFolder =  subFolders.get((int)(targetIdx));
			long targetSize = targetFolder.getSize();
			FileMoveDto fileMoveDto = new FileMoveDto(targetFolder.getId(), folderTreeSetUp.getUserId());

			fileService.moveFile(sourceFile.getId(), fileMoveDto);

			Thread.sleep(5000);

			FolderMetadata findSourceFolder = folderMetadataJpaRepository.findById(parentId).get();
			FolderMetadata findTargetFolder = folderMetadataJpaRepository.findById(targetFolder.getId()).get();

			assertEquals(sourceSize - sourceFileSize, findSourceFolder.getSize());
			assertEquals(targetSize + sourceFileSize, findTargetFolder.getSize());
		}

	}

	@Nested
	@DisplayName("파일 삭제 테스트")
	class FileDeleteTest{

		@Test
		@DisplayName("파일 삭제 성공 테스트")
		void file_delete_success_test() throws InterruptedException {
			List<FileMetadata> files = folderTreeSetUp.getFiles();
			FileMetadata targetFile = files.get(0);
			FolderMetadata parentFolder = folderMetadataJpaRepository.findById(targetFile.getParentFolderId()).get();

			fileService.deleteFile(targetFile.getId(), folderTreeSetUp.getUserId());

			Thread.sleep(5000);

			FolderMetadata findFolder = folderMetadataJpaRepository.findById(targetFile.getParentFolderId()).get();

			assertEquals(parentFolder.getSize() - targetFile.getFileSize(), findFolder.getSize());
		}
	}
}