package com.woowacamp.storage.domain.file.service;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.woowacamp.storage.container.ContainerBaseConfig;
import com.woowacamp.storage.domain.file.dto.request.FileUploadRequestDto;
import com.woowacamp.storage.domain.file.dto.response.FileUploadResponseDto;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.global.constant.PermissionType;

import static org.junit.Assert.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public class S3FileServiceIntegrationTest extends ContainerBaseConfig {

	@Autowired
	private FolderMetadataJpaRepository folderMetadataJpaRepository;

	@Autowired
	private FileMetadataJpaRepository fileMetadataJpaRepository;

	@Autowired
	private S3FileService s3FileService;

	@Autowired
	private PresignedUrlService presignedUrlService;

	@Nested
	@DisplayName("파일 다운로드 테스트")
	class FileDownloadTest {

		private final RestTemplate restTemplate = new RestTemplate();

		@Test
		@DisplayName("파일 다운로드 성공 테스트")
		void get_file_download_link() throws IOException, URISyntaxException, InterruptedException {
			long folderId = 1;
			long rootId = 1;
			long ownerId = 1;
			long creatorId = 1;
			LocalDateTime createdAt = LocalDateTime.now();
			LocalDateTime updatedAt = LocalDateTime.now();
			String uploadFolderName = "root";
			long size = 0;
			LocalDateTime sharingExpiredAt = LocalDateTime.now();
			PermissionType permissionType = PermissionType.WRITE;

			FolderMetadata folderMetadata = FolderMetadata.builder()
				.rootId(rootId)
				.ownerId(ownerId)
				.creatorId(creatorId)
				.createdAt(createdAt)
				.updatedAt(updatedAt)
				.uploadFolderName(uploadFolderName)
				.size(size)
				.sharingExpiredAt(sharingExpiredAt)
				.permissionType(permissionType)
				.build();

			folderMetadataJpaRepository.save(folderMetadata);

			// 1. 업로드 파일 생성
			File tempFile = Files.createTempFile("test-upload", ".txt").toFile();
			try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
				writer.write("12345"); // 파일 내용 작성
			}

			String[] totalName = tempFile.getName().split("\\.");
			long parentFolderId = folderMetadata.getId();
			long fileSize = tempFile.length();
			String fileName = totalName[0];
			String fileExtension = totalName[1];

			FileUploadRequestDto requestDto = new FileUploadRequestDto(ownerId, parentFolderId, fileSize, creatorId,
				fileName, fileExtension);

			// 2. 업로드 파일 생성 및 presigned url 획득
			FileUploadResponseDto initialMetadata = s3FileService.createInitialMetadata(requestDto);

			// 3. presigned url로 파일 업로드
			byte[] uploadFileContent = Files.readAllBytes(tempFile.toPath());
			restTemplate.put(initialMetadata.presignedUrl().toURI(), uploadFileContent);
			// 3-1. 업로드 완료 요청 보내기
			s3FileService.createComplete(initialMetadata.id(), ownerId, initialMetadata.objectKey());

			Thread.sleep(3000);

			// 4. 다운로드 url 받기
			URL fileUrl = s3FileService.getFileUrl(initialMetadata.id());

			// 5. 원격 파일 다운로드
			ResponseEntity<byte[]> forEntity = restTemplate.getForEntity(fileUrl.toURI(), byte[].class);

			Thread.sleep(3000);

			// 6. 원격 파일 삭제
			presignedUrlService.deleteFile(initialMetadata.objectKey());

			byte[] downloadFileContent = Objects.requireNonNull(forEntity.getBody());
			byte[] originalFileContent = Files.readAllBytes(tempFile.toPath());

			assertArrayEquals(originalFileContent, downloadFileContent);

		}
	}
}
