package com.woowacamp.storage.domain.folder.service;

import static org.junit.jupiter.api.Assertions.*;

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
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataRepository;
import com.woowacamp.storage.domain.folder.dto.CursorType;
import com.woowacamp.storage.domain.folder.dto.FolderContentsDto;
import com.woowacamp.storage.domain.folder.dto.FolderContentsSortField;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.global.constant.UploadStatus;

@SpringBootTest
@ActiveProfiles("test")
class FolderServiceTest {

	@Autowired
	private FolderMetadataJpaRepository folderMetadataRepository;

	@Autowired
	private FileMetadataRepository fileMetadataRepository;

	@Autowired
	private FolderService folderService;
	private FolderMetadata parentFolder;
	private List<FolderMetadata> subFolders;
	private List<FileMetadata> files;
	private LocalDateTime now;

	@AfterEach
	void afterEach() {
		fileMetadataRepository.deleteAllInBatch();
		folderMetadataRepository.deleteAllInBatch();
	}

	@BeforeEach
	void setUp() {
		now = LocalDateTime.now();
		fileMetadataRepository.deleteAll();
		folderMetadataRepository.deleteAll();

		parentFolder = folderMetadataRepository.save(
			FolderMetadata.builder().createdAt(now).updatedAt(now).uploadFolderName("Parent Folder").build());

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
				.build());
			folderMetadata.addSize(1000 * (i + 1));
			subFolders.add(folderMetadata);
		}

		for (int i = 0; i < 7; i++) {
			FileMetadata fileMetadata = fileMetadataRepository.save(FileMetadata.builder()
				.rootId(1L)
				.uuidFileName("uuidFileName" + i)
				.creatorId(1L)
				.fileType("file")
				.ownerId(1L)
				.createdAt(now.minusHours(i))
				.updatedAt(now)
				.fileSize((long)(500 * (i + 1)))
				.parentFolderId(parentFolder.getId())
				.uploadStatus(UploadStatus.SUCCESS)
				.uploadFileName("File " + (i + 1))
				.build());

			files.add(fileMetadata);
		}
	}

	@Nested
	@DisplayName("getFolderContents 메소드는")
	class GetFolderContentsTest {

		@Nested
		@DisplayName("파일 커서 타입 조건에서")
		class WithFileCursorType {

			@Test
			@DisplayName("생성 시간 기준 오름차순으로 파일 목록을 반환한다")
			void orderByCreatedAt_asc() {
				// When
				FolderContentsDto result = folderService.getFolderContents(parentFolder.getId(), 0L, CursorType.FILE,
					10, FolderContentsSortField.CREATED_AT, Sort.Direction.ASC, now.minusDays(7), 0L, true);

				// Then
				assertEquals(7, result.fileMetadataList().size());
				assertTrue(result.folderMetadataList().isEmpty());
				for (int i = 0; i < result.fileMetadataList().size() - 1; i++) {
					assertTrue(result.fileMetadataList()
						.get(i)
						.getCreatedAt()
						.isBefore(result.fileMetadataList().get(i + 1).getCreatedAt())
						|| result.fileMetadataList()
						.get(i)
						.getCreatedAt()
						.equals(result.fileMetadataList().get(i + 1).getCreatedAt()));
				}
			}

			@Test
			@DisplayName("크기 기준 오름차순으로 파일 목록을 반환한다")
			void orderBySize_desc() {
				// When
				FolderContentsDto result = folderService.getFolderContents(parentFolder.getId(), 0L, CursorType.FILE,
					10, FolderContentsSortField.DATA_SIZE, Sort.Direction.ASC, now, 0L, true);

				// Then
				assertEquals(7, result.fileMetadataList().size());
				assertTrue(result.folderMetadataList().isEmpty());
			}

			@Test
			@DisplayName("limit 개수만큼만 파일 목록을 반환한다")
			void withLimit() {
				// When
				int limit = 5;
				FolderContentsDto result = folderService.getFolderContents(parentFolder.getId(), 0L, CursorType.FILE,
					limit, FolderContentsSortField.CREATED_AT, Sort.Direction.DESC, now, 0L, true);

				// Then
				assertEquals(limit, result.fileMetadataList().size());
				assertTrue(result.folderMetadataList().isEmpty());
			}
		}

		@Nested
		@DisplayName("폴더 커서 타입 조건에서")
		class WithFolderCursorType {

			@Test
			@DisplayName("생성 시간 기준 내림차순으로 폴더와 파일 목록을 반환한다")
			void orderByCreatedAt_desc() {
				// When
				FolderContentsDto result = folderService.getFolderContents(parentFolder.getId(), 0L, CursorType.FOLDER,
					20, FolderContentsSortField.CREATED_AT, Sort.Direction.DESC, now, 0L, true);

				// Then
				List<FolderMetadata> resultFolders = result.folderMetadataList();
				List<FileMetadata> resultFiles = result.fileMetadataList();
				assertEquals(7, resultFolders.size());
				assertEquals(7, resultFiles.size());
				for (int i = 0; i < resultFolders.size() - 1; i++) {
					assertTrue(resultFolders.get(i).getCreatedAt().isAfter(resultFolders.get(i + 1).getCreatedAt())
						|| resultFolders.get(i).getCreatedAt().equals(resultFolders.get(i + 1).getCreatedAt()));
				}
				for (int i = 0; i < resultFiles.size() - 1; i++) {
					assertTrue(resultFiles.get(i).getCreatedAt().isAfter(resultFiles.get(i + 1).getCreatedAt())
						|| resultFiles.get(i).getCreatedAt().equals(resultFiles.get(i + 1).getCreatedAt()));
				}
			}

			@Test
			@DisplayName("크기 기준 오름차순으로 폴더와 파일 목록을 반환한다")
			void orderBySize_asc() {
				// When
				FolderContentsDto result = folderService.getFolderContents(parentFolder.getId(), 0L, CursorType.FOLDER,
					20, FolderContentsSortField.DATA_SIZE, Sort.Direction.ASC, now, 0L, true);

				// Then
				List<FolderMetadata> resultFolders = result.folderMetadataList();
				List<FileMetadata> resultFiles = result.fileMetadataList();
				assertEquals(7, resultFolders.size());
				assertEquals(7, resultFiles.size());
				for (int i = 0; i < resultFolders.size() - 1; i++) {
					assertTrue(resultFolders.get(i).getSize() <= resultFolders.get(i + 1).getSize());
				}
				for (int i = 0; i < resultFiles.size() - 1; i++) {
					assertTrue(resultFiles.get(i).getFileSize() <= resultFiles.get(i + 1).getFileSize());
				}
			}

			@Test
			@DisplayName("limit 개수만큼만 폴더와 파일 목록을 반환한다")
			void withLimit() {
				// When
				int limit = 10;
				FolderContentsDto result = folderService.getFolderContents(parentFolder.getId(), 0L, CursorType.FOLDER,
					limit, FolderContentsSortField.CREATED_AT, Sort.Direction.DESC, now, 0L, true);

				// Then
				assertEquals(limit, result.folderMetadataList().size() + result.fileMetadataList().size());
			}
		}
	}
}
