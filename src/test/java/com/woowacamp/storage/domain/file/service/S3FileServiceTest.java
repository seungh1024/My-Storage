package com.woowacamp.storage.domain.file.service;

import java.net.URL;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.woowacamp.storage.domain.file.dto.request.FileUploadRequestDto;
import com.woowacamp.storage.domain.file.dto.response.FileUploadResponseDto;
import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.entity.FileMetadataFactory;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.global.constant.CommonConstant;
import com.woowacamp.storage.global.constant.UploadStatus;
import com.woowacamp.storage.global.error.CustomException;
import com.woowacamp.storage.global.error.ErrorCode;
import com.woowacamp.storage.global.util.ValidateParentsUtil;

import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class S3FileServiceTest {

	@InjectMocks
	private S3FileService s3FileService;

	@Mock
	private FileMetadataJpaRepository fileMetadataJpaRepository;
	@Mock
	private FolderMetadataJpaRepository folderMetadataJpaRepository;
	@Mock
	private PresignedUrlService presignedUrlService;
	@Mock
	private ValidateParentsUtil validateParentsUtil;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(s3FileService, "MAX_FILE_SIZE", 100L);
		ReflectionTestUtils.setField(s3FileService, "MAX_STORAGE_SIZE", 1_000L);
	}

	// record 순서 반드시 맞춰야 함:
	// (userId, parentFolderId, fileSize, creatorId, rootId, fileName, fileExtension)
	private FileUploadRequestDto req(
		long userId,
		long parentFolderId,
		long fileSize,
		long creatorId,
		long rootId,
		String fileName,
		String fileExtension
	) {
		return new FileUploadRequestDto(userId, parentFolderId, fileSize, creatorId, rootId, fileName, fileExtension);
	}

	// =========================================================
	// createFileMetadata
	// =========================================================
	@Nested
	@DisplayName("createFileMetadata")
	class CreateFileMetadataTests {

		@Test
		@DisplayName("실패: parent 폴더 없음 -> FOLDER_NOT_FOUND, 이후 로직 진행 안 함")
		void fail_parent_not_found() {
			// given
			FileUploadRequestDto dto = req(10L, 100L, 10L, 10L, 1L, "a.txt", ".txt");
			given(folderMetadataJpaRepository.findById(dto.parentFolderId())).willReturn(Optional.empty());

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> s3FileService.createFileMetadata(dto));
			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), ex.getMessage());

			then(fileMetadataJpaRepository).shouldHaveNoInteractions();
			then(folderMetadataJpaRepository).should(never()).findByIdForUpdate(anyLong());
			then(validateParentsUtil).shouldHaveNoInteractions();
			then(presignedUrlService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: parent owner != dto.userId -> ACCESS_DENIED, 검증/용량/락/저장 전 종료")
		void fail_access_denied_owner_mismatch() {
			// given
			FileUploadRequestDto dto = req(10L, 100L, 10L, 10L, 1L, "a.txt", ".txt");

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(dto.parentFolderId())).willReturn(Optional.of(parent));
			given(parent.getOwnerId()).willReturn(999L); // mismatch

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> s3FileService.createFileMetadata(dto));
			assertEquals(ErrorCode.ACCESS_DENIED.getMessage(), ex.getMessage());

			then(fileMetadataJpaRepository).shouldHaveNoInteractions(); // duplicate 체크도 안 감
			then(folderMetadataJpaRepository).should(never()).findByIdForUpdate(anyLong());
			then(validateParentsUtil).shouldHaveNoInteractions();
			then(presignedUrlService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: 파일명 금칙어 -> INVALID_FILE_NAME, 중복/용량/락/저장 전 종료")
		void fail_invalid_file_name_blacklist() {
			// given
			char bad = CommonConstant.FILE_NAME_BLACK_LIST[0];
			FileUploadRequestDto dto = req(10L, 100L, 10L, 10L, 1L, "ab" + bad + "cd", ".txt");

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(dto.parentFolderId())).willReturn(Optional.of(parent));
			given(parent.getOwnerId()).willReturn(dto.userId());

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> s3FileService.createFileMetadata(dto));
			assertEquals(ErrorCode.INVALID_FILE_NAME.getMessage(), ex.getMessage());

			then(fileMetadataJpaRepository).shouldHaveNoInteractions();
			then(folderMetadataJpaRepository).should(never()).findByIdForUpdate(anyLong());
			then(validateParentsUtil).shouldHaveNoInteractions();
			then(presignedUrlService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: 확장자 금칙어 -> INVALID_FILE_NAME, 중복/용량/락/저장 전 종료")
		void fail_invalid_extension_blacklist() {
			// given
			char bad = CommonConstant.FILE_NAME_BLACK_LIST[0];
			FileUploadRequestDto dto = req(10L, 100L, 10L, 10L, 1L, "a.txt", "" + bad);

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(dto.parentFolderId())).willReturn(Optional.of(parent));
			given(parent.getOwnerId()).willReturn(dto.userId());

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> s3FileService.createFileMetadata(dto));
			assertEquals(ErrorCode.INVALID_FILE_NAME.getMessage(), ex.getMessage());

			then(fileMetadataJpaRepository).shouldHaveNoInteractions();
			then(folderMetadataJpaRepository).should(never()).findByIdForUpdate(anyLong());
			then(validateParentsUtil).shouldHaveNoInteractions();
			then(presignedUrlService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: 동일 파일명 존재 -> FILE_NAME_DUPLICATE, 용량/락/저장 전 종료")
		void fail_duplicate_file_name_in_folder() {
			// given
			FileUploadRequestDto dto = req(10L, 100L, 10L, 10L, 1L, "a.txt", ".txt");

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(dto.parentFolderId())).willReturn(Optional.of(parent));
			given(parent.getOwnerId()).willReturn(dto.userId());

			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
				dto.parentFolderId(), dto.fileName(), UploadStatus.FAIL
			)).willReturn(true);

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> s3FileService.createFileMetadata(dto));
			assertEquals(ErrorCode.FILE_NAME_DUPLICATE.getMessage(), ex.getMessage());

			then(folderMetadataJpaRepository).should(never()).findByIdForUpdate(anyLong());
			then(validateParentsUtil).shouldHaveNoInteractions();
			then(fileMetadataJpaRepository).should(never()).save(any());
			then(presignedUrlService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: root(forUpdate) 없음 -> FOLDER_NOT_FOUND (validateFile 통과 후)")
		void fail_root_not_found() {
			// given
			FileUploadRequestDto dto = req(10L, 100L, 10L, 10L, 1L, "a.txt", ".txt");

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(dto.parentFolderId())).willReturn(Optional.of(parent));
			given(parent.getOwnerId()).willReturn(dto.userId());

			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
				dto.parentFolderId(), dto.fileName(), UploadStatus.FAIL
			)).willReturn(false);

			given(folderMetadataJpaRepository.findByIdForUpdate(dto.rootId())).willReturn(Optional.empty());

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> s3FileService.createFileMetadata(dto));
			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), ex.getMessage());

			then(validateParentsUtil).shouldHaveNoInteractions();
			then(fileMetadataJpaRepository).should(never()).save(any());
			then(presignedUrlService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: fileSize > MAX_FILE_SIZE -> EXCEED_MAX_FILE_SIZE")
		void fail_exceed_max_file_size() {
			// given (MAX_FILE_SIZE=100)
			FileUploadRequestDto dto = req(10L, 100L, 101L, 10L, 1L, "a.txt", ".txt");

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(dto.parentFolderId())).willReturn(Optional.of(parent));
			given(parent.getOwnerId()).willReturn(dto.userId());

			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
				dto.parentFolderId(), dto.fileName(), UploadStatus.FAIL
			)).willReturn(false);

			FolderMetadata root = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findByIdForUpdate(dto.rootId())).willReturn(Optional.of(root));
			// root.getSize()는 이 케이스에서 안 쓰임(>MAX에서 바로 throw)

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> s3FileService.createFileMetadata(dto));
			assertEquals(ErrorCode.EXCEED_MAX_FILE_SIZE.getMessage(), ex.getMessage());

			then(validateParentsUtil).shouldHaveNoInteractions();
			then(fileMetadataJpaRepository).should(never()).save(any());
			then(presignedUrlService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: root.size + fileSize > MAX_STORAGE_SIZE -> EXCEED_MAX_STORAGE_SIZE")
		void fail_exceed_max_storage_size() {
			// given (MAX_STORAGE_SIZE=1000)
			FileUploadRequestDto dto = req(10L, 100L, 10L, 10L, 1L, "a.txt", ".txt");

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(dto.parentFolderId())).willReturn(Optional.of(parent));
			given(parent.getOwnerId()).willReturn(dto.userId());

			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
				dto.parentFolderId(), dto.fileName(), UploadStatus.FAIL
			)).willReturn(false);

			FolderMetadata root = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findByIdForUpdate(dto.rootId())).willReturn(Optional.of(root));
			given(root.getSize()).willReturn(995L); // 995 + 10 > 1000

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> s3FileService.createFileMetadata(dto));
			assertEquals(ErrorCode.EXCEED_MAX_STORAGE_SIZE.getMessage(), ex.getMessage());

			then(validateParentsUtil).shouldHaveNoInteractions();
			then(fileMetadataJpaRepository).should(never()).save(any());
			then(presignedUrlService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: validateParentsUtil에서 예외 -> 저장/URL 생성 전 종료")
		void fail_validate_parents_lock_throws() {
			// given
			FileUploadRequestDto dto = req(10L, 100L, 10L, 10L, 1L, "a.txt", ".txt");

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(dto.parentFolderId())).willReturn(Optional.of(parent));
			given(parent.getOwnerId()).willReturn(dto.userId());

			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
				dto.parentFolderId(), dto.fileName(), UploadStatus.FAIL
			)).willReturn(false);

			FolderMetadata root = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findByIdForUpdate(dto.rootId())).willReturn(Optional.of(root));
			given(root.getSize()).willReturn(0L);

			willThrow(ErrorCode.PARENT_LOCKED.baseException())
				.given(validateParentsUtil).validateParentsFolderLock(parent);

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> s3FileService.createFileMetadata(dto));
			assertEquals(ErrorCode.PARENT_LOCKED.getMessage(), ex.getMessage());

			then(fileMetadataJpaRepository).should(never()).save(any());
			then(presignedUrlService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: FileMetadataFactory(static)에서 예외 -> save 호출되지 않는다")
		void fail_factory_throws_no_save() {
			// given
			FileUploadRequestDto dto = req(10L, 100L, 10L, 10L, 1L, "a.txt", ".txt");

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(dto.parentFolderId())).willReturn(Optional.of(parent));
			given(parent.getOwnerId()).willReturn(dto.userId());

			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
				dto.parentFolderId(), dto.fileName(), UploadStatus.FAIL
			)).willReturn(false);

			FolderMetadata root = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findByIdForUpdate(dto.rootId())).willReturn(Optional.of(root));
			given(root.getSize()).willReturn(0L);

			willDoNothing().given(validateParentsUtil).validateParentsFolderLock(parent);

			try (MockedStatic<FileMetadataFactory> st = mockStatic(FileMetadataFactory.class)) {
				st.when(() -> FileMetadataFactory.buildInitialMetadata(eq(parent), eq(dto), anyString()))
					.thenThrow(new RuntimeException("boom"));

				// when & then
				assertThrows(RuntimeException.class, () -> s3FileService.createFileMetadata(dto));

				then(fileMetadataJpaRepository).should(never()).save(any());
				then(presignedUrlService).shouldHaveNoInteractions();
			}
		}

		@Test
		@DisplayName("실패: 첫 save에서 예외 -> updateIdFullPath/두번째 save/URL 생성 없음")
		void fail_first_save_throws() {
			// given
			FileUploadRequestDto dto = req(10L, 100L, 10L, 10L, 1L, "a.txt", ".txt");

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(dto.parentFolderId())).willReturn(Optional.of(parent));
			given(parent.getOwnerId()).willReturn(dto.userId());

			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
				dto.parentFolderId(), dto.fileName(), UploadStatus.FAIL
			)).willReturn(false);

			FolderMetadata root = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findByIdForUpdate(dto.rootId())).willReturn(Optional.of(root));
			given(root.getSize()).willReturn(0L);

			willDoNothing().given(validateParentsUtil).validateParentsFolderLock(parent);

			FileMetadata fileMetadata = mock(FileMetadata.class);

			try (MockedStatic<FileMetadataFactory> st = mockStatic(FileMetadataFactory.class)) {
				st.when(() -> FileMetadataFactory.buildInitialMetadata(eq(parent), eq(dto), anyString()))
					.thenReturn(fileMetadata);

				willThrow(new RuntimeException("db")).given(fileMetadataJpaRepository).save(fileMetadata);

				// when & then
				assertThrows(RuntimeException.class, () -> s3FileService.createFileMetadata(dto));

				then(presignedUrlService).shouldHaveNoInteractions();
				then(fileMetadataJpaRepository).should(times(1)).save(fileMetadata);
			}
		}

		@Test
		@DisplayName("실패: updateIdFullPath에서 예외 -> 두번째 save/URL 생성 없음")
		void fail_updateIdFullPath_throws() {
			// given
			FileUploadRequestDto dto = req(10L, 100L, 10L, 10L, 1L, "a.txt", ".txt");

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(dto.parentFolderId())).willReturn(Optional.of(parent));
			given(parent.getOwnerId()).willReturn(dto.userId());
			given(parent.getIdFullPath()).willReturn("/100/");

			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
				dto.parentFolderId(), dto.fileName(), UploadStatus.FAIL
			)).willReturn(false);

			FolderMetadata root = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findByIdForUpdate(dto.rootId())).willReturn(Optional.of(root));
			given(root.getSize()).willReturn(0L);

			willDoNothing().given(validateParentsUtil).validateParentsFolderLock(parent);

			FileMetadata fileMetadata = mock(FileMetadata.class);
			FileMetadata saved = mock(FileMetadata.class);

			try (MockedStatic<FileMetadataFactory> st = mockStatic(FileMetadataFactory.class)) {
				st.when(() -> FileMetadataFactory.buildInitialMetadata(eq(parent), eq(dto), anyString()))
					.thenReturn(fileMetadata);

				given(fileMetadataJpaRepository.save(fileMetadata)).willReturn(saved);
				willThrow(new RuntimeException("boom")).given(saved).updateIdFullPath("/100/");

				// when & then
				assertThrows(RuntimeException.class, () -> s3FileService.createFileMetadata(dto));

				then(fileMetadataJpaRepository).should(times(1)).save(fileMetadata);
				then(fileMetadataJpaRepository).should(never()).save(saved);
				then(presignedUrlService).shouldHaveNoInteractions();
			}
		}

		@Test
		@DisplayName("실패: 두번째 save에서 예외 -> URL 생성 없이 예외 전파")
		void fail_second_save_throws() {
			// given
			FileUploadRequestDto dto = req(10L, 100L, 10L, 10L, 1L, "a.txt", ".txt");

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(dto.parentFolderId())).willReturn(Optional.of(parent));
			given(parent.getOwnerId()).willReturn(dto.userId());
			given(parent.getIdFullPath()).willReturn("/100/");

			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
				dto.parentFolderId(), dto.fileName(), UploadStatus.FAIL
			)).willReturn(false);

			FolderMetadata root = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findByIdForUpdate(dto.rootId())).willReturn(Optional.of(root));
			given(root.getSize()).willReturn(0L);

			willDoNothing().given(validateParentsUtil).validateParentsFolderLock(parent);

			FileMetadata fileMetadata = mock(FileMetadata.class);
			FileMetadata saved = mock(FileMetadata.class);

			try (MockedStatic<FileMetadataFactory> st = mockStatic(FileMetadataFactory.class)) {
				st.when(() -> FileMetadataFactory.buildInitialMetadata(eq(parent), eq(dto), anyString()))
					.thenReturn(fileMetadata);

				given(fileMetadataJpaRepository.save(fileMetadata)).willReturn(saved);
				willDoNothing().given(saved).updateIdFullPath("/100/");
				willThrow(new RuntimeException("db2")).given(fileMetadataJpaRepository).save(saved);

				// when & then
				assertThrows(RuntimeException.class, () -> s3FileService.createFileMetadata(dto));

				then(presignedUrlService).shouldHaveNoInteractions();
			}
		}

		@Test
		@DisplayName("성공: 검증/용량/부모락/저장2회/URL 생성 후 응답 반환 (objectKey 일치 검증)")
		void success_create_file_metadata() throws Exception {
			// given
			FileUploadRequestDto dto = req(10L, 100L, 10L, 10L, 1L, "a.txt", ".txt");

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(dto.parentFolderId())).willReturn(Optional.of(parent));
			given(parent.getOwnerId()).willReturn(dto.userId());
			given(parent.getIdFullPath()).willReturn("/100/");

			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
				dto.parentFolderId(), dto.fileName(), UploadStatus.FAIL
			)).willReturn(false);

			FolderMetadata root = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findByIdForUpdate(dto.rootId())).willReturn(Optional.of(root));
			given(root.getSize()).willReturn(0L);

			willDoNothing().given(validateParentsUtil).validateParentsFolderLock(parent);

			FileMetadata fileMetadata = mock(FileMetadata.class);
			given(fileMetadata.getId()).willReturn(777L);

			FileMetadata saved = mock(FileMetadata.class);

			// UUID는 랜덤이라 "캡처"로 동일성만 검증
			ArgumentCaptor<String> objectKeyCaptor = ArgumentCaptor.forClass(String.class);

			URL presigned = new URL("https://example.com/upload");

			try (MockedStatic<FileMetadataFactory> st = mockStatic(FileMetadataFactory.class)) {
				st.when(() -> FileMetadataFactory.buildInitialMetadata(eq(parent), eq(dto), anyString()))
					.thenReturn(fileMetadata);

				given(fileMetadataJpaRepository.save(fileMetadata)).willReturn(saved);
				willDoNothing().given(saved).updateIdFullPath("/100/");
				given(fileMetadataJpaRepository.save(saved)).willReturn(saved);

				given(presignedUrlService.getPresignedUrl(objectKeyCaptor.capture())).willReturn(presigned);

				// when
				FileUploadResponseDto res = s3FileService.createFileMetadata(dto);

				// then
				assertEquals(777L, res.id());
				assertEquals(presigned, res.presignedUrl());

				String capturedKey = objectKeyCaptor.getValue();
				assertNotNull(capturedKey);
				assertFalse(capturedKey.isBlank());
				assertEquals(capturedKey, res.objectKey());

				InOrder inOrder = inOrder(folderMetadataJpaRepository, validateParentsUtil, fileMetadataJpaRepository, presignedUrlService);
				inOrder.verify(folderMetadataJpaRepository).findById(dto.parentFolderId());
				inOrder.verify(folderMetadataJpaRepository).findByIdForUpdate(dto.rootId());
				inOrder.verify(validateParentsUtil).validateParentsFolderLock(parent);
				inOrder.verify(fileMetadataJpaRepository).save(fileMetadata);
				inOrder.verify(fileMetadataJpaRepository).save(saved);
				inOrder.verify(presignedUrlService).getPresignedUrl(anyString());
			}
		}

		@Test
		@DisplayName("검증: UUID 중복이면 existsByUuidFileName가 여러 번 호출된다(루프 확인)")
		void verify_uuid_collision_loop() throws Exception {
			// given
			FileUploadRequestDto dto = req(10L, 100L, 10L, 10L, 1L, "a.txt", ".txt");

			FolderMetadata parent = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(dto.parentFolderId())).willReturn(Optional.of(parent));
			given(parent.getOwnerId()).willReturn(dto.userId());
			given(parent.getIdFullPath()).willReturn("/100/");

			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
				dto.parentFolderId(), dto.fileName(), UploadStatus.FAIL
			)).willReturn(false);

			FolderMetadata root = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findByIdForUpdate(dto.rootId())).willReturn(Optional.of(root));
			given(root.getSize()).willReturn(0L);

			willDoNothing().given(validateParentsUtil).validateParentsFolderLock(parent);

			// 핵심: 첫 exists는 true(충돌), 두번째는 false(통과)
			given(fileMetadataJpaRepository.existsByUuidFileName(anyString()))
				.willReturn(true, false);

			FileMetadata fileMetadata = mock(FileMetadata.class);
			given(fileMetadata.getId()).willReturn(1L);
			FileMetadata saved = mock(FileMetadata.class);

			try (MockedStatic<FileMetadataFactory> st = mockStatic(FileMetadataFactory.class)) {
				st.when(() -> FileMetadataFactory.buildInitialMetadata(eq(parent), eq(dto), anyString()))
					.thenReturn(fileMetadata);

				given(fileMetadataJpaRepository.save(fileMetadata)).willReturn(saved);
				willDoNothing().given(saved).updateIdFullPath("/100/");
				given(fileMetadataJpaRepository.save(saved)).willReturn(saved);

				given(presignedUrlService.getPresignedUrl(anyString()))
					.willReturn(new URL("https://example.com/upload"));

				// when
				s3FileService.createFileMetadata(dto);

				// then
				then(fileMetadataJpaRepository).should(times(2)).existsByUuidFileName(anyString());
			}
		}
	}

	// =========================================================
	// validateFile (직접 테스트)
	// =========================================================
	@Nested
	@DisplayName("validateFile")
	class ValidateFileTests {

		@Test
		@DisplayName("실패: owner 불일치 -> ACCESS_DENIED")
		void fail_owner_mismatch() {
			// given
			FileUploadRequestDto dto = req(10L, 100L, 10L, 10L, 1L, "a.txt", ".txt");
			FolderMetadata parent = mock(FolderMetadata.class);
			given(parent.getOwnerId()).willReturn(999L);

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> s3FileService.validateFile(dto, parent));
			assertEquals(ErrorCode.ACCESS_DENIED.getMessage(), ex.getMessage());

			then(fileMetadataJpaRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: 파일명 금칙어 -> INVALID_FILE_NAME")
		void fail_blacklist_in_name() {
			// given
			char bad = CommonConstant.FILE_NAME_BLACK_LIST[0];
			FileUploadRequestDto dto = req(10L, 100L, 10L, 10L, 1L, "ab" + bad + "cd", ".txt");
			FolderMetadata parent = mock(FolderMetadata.class);
			given(parent.getOwnerId()).willReturn(dto.userId());

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> s3FileService.validateFile(dto, parent));
			assertEquals(ErrorCode.INVALID_FILE_NAME.getMessage(), ex.getMessage());

			then(fileMetadataJpaRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: 확장자 금칙어 -> INVALID_FILE_NAME")
		void fail_blacklist_in_extension() {
			// given
			char bad = CommonConstant.FILE_NAME_BLACK_LIST[0];
			FileUploadRequestDto dto = req(10L, 100L, 10L, 10L, 1L, "a.txt", "" + bad);
			FolderMetadata parent = mock(FolderMetadata.class);
			given(parent.getOwnerId()).willReturn(dto.userId());

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> s3FileService.validateFile(dto, parent));
			assertEquals(ErrorCode.INVALID_FILE_NAME.getMessage(), ex.getMessage());

			then(fileMetadataJpaRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: 중복 파일명 -> FILE_NAME_DUPLICATE")
		void fail_duplicate() {
			// given
			FileUploadRequestDto dto = req(10L, 100L, 10L, 10L, 1L, "a.txt", ".txt");
			FolderMetadata parent = mock(FolderMetadata.class);
			given(parent.getOwnerId()).willReturn(dto.userId());

			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
				dto.parentFolderId(), dto.fileName(), UploadStatus.FAIL
			)).willReturn(true);

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> s3FileService.validateFile(dto, parent));
			assertEquals(ErrorCode.FILE_NAME_DUPLICATE.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("성공: owner/금칙어/중복 모두 통과")
		void success() {
			// given
			FileUploadRequestDto dto = req(10L, 100L, 10L, 10L, 1L, "a.txt", ".txt");
			FolderMetadata parent = mock(FolderMetadata.class);
			given(parent.getOwnerId()).willReturn(dto.userId());

			given(fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(
				dto.parentFolderId(), dto.fileName(), UploadStatus.FAIL
			)).willReturn(false);

			// when & then
			assertDoesNotThrow(() -> s3FileService.validateFile(dto, parent));
		}
	}

	// =========================================================
	// validateFileSize (직접 테스트)
	// =========================================================
	@Nested
	@DisplayName("validateFileSize")
	class ValidateFileSizeTests {

		@Test
		@DisplayName("실패: rootFolder(forUpdate) 없음 -> FOLDER_NOT_FOUND")
		void fail_root_not_found() {
			// given
			given(folderMetadataJpaRepository.findByIdForUpdate(1L)).willReturn(Optional.empty());

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> s3FileService.validateFileSize(10L, 1L));
			assertEquals(ErrorCode.FOLDER_NOT_FOUND.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("실패: fileSize > MAX_FILE_SIZE -> EXCEED_MAX_FILE_SIZE")
		void fail_exceed_max_file_size() {
			// given
			FolderMetadata root = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findByIdForUpdate(1L)).willReturn(Optional.of(root));

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> s3FileService.validateFileSize(101L, 1L));
			assertEquals(ErrorCode.EXCEED_MAX_FILE_SIZE.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("실패: root.size + fileSize > MAX_STORAGE_SIZE -> EXCEED_MAX_STORAGE_SIZE")
		void fail_exceed_max_storage_size() {
			// given
			FolderMetadata root = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findByIdForUpdate(1L)).willReturn(Optional.of(root));
			given(root.getSize()).willReturn(995L);

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> s3FileService.validateFileSize(10L, 1L));
			assertEquals(ErrorCode.EXCEED_MAX_STORAGE_SIZE.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("성공: 사이즈/스토리지 모두 통과")
		void success() {
			// given
			FolderMetadata root = mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findByIdForUpdate(1L)).willReturn(Optional.of(root));
			given(root.getSize()).willReturn(0L);

			// when & then
			assertDoesNotThrow(() -> s3FileService.validateFileSize(10L, 1L));
		}
	}

	// =========================================================
	// getUuidFileName (직접 테스트)
	// =========================================================
	@Nested
	@DisplayName("getUuidFileName")
	class GetUuidFileNameTests {

		@Test
		@DisplayName("성공: exists가 false면 즉시 반환하고 exists 1회 호출")
		void success_no_collision() {
			// given
			given(fileMetadataJpaRepository.existsByUuidFileName(anyString())).willReturn(false);

			// when
			String uuid = s3FileService.getUuidFileName();

			// then
			assertNotNull(uuid);
			assertFalse(uuid.isBlank());
			then(fileMetadataJpaRepository).should(times(1)).existsByUuidFileName(anyString());
		}

		@Test
		@DisplayName("성공: 중복(true) -> 재시도 -> 통과(false), exists 2회 이상 호출")
		void success_collision_then_ok() {
			// given
			given(fileMetadataJpaRepository.existsByUuidFileName(anyString()))
				.willReturn(true, true, false);

			// when
			String uuid = s3FileService.getUuidFileName();

			// then
			assertNotNull(uuid);
			then(fileMetadataJpaRepository).should(times(3)).existsByUuidFileName(anyString());
		}
	}

	// =========================================================
	// getFileUrl
	// =========================================================
	@Nested
	@DisplayName("getFileUrl")
	class GetFileUrlTests {

		@Test
		@DisplayName("실패: 파일 없음 -> FILE_NOT_FOUND")
		void fail_not_found() {
			// given
			given(fileMetadataJpaRepository.findById(1L)).willReturn(Optional.empty());

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> s3FileService.getFileUrl(1L));
			assertEquals(ErrorCode.FILE_NOT_FOUND.getMessage(), ex.getMessage());

			then(presignedUrlService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: uploadStatus != SUCCESS -> FILE_NOT_FOUND")
		void fail_upload_not_success() {
			// given
			FileMetadata file = mock(FileMetadata.class);
			given(fileMetadataJpaRepository.findById(1L)).willReturn(Optional.of(file));
			given(file.getUploadStatus()).willReturn(UploadStatus.FAIL);

			// when & then
			CustomException ex = assertThrows(CustomException.class, () -> s3FileService.getFileUrl(1L));
			assertEquals(ErrorCode.FILE_NOT_FOUND.getMessage(), ex.getMessage());

			then(presignedUrlService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("성공: SUCCESS면 downloadUrl 반환")
		void success() throws Exception {
			// given
			FileMetadata file = mock(FileMetadata.class);
			given(fileMetadataJpaRepository.findById(1L)).willReturn(Optional.of(file));
			given(file.getUploadStatus()).willReturn(UploadStatus.SUCCESS);
			given(file.getUuidFileName()).willReturn("obj-key");

			URL url = new URL("https://example.com/download");
			given(presignedUrlService.getDownloadUrl("obj-key")).willReturn(url);

			// when
			URL result = s3FileService.getFileUrl(1L);

			// then
			assertEquals(url, result);
			then(presignedUrlService).should(times(1)).getDownloadUrl("obj-key");
		}
	}

	// =========================================================
	// createComplete
	// =========================================================
	@Nested
	@DisplayName("createComplete")
	class CreateCompleteTests {

		@Test
		@DisplayName("실패: 파일 없음 -> FILE_NOT_FOUND")
		void fail_not_found() {
			// given
			given(fileMetadataJpaRepository.findById(1L)).willReturn(Optional.empty());

			// when & then
			CustomException ex = assertThrows(CustomException.class,
				() -> s3FileService.createComplete(1L, 10L, "k"));
			assertEquals(ErrorCode.FILE_NOT_FOUND.getMessage(), ex.getMessage());

			then(presignedUrlService).shouldHaveNoInteractions();
			then(fileMetadataJpaRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("실패: creatorId != userId -> WRONG_PERMISSION_TYPE")
		void fail_wrong_permission_type() {
			// given
			FileMetadata file = mock(FileMetadata.class);
			given(fileMetadataJpaRepository.findById(1L)).willReturn(Optional.of(file));
			given(file.getCreatorId()).willReturn(999L);

			// when & then
			CustomException ex = assertThrows(CustomException.class,
				() -> s3FileService.createComplete(1L, 10L, "k"));
			assertEquals(ErrorCode.WRONG_PERMISSION_TYPE.getMessage(), ex.getMessage());

			then(presignedUrlService).shouldHaveNoInteractions();
			then(fileMetadataJpaRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("실패: objectKey 불일치 -> WRONG_OBJECT_KEY")
		void fail_wrong_object_key() {
			// given
			FileMetadata file = mock(FileMetadata.class);
			given(fileMetadataJpaRepository.findById(1L)).willReturn(Optional.of(file));
			given(file.getCreatorId()).willReturn(10L);
			given(file.getUuidFileName()).willReturn("correct-key");

			// when & then
			CustomException ex = assertThrows(CustomException.class,
				() -> s3FileService.createComplete(1L, 10L, "wrong-key"));
			assertEquals(ErrorCode.WRONG_OBJECT_KEY.getMessage(), ex.getMessage());

			then(presignedUrlService).shouldHaveNoInteractions();
			then(fileMetadataJpaRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("실패: RGW contentLength != 요청 fileSize -> INVALID_FILE_SIZE + FAIL로 업데이트 후 save 1회")
		void fail_invalid_file_size_updates_fail_and_saves() {
			// given
			FileMetadata file = mock(FileMetadata.class);
			given(fileMetadataJpaRepository.findById(1L)).willReturn(Optional.of(file));
			given(file.getCreatorId()).willReturn(10L);
			given(file.getUuidFileName()).willReturn("obj-key");
			given(file.getFileSize()).willReturn(100L);

			HeadObjectResponse head = HeadObjectResponse.builder().contentLength(99L).build();
			given(presignedUrlService.getFileMetadata("obj-key")).willReturn(head);

			// when & then
			CustomException ex = assertThrows(CustomException.class,
				() -> s3FileService.createComplete(1L, 10L, "obj-key"));
			assertEquals(ErrorCode.INVALID_FILE_SIZE.getMessage(), ex.getMessage());

			then(file).should(times(1)).updateFailUploadStatus();
			then(file).should(never()).updateFinishUploadStatus();
			then(fileMetadataJpaRepository).should(times(1)).save(file);
		}

		@Test
		@DisplayName("성공: RGW contentLength == 요청 fileSize -> FINISH로 업데이트 후 save 1회")
		void success_updates_finish_and_saves() {
			// given
			FileMetadata file = mock(FileMetadata.class);
			given(fileMetadataJpaRepository.findById(1L)).willReturn(Optional.of(file));
			given(file.getCreatorId()).willReturn(10L);
			given(file.getUuidFileName()).willReturn("obj-key");
			given(file.getFileSize()).willReturn(100L);

			HeadObjectResponse head = HeadObjectResponse.builder().contentLength(100L).build();
			given(presignedUrlService.getFileMetadata("obj-key")).willReturn(head);

			// when
			assertDoesNotThrow(() -> s3FileService.createComplete(1L, 10L, "obj-key"));

			// then
			then(file).should(times(1)).updateFinishUploadStatus();
			then(file).should(never()).updateFailUploadStatus();
			then(fileMetadataJpaRepository).should(times(1)).save(file);
		}
	}
}
