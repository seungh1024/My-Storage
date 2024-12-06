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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.global.constant.PermissionType;
import com.woowacamp.storage.global.constant.UploadStatus;

@SpringBootTest
@ActiveProfiles("test")
class FolderServiceTest {

	@Autowired
	private FolderMetadataJpaRepository folderMetadataRepository;

	@Autowired
	private FileMetadataJpaRepository fileMetadataJpaRepository;

	@Autowired
	private FolderService folderService;
	private FolderMetadata parentFolder;
	private List<FolderMetadata> subFolders;
	private List<FileMetadata> files;
	private LocalDateTime now;

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

		parentFolder = folderMetadataRepository.save(
			FolderMetadata.builder().createdAt(now).updatedAt(now).uploadFolderName("Parent Folder").sharingExpiredAt(now).permissionType(
				PermissionType.WRITE).build());

		subFolders = new ArrayList<>();
		files = new ArrayList<>();

		for (int i = 0; i < 7; i++) {
			FolderMetadata folderMetadata = folderMetadataRepository.save(FolderMetadata.builder()
				.rootId(1L)
				.creatorId(1L)
				.createdAt(now.minusDays(i))
				.updatedAt(now)
				.parentFolderId(parentFolder.getId())
				.uploadFolderName("Sub Folder " + (i + 1))
				.sharingExpiredAt(now)
				.permissionType(PermissionType.WRITE)
				.build());
			folderMetadata.addSize(1000 * (i + 1));
			subFolders.add(folderMetadata);
		}

		for (int i = 0; i < 7; i++) {
			for (int j = 0; j < 2; j++) {
				FileMetadata fileMetadata = fileMetadataJpaRepository.save(FileMetadata.builder()
					.rootId(1L)
					.uuidFileName("uuidFileName" + i+j)
					.creatorId(1L)
					.fileType("file")
					.ownerId(1L)
					.createdAt(now.minusHours(i))
					.updatedAt(now)
					.fileSize((long)(500 * (i + 1)))
					.parentFolderId(subFolders.get(i).getId())
					.uploadStatus(UploadStatus.SUCCESS)
					.uploadFileName("File " + i+j)
					.sharingExpiredAt(now)
					.permissionType(PermissionType.WRITE)
					.build());

				files.add(fileMetadata);
			}
		}
	}

	@Nested
	@DisplayName("test")
	class Test {

		@org.junit.jupiter.api.Test
		@DisplayName("test method")
		void test() {
			System.out.println("test");
		}
	}
}
