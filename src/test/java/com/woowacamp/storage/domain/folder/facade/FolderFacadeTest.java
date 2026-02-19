package com.woowacamp.storage.domain.folder.facade;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.woowacamp.storage.domain.folder.dto.command.CreateLockContext;
import com.woowacamp.storage.domain.folder.dto.command.MoveLockContext;
import com.woowacamp.storage.domain.folder.dto.request.CreateFolderReqDto;
import com.woowacamp.storage.domain.folder.dto.request.FolderMoveDto;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.service.FolderService;
import com.woowacamp.storage.global.error.CustomException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class FolderFacadeTest {

	@InjectMocks
	private FolderFacade folderFacade;

	@Mock
	private FolderMetadataJpaRepository folderMetadataJpaRepository;

	@Mock
	private FolderService folderService;

	private FolderMoveDto moveDto(long userId, long targetFolderId, long rootId, String folderName) {
		return new FolderMoveDto(userId, targetFolderId, rootId, folderName);
	}

	private CreateFolderReqDto createReq(long userId, long rootId, long parentFolderId, String name, long creatorId) {
		return new CreateFolderReqDto(userId, rootId, parentFolderId, name, creatorId);
	}

	@Nested
	@DisplayName("moveFolder")
	class MoveFolderTest {

		@Test
		@DisplayName("성공: source rootId를 사용해서 lockContext를 생성한다")
		void success_use_source_root_id() {
			Long sourceFolderId = 1L;
			FolderMoveDto dto = moveDto(100L, 2L, 999L, "ignored");
			FolderMetadata sourceFolder = org.mockito.Mockito.mock(FolderMetadata.class);
			FolderMetadata targetFolder = org.mockito.Mockito.mock(FolderMetadata.class);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceFolderId)).willReturn(Optional.of(sourceFolder));
			given(folderMetadataJpaRepository.findByIdNotDeleted(dto.targetFolderId())).willReturn(Optional.of(targetFolder));
			given(sourceFolder.getRootId()).willReturn(300L);
			given(sourceFolder.getUploadFolderName()).willReturn("source-folder");
			given(targetFolder.getId()).willReturn(dto.targetFolderId());

			folderFacade.moveFolder(sourceFolderId, dto);

			ArgumentCaptor<MoveLockContext> captor = ArgumentCaptor.forClass(MoveLockContext.class);
			then(folderService).should().moveFolder(captor.capture(), org.mockito.Mockito.eq(dto));
			MoveLockContext lockContext = captor.getValue();
			assertEquals(sourceFolderId, lockContext.sourceFolderId());
			assertEquals(dto.targetFolderId(), lockContext.targetFolderId());
			assertEquals(300L, lockContext.rootId());
			assertEquals("source-folder", lockContext.folderName());
		}

		@Test
		@DisplayName("성공: source rootId가 null이면 source id를 rootId로 사용한다")
		void success_fallback_to_source_id_when_root_is_null() {
			Long sourceFolderId = 1L;
			FolderMoveDto dto = moveDto(100L, 2L, 999L, "ignored");
			FolderMetadata sourceFolder = org.mockito.Mockito.mock(FolderMetadata.class);
			FolderMetadata targetFolder = org.mockito.Mockito.mock(FolderMetadata.class);

			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceFolderId)).willReturn(Optional.of(sourceFolder));
			given(folderMetadataJpaRepository.findByIdNotDeleted(dto.targetFolderId())).willReturn(Optional.of(targetFolder));
			given(sourceFolder.getRootId()).willReturn(null);
			given(sourceFolder.getId()).willReturn(sourceFolderId);
			given(sourceFolder.getUploadFolderName()).willReturn("source-folder");
			given(targetFolder.getId()).willReturn(dto.targetFolderId());

			folderFacade.moveFolder(sourceFolderId, dto);

			ArgumentCaptor<MoveLockContext> captor = ArgumentCaptor.forClass(MoveLockContext.class);
			then(folderService).should().moveFolder(captor.capture(), org.mockito.Mockito.eq(dto));
			assertEquals(sourceFolderId, captor.getValue().rootId());
		}

		@Test
		@DisplayName("실패: source 폴더가 없으면 예외를 던지고 서비스 호출하지 않는다")
		void fail_source_not_found() {
			Long sourceFolderId = 1L;
			FolderMoveDto dto = moveDto(100L, 2L, 999L, "ignored");
			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceFolderId)).willReturn(Optional.empty());

			assertThrows(CustomException.class, () -> folderFacade.moveFolder(sourceFolderId, dto));

			then(folderService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("실패: target 폴더가 없으면 예외를 던지고 서비스 호출하지 않는다")
		void fail_target_not_found() {
			Long sourceFolderId = 1L;
			FolderMoveDto dto = moveDto(100L, 2L, 999L, "ignored");
			FolderMetadata sourceFolder = org.mockito.Mockito.mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findByIdNotDeleted(sourceFolderId)).willReturn(Optional.of(sourceFolder));
			given(folderMetadataJpaRepository.findByIdNotDeleted(dto.targetFolderId())).willReturn(Optional.empty());

			assertThrows(CustomException.class, () -> folderFacade.moveFolder(sourceFolderId, dto));

			then(folderService).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("createFolder")
	class CreateFolderTest {

		@Test
		@DisplayName("성공: parent rootId를 사용해서 lockContext를 생성한다")
		void success_use_parent_root_id() {
			CreateFolderReqDto req = createReq(100L, 1L, 10L, "new-folder", 100L);
			FolderMetadata parentFolder = org.mockito.Mockito.mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(req.parentFolderId())).willReturn(Optional.of(parentFolder));
			given(parentFolder.getId()).willReturn(req.parentFolderId());
			given(parentFolder.getRootId()).willReturn(50L);
			given(folderService.createFolder(any(CreateLockContext.class), org.mockito.Mockito.eq(req))).willReturn(999L);

			Long createdId = folderFacade.createFolder(req);

			ArgumentCaptor<CreateLockContext> captor = ArgumentCaptor.forClass(CreateLockContext.class);
			then(folderService).should().createFolder(captor.capture(), org.mockito.Mockito.eq(req));
			CreateLockContext lockContext = captor.getValue();
			assertEquals(req.parentFolderId(), lockContext.parentFolderId());
			assertEquals(50L, lockContext.rootId());
			assertEquals(req.uploadFolderName(), lockContext.folderName());
			assertEquals(999L, createdId);
		}

		@Test
		@DisplayName("성공: parent rootId가 null이면 parent id를 rootId로 사용한다")
		void success_fallback_to_parent_id_when_root_is_null() {
			CreateFolderReqDto req = createReq(100L, 1L, 10L, "new-folder", 100L);
			FolderMetadata parentFolder = org.mockito.Mockito.mock(FolderMetadata.class);
			given(folderMetadataJpaRepository.findById(req.parentFolderId())).willReturn(Optional.of(parentFolder));
			given(parentFolder.getId()).willReturn(req.parentFolderId());
			given(parentFolder.getRootId()).willReturn(null);
			given(folderService.createFolder(any(CreateLockContext.class), org.mockito.Mockito.eq(req))).willReturn(999L);

			folderFacade.createFolder(req);

			ArgumentCaptor<CreateLockContext> captor = ArgumentCaptor.forClass(CreateLockContext.class);
			then(folderService).should().createFolder(captor.capture(), org.mockito.Mockito.eq(req));
			assertEquals(req.parentFolderId(), captor.getValue().rootId());
		}

		@Test
		@DisplayName("실패: parent 폴더가 없으면 예외를 던지고 서비스 호출하지 않는다")
		void fail_parent_not_found() {
			CreateFolderReqDto req = createReq(100L, 1L, 10L, "new-folder", 100L);
			given(folderMetadataJpaRepository.findById(req.parentFolderId())).willReturn(Optional.empty());

			assertThrows(CustomException.class, () -> folderFacade.createFolder(req));

			then(folderService).shouldHaveNoInteractions();
		}
	}
}
