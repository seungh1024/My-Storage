package com.woowacamp.storage.domain.file.service;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.woowacamp.storage.domain.file.dto.request.FileUploadRequestDto;
import com.woowacamp.storage.domain.file.dto.response.FileUploadResponseDto;
import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.service.MetadataService;
import com.woowacamp.storage.global.aop.type.FileType;
import com.woowacamp.storage.global.constant.PermissionType;
import com.woowacamp.storage.global.constant.UploadStatus;
import com.woowacamp.storage.global.error.CustomException;
import com.woowacamp.storage.global.error.ErrorCode;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class S3FileServiceTest {

	@InjectMocks
	private S3FileService s3FileService;

	@Mock
	private FolderMetadataJpaRepository folderMetadataJpaRepository;

	@Mock
	private FileMetadataJpaRepository fileMetadataJpaRepository;

	@Mock
	private PresignedUrlService presignedUrlService;

	@Mock
	private MetadataService metadataService;

	private final long MAX_FILE_SIZE = 10000L;
	private final long MAX_STORAGE_SIZE = 10000L;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(s3FileService, "MAX_FILE_SIZE", 10000L);
		ReflectionTestUtils.setField(s3FileService, "MAX_STORAGE_SIZE", 10000L);
	}

	@Nested
	@DisplayName("파일 업로드 테스트")
	class CreateFileTest {

		@Test
		@DisplayName("부모 폴더가 존재하지 않으면 파일 업로드에 실패한다.")
		void if_parent_folder_not_exist() {
			long userId = 1;
			long parentFolderId = 1;
			long fileSize = 1;
			long creatorId = 1;
			String fileName = "test";
			String fileExtension = "jpg";

			FileUploadRequestDto requestDto = new FileUploadRequestDto(userId, parentFolderId, fileSize, creatorId,
				fileName, fileExtension);

			given(folderMetadataJpaRepository.findById(parentFolderId))
				.willThrow(ErrorCode.FOLDER_NOT_FOUND.baseException());

			CustomException customException = assertThrows(CustomException.class,
				() -> s3FileService.createFileMetadata(requestDto));

			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("파일 이름에 금칙어가 존재하면 파일 업로드에 실패한다.")
		void if_file_name_include_invalid_word() {
			long userId = 1;
			long parentFolderId = 1;
			long fileSize = 1;
			long creatorId = 1;
			String fileName = "test//";
			String fileExtension = "jpg";

			FileUploadRequestDto requestDto = new FileUploadRequestDto(userId, parentFolderId, fileSize, creatorId,
				fileName, fileExtension);

			FolderMetadata folderMetadata = FolderMetadata.builder()
				.id(parentFolderId)
				.build();

			given(folderMetadataJpaRepository.findById(parentFolderId)).willReturn(Optional.of(folderMetadata));

			CustomException customException = assertThrows(CustomException.class,
				() -> s3FileService.createFileMetadata(requestDto));

			assertEquals(ErrorCode.INVALID_FILE_NAME.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("파일 이름에 금칙어가 존재하면 파일 업로드에 실패한다.")
		void if_file_extension_include_invalid_word() {
			long userId = 1;
			long parentFolderId = 1;
			long fileSize = 1;
			long creatorId = 1;
			String fileName = "test";
			String fileExtension = "jpg//";

			FileUploadRequestDto requestDto = new FileUploadRequestDto(userId, parentFolderId, fileSize, creatorId,
				fileName, fileExtension);

			FolderMetadata folderMetadata = FolderMetadata.builder()
				.id(parentFolderId)
				.build();

			given(folderMetadataJpaRepository.findById(parentFolderId)).willReturn(Optional.of(folderMetadata));

			CustomException customException = assertThrows(CustomException.class,
				() -> s3FileService.createFileMetadata(requestDto));

			assertEquals(ErrorCode.INVALID_FILE_NAME.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("파일 이름에 금칙어가 존재하면 파일 업로드에 실패한다.")
		void if_file_name_already_exists() {
			long userId = 1;
			long parentFolderId = 1;
			long fileSize = 1;
			long creatorId = 1;
			String fileName = "test";
			String fileExtension = "jpg";

			FileUploadRequestDto requestDto = new FileUploadRequestDto(userId, parentFolderId, fileSize, creatorId,
				fileName, fileExtension);

			FolderMetadata folderMetadata = FolderMetadata.builder()
				.id(parentFolderId)
				.build();

			given(folderMetadataJpaRepository.findById(parentFolderId)).willReturn(Optional.of(folderMetadata));
			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(parentFolderId,
				fileName,
				UploadStatus.FAIL)).willReturn(true);

			CustomException customException = assertThrows(CustomException.class,
				() -> s3FileService.createFileMetadata(requestDto));

			assertEquals(ErrorCode.FILE_NAME_DUPLICATE.getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("파일 업로드 성공 테스트")
		void file_upload_success() throws MalformedURLException {
			long userId = 1;
			long rootId = 1;
			long parentFolderId = 2;
			long fileSize = 1;
			long creatorId = 1;
			long rootFolderId = 1;
			String fileName = "test";
			String fileExtension = "jpg";
			URL url = new URL("http://test");
			long fileMetadataId = 1;
			String objectKey = "test";

			FileUploadRequestDto requestDto = new FileUploadRequestDto(userId, parentFolderId, fileSize, creatorId,
				fileName, fileExtension);

			FolderMetadata folderMetadata = FolderMetadata.builder()
				.id(parentFolderId)
				.rootId(rootFolderId)
				.build();

			FolderMetadata rootFolder = FolderMetadata.builder()
				.id(rootId)
				.size(fileSize)
				.build();

			given(folderMetadataJpaRepository.findById(parentFolderId)).willReturn(Optional.of(folderMetadata));
			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(parentFolderId,
				fileName,
				UploadStatus.FAIL)).willReturn(false);
			given(fileMetadataJpaRepository.existsByUuidFileName(anyString())).willReturn(false);

			given(folderMetadataJpaRepository.findByIdForUpdate(anyLong())).willReturn(Optional.of(rootFolder));

			given(fileMetadataJpaRepository.save(any(FileMetadata.class))).willAnswer(invocation -> {
				FileMetadata savedMetadata = invocation.getArgument(0); // save()로 전달된 객체 가져오기
				ReflectionTestUtils.setField(savedMetadata, "id", 1L);  // Mock 환경에서 ID 강제 설정
				return savedMetadata;
			});

			doNothing().when(metadataService).calculateSize(anyLong());

			given(presignedUrlService.getPresignedUrl(anyString())).willReturn(url);

			FileUploadResponseDto response = s3FileService.createFileMetadata(requestDto);

			assertEquals(url.toString(), response.presignedUrl().toString());
			assertEquals(fileMetadataId, response.id());
		}

		@Test
		@DisplayName("파일 업로드 시 제한 용량을 넘으면 업로드에 실패한다.")
		void if_file_size_over_max_file_size() {
			long userId = 1;
			long rootId = 1;
			long parentFolderId = 2;
			long fileSize = MAX_FILE_SIZE + 1;
			long creatorId = 1;
			long rootFolderId = 1;
			String fileName = "test";
			String fileExtension = "jpg";

			FileUploadRequestDto requestDto = new FileUploadRequestDto(userId, parentFolderId, fileSize, creatorId,
				fileName, fileExtension);

			FolderMetadata folderMetadata = FolderMetadata.builder()
				.id(parentFolderId)
				.rootId(rootFolderId)
				.build();

			FolderMetadata rootFolder = FolderMetadata.builder()
				.id(rootId)
				.size(1)
				.build();

			given(folderMetadataJpaRepository.findById(parentFolderId)).willReturn(Optional.of(folderMetadata));
			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(parentFolderId,
				fileName,
				UploadStatus.FAIL)).willReturn(false);

			given(folderMetadataJpaRepository.findByIdForUpdate(anyLong())).willReturn(Optional.of(rootFolder));

			// when
			CustomException customException = assertThrows(CustomException.class,
				() -> s3FileService.createFileMetadata(requestDto));

			// then
			assertEquals(ErrorCode.EXCEED_MAX_FILE_SIZE.baseException().getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("파일 업로드 시 스토리지 제한 용량을 넘으면 업로드에 실패한다.")
		void if_file_size_over_max_storage_size() {
			long userId = 1;
			long rootId = 1;
			long parentFolderId = 2;
			long fileSize = 1;
			long creatorId = 1;
			long rootFolderId = 1;
			String fileName = "test";
			String fileExtension = "jpg";

			FileUploadRequestDto requestDto = new FileUploadRequestDto(userId, parentFolderId, fileSize, creatorId,
				fileName, fileExtension);

			FolderMetadata folderMetadata = FolderMetadata.builder()
				.id(parentFolderId)
				.rootId(rootFolderId)
				.build();

			FolderMetadata rootFolder = FolderMetadata.builder()
				.id(rootId)
				.size(MAX_FILE_SIZE)
				.build();

			given(folderMetadataJpaRepository.findById(parentFolderId)).willReturn(Optional.of(folderMetadata));
			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(parentFolderId,
				fileName,
				UploadStatus.FAIL)).willReturn(false);

			given(folderMetadataJpaRepository.findByIdForUpdate(anyLong())).willReturn(Optional.of(rootFolder));

			// when
			CustomException customException = assertThrows(CustomException.class,
				() -> s3FileService.createFileMetadata(requestDto));

			// then
			assertEquals(ErrorCode.EXCEED_MAX_STORAGE_SIZE.baseException().getMessage(), customException.getMessage());
		}

	}

	@Nested
	@DisplayName("파일 다운로드 테스트")
	class FileDownloadTest {

		private final long rootId = 1;
		private final long creatorId = 1;
		private final long ownerId = 1;
		private final String fileType = FileType.FILE.name();
		private final long parentFolderId = 1;
		private final LocalDateTime createdAt = LocalDateTime.now();
		private final LocalDateTime updatedAt = LocalDateTime.now();
		private final long fileSize = 1;
		private final String uploadFileName = "test";
		private final String uuidFileName = "uuid";
		private final LocalDateTime sharingExpiredAt = LocalDateTime.now();
		private final PermissionType permissionType = PermissionType.WRITE;

		@Test
		@DisplayName("파일이 존재하지 않으면 예외가 발생한다.")
		void if_file_not_exists() {
			FileMetadata fileMetadata = FileMetadata.builder()
				.rootId(rootId)
				.creatorId(creatorId)
				.ownerId(ownerId)
				.fileType(fileType)
				.parentFolderId(parentFolderId)
				.createdAt(createdAt)
				.updatedAt(updatedAt)
				.fileSize(fileSize)
				.uploadFileName(uploadFileName)
				.uuidFileName(uuidFileName)
				.sharingExpiredAt(sharingExpiredAt)
				.permissionType(permissionType)
				.build();

			given(fileMetadataJpaRepository.save(any(FileMetadata.class))).willAnswer(invocation -> {
				FileMetadata savedMetadata = invocation.getArgument(0); // save()로 전달된 객체 가져오기
				ReflectionTestUtils.setField(savedMetadata, "id", 1L);  // Mock 환경에서 ID 강제 설정
				return savedMetadata;
			});

			fileMetadataJpaRepository.save(fileMetadata);

			CustomException customException = assertThrows(CustomException.class,
				() -> s3FileService.getFileUrl(fileMetadata.getId() + 1));

			assertEquals(ErrorCode.FILE_NOT_FOUND.baseException().getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("파일의 업로드가 완료되지 않으면 예외가 발생한다.")
		void if_file_upload_status_not_success() {
			FileMetadata fileMetadata = FileMetadata.builder()
				.rootId(rootId)
				.creatorId(creatorId)
				.ownerId(ownerId)
				.fileType(fileType)
				.parentFolderId(parentFolderId)
				.createdAt(createdAt)
				.updatedAt(updatedAt)
				.fileSize(fileSize)
				.uploadFileName(uploadFileName)
				.uuidFileName(uuidFileName)
				.sharingExpiredAt(sharingExpiredAt)
				.permissionType(permissionType)
				.uploadStatus(UploadStatus.FAIL)
				.build();

			given(fileMetadataJpaRepository.save(any(FileMetadata.class))).willAnswer(invocation -> {
				FileMetadata savedMetadata = invocation.getArgument(0); // save()로 전달된 객체 가져오기
				ReflectionTestUtils.setField(savedMetadata, "id", 1L);  // Mock 환경에서 ID 강제 설정
				return savedMetadata;
			});

			fileMetadataJpaRepository.save(fileMetadata);

			CustomException customException = assertThrows(CustomException.class,
				() -> s3FileService.getFileUrl(fileMetadata.getId()));

			assertEquals(ErrorCode.FILE_NOT_FOUND.baseException().getMessage(), customException.getMessage());
		}

		@Test
		@DisplayName("파일의 다운로드 링크를 받는다.")
		void get_file_download_link_success() throws MalformedURLException {
			FileMetadata fileMetadata = FileMetadata.builder()
				.rootId(rootId)
				.creatorId(creatorId)
				.ownerId(ownerId)
				.fileType(fileType)
				.parentFolderId(parentFolderId)
				.createdAt(createdAt)
				.updatedAt(updatedAt)
				.fileSize(fileSize)
				.uploadFileName(uploadFileName)
				.uuidFileName(uuidFileName)
				.sharingExpiredAt(sharingExpiredAt)
				.permissionType(permissionType)
				.uploadStatus(UploadStatus.SUCCESS)
				.build();

			given(fileMetadataJpaRepository.findById(anyLong())).willReturn(Optional.of(fileMetadata));

			given(fileMetadataJpaRepository.save(any(FileMetadata.class))).willAnswer(invocation -> {
				FileMetadata savedMetadata = invocation.getArgument(0); // save()로 전달된 객체 가져오기
				ReflectionTestUtils.setField(savedMetadata, "id", 1L);  // Mock 환경에서 ID 강제 설정
				return savedMetadata;
			});

			fileMetadataJpaRepository.save(fileMetadata);

			URL url = new URL("http://test:8080");
			given(presignedUrlService.getDownloadUrl(anyString())).willReturn(url);

			URL fileUrl = s3FileService.getFileUrl(fileMetadata.getId());

			assertEquals(url.getHost(), fileUrl.getHost());
			assertEquals(url.getPath(), fileUrl.getPath());
			assertEquals(url.getPort(), fileUrl.getPort());
		}
	}
}