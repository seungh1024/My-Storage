package com.woowacamp.storage.domain.file.facade;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.woowacamp.storage.domain.file.dto.FileMoveDto;
import com.woowacamp.storage.domain.file.dto.command.FileCreateLockContext;
import com.woowacamp.storage.domain.file.dto.command.FileMoveLockContext;
import com.woowacamp.storage.domain.file.dto.request.FileUploadRequestDto;
import com.woowacamp.storage.domain.file.dto.response.FileUploadResponseDto;
import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.file.service.FileService;
import com.woowacamp.storage.domain.file.service.S3FileService;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.global.error.CustomException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class FileFacadeTest {

	@InjectMocks
	private FileFacade fileFacade;

	@Mock
	private FileMetadataJpaRepository fileMetadataJpaRepository;

	@Mock
	private FolderMetadataJpaRepository folderMetadataJpaRepository;

	@Mock
	private FileService fileService;

	@Mock
	private S3FileService s3FileService;

	private FileMoveDto moveDto(long targetFolderId, long userId, long rootId, String fileName) {
		return new FileMoveDto(targetFolderId, userId, rootId, fileName);
	}

	private FileUploadRequestDto uploadDto(long userId, long parentFolderId, String fileName) {
		return new FileUploadRequestDto(userId, parentFolderId, 10L, userId, 1L, fileName, "txt");
	}

	@Nested
	@DisplayName("moveFile")
	class MoveFileTest {

		@Test
		@DisplayName("성공: source rootId 사용해서 lockContext를 생성한다")
		void success_use_source_root_id() {
			Long fileId = 1L;
			FileMoveDto dto = moveDto(20L, 100L, 999L, "ignored.txt");
			FileMetadata sourceFile = org.mockito.Mockito.mock(FileMetadata.class);
			FolderMetadata targetFolder = org.mockito.Mockito.mock(FolderMetadata.class);

			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.of(sourceFile));
			given(folderMetadataJpaRepository.findByIdNotDeleted(dto.targetFolderId())).willReturn(Optional.of(targetFolder));
			given(sourceFile.getRootId()).willReturn(300L);
			given(sourceFile.getUploadFileName()).willReturn("source-name.txt");
			given(targetFolder.getId()).willReturn(dto.targetFolderId());

			fileFacade.moveFile(fileId, dto);

			ArgumentCaptor<FileMoveLockContext> captor = ArgumentCaptor.forClass(FileMoveLockContext.class);
			then(fileService).should().moveFile(captor.capture(), org.mockito.Mockito.eq(dto));
			FileMoveLockContext lockContext = captor.getValue();
			assertEquals(fileId, lockContext.fileId());
			assertEquals(dto.targetFolderId(), lockContext.targetFolderId());
			assertEquals(300L, lockContext.rootId());
			assertEquals("source-name.txt", lockContext.fileName());
		}

		@Test
		@DisplayName("성공: source rootId가 null이면 target rootId를 사용한다")
		void success_fallback_to_target_root_id() {
			Long fileId = 1L;
			FileMoveDto dto = moveDto(20L, 100L, 999L, "ignored.txt");
			FileMetadata sourceFile = org.mockito.Mockito.mock(FileMetadata.class);
			FolderMetadata targetFolder = org.mockito.Mockito.mock(FolderMetadata.class);

			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.of(sourceFile));
			given(folderMetadataJpaRepository.findByIdNotDeleted(dto.targetFolderId())).willReturn(Optional.of(targetFolder));
			given(sourceFile.getRootId()).willReturn(null);
			given(sourceFile.getUploadFileName()).willReturn("source-name.txt");
			given(targetFolder.getId()).willReturn(dto.targetFolderId());
			given(targetFolder.getRootId()).willReturn(400L);

			fileFacade.moveFile(fileId, dto);

			ArgumentCaptor<FileMoveLockContext> captor = ArgumentCaptor.forClass(FileMoveLockContext.class);
			then(fileService).should().moveFile(captor.capture(), org.mockito.Mockito.eq(dto));
			assertEquals(400L, captor.getValue().rootId());
		}

		@Test
		@DisplayName("성공: source/target rootId가 null이면 target id를 rootId로 사용한다")
		void success_fallback_to_target_id_when_roots_are_null() {
			Long fileId = 1L;
			FileMoveDto dto = moveDto(20L, 100L, 999L, "ignored.txt");
			FileMetadata sourceFile = org.mockito.Mockito.mock(FileMetadata.class);
			FolderMetadata targetFolder = org.mockito.Mockito.mock(FolderMetadata.class);

			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.of(sourceFile));
			given(folderMetadataJpaRepository.findByIdNotDeleted(dto.targetFolderId())).willReturn(Optional.of(targetFolder));
			given(sourceFile.getRootId()).willReturn(null);
			given(sourceFile.getUploadFileName()).willReturn("source-name.txt");
			given(targetFolder.getId()).willReturn(dto.targetFolderId());
			given(targetFolder.getRootId()).willReturn(null);

			fileFacade.moveFile(fileId, dto);

			ArgumentCaptor<FileMoveLockContext> captor = ArgumentCaptor.forClass(FileMoveLockContext.class);
			then(fileService).should().moveFile(captor.capture(), org.mockito.Mockito.eq(dto));
			assertEquals(dto.targetFolderId(), captor.getValue().rootId());
		}

		@Test
		@DisplayName("실패: source 파일이 없으면 예외를 던지고 서비스 호출하지 않는다")
		void fail_source_file_not_found() {
			Long fileId = 1L;
			FileMoveDto dto = moveDto(20L, 100L, 999L, "ignored.txt");
			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.empty());

			assertThrows(CustomException.class, () -> fileFacade.moveFile(fileId, dto));

			then(folderMetadataJpaRepository).shouldHaveNoInteractions();
			then(fileService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: target 폴더가 없으면 예외를 던지고 서비스 호출하지 않는다")
		void fail_target_folder_not_found() {
			Long fileId = 1L;
			FileMoveDto dto = moveDto(20L, 100L, 999L, "ignored.txt");
			FileMetadata sourceFile = org.mockito.Mockito.mock(FileMetadata.class);
			given(fileMetadataJpaRepository.findById(fileId)).willReturn(Optional.of(sourceFile));
			given(folderMetadataJpaRepository.findByIdNotDeleted(dto.targetFolderId())).willReturn(Optional.empty());

			assertThrows(CustomException.class, () -> fileFacade.moveFile(fileId, dto));

			then(fileService).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("createFileMetadata")
	class CreateFileMetadataTest {

		@Test
		@DisplayName("성공: parent rootId를 사용해서 lockContext를 생성한다")
		void success_use_parent_root_id() throws MalformedURLException {
			FileUploadRequestDto dto = uploadDto(100L, 10L, "new.txt");
			FolderMetadata parentFolder = org.mockito.Mockito.mock(FolderMetadata.class);
			FileUploadResponseDto expected = new FileUploadResponseDto(1L, "k", new URL("https://example.com"));

			given(folderMetadataJpaRepository.findById(dto.parentFolderId())).willReturn(Optional.of(parentFolder));
			given(parentFolder.getId()).willReturn(dto.parentFolderId());
			given(parentFolder.getRootId()).willReturn(50L);
			given(s3FileService.createFileMetadata(any(FileCreateLockContext.class), org.mockito.Mockito.eq(dto)))
				.willReturn(expected);

			FileUploadResponseDto actual = fileFacade.createFileMetadata(dto);

			ArgumentCaptor<FileCreateLockContext> captor = ArgumentCaptor.forClass(FileCreateLockContext.class);
			then(s3FileService).should().createFileMetadata(captor.capture(), org.mockito.Mockito.eq(dto));
			FileCreateLockContext lockContext = captor.getValue();
			assertEquals(dto.parentFolderId(), lockContext.parentFolderId());
			assertEquals(50L, lockContext.rootId());
			assertEquals(dto.fileName(), lockContext.fileName());
			assertEquals(expected, actual);
		}

		@Test
		@DisplayName("성공: parent rootId가 null이면 parent id를 rootId로 사용한다")
		void success_fallback_to_parent_id_when_root_is_null() throws MalformedURLException {
			FileUploadRequestDto dto = uploadDto(100L, 10L, "new.txt");
			FolderMetadata parentFolder = org.mockito.Mockito.mock(FolderMetadata.class);
			FileUploadResponseDto expected = new FileUploadResponseDto(1L, "k", new URL("https://example.com"));

			given(folderMetadataJpaRepository.findById(dto.parentFolderId())).willReturn(Optional.of(parentFolder));
			given(parentFolder.getId()).willReturn(dto.parentFolderId());
			given(parentFolder.getRootId()).willReturn(null);
			given(s3FileService.createFileMetadata(any(FileCreateLockContext.class), org.mockito.Mockito.eq(dto)))
				.willReturn(expected);

			fileFacade.createFileMetadata(dto);

			ArgumentCaptor<FileCreateLockContext> captor = ArgumentCaptor.forClass(FileCreateLockContext.class);
			then(s3FileService).should().createFileMetadata(captor.capture(), org.mockito.Mockito.eq(dto));
			assertEquals(dto.parentFolderId(), captor.getValue().rootId());
		}

		@Test
		@DisplayName("실패: parent 폴더가 없으면 예외를 던지고 서비스 호출하지 않는다")
		void fail_parent_folder_not_found() {
			FileUploadRequestDto dto = uploadDto(100L, 10L, "new.txt");
			given(folderMetadataJpaRepository.findById(dto.parentFolderId())).willReturn(Optional.empty());

			assertThrows(CustomException.class, () -> fileFacade.createFileMetadata(dto));

			then(s3FileService).shouldHaveNoInteractions();
		}
	}
}
