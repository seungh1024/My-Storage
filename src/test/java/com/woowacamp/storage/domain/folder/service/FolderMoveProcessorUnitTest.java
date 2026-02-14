package com.woowacamp.storage.domain.folder.service;

import java.util.List;
import java.util.Optional;
import java.util.Stack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.woowacamp.storage.domain.file.repository.FileMetadataRepository;
import com.woowacamp.storage.domain.folder.entity.FolderJob;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.helper.FolderBatchUpdateHelper;
import com.woowacamp.storage.domain.folder.repository.FolderJobJpaRepository;
import com.woowacamp.storage.domain.folder.repository.FolderJobRepository;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataRepository;
import com.woowacamp.storage.domain.folder.utils.FolderJobStatus;
import com.woowacamp.storage.global.error.CustomException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class FolderMoveProcessorUnitTest {

	@InjectMocks
	private FolderMoveProcessor folderMoveProcessor;

	@Mock
	private FolderMetadataRepository folderMetadataRepository;

	@Mock
	private FolderMetadataJpaRepository folderMetadataJpaRepository;

	@Mock
	private FileMetadataRepository fileMetadataRepository;

	@Mock
	private FolderJobRepository folderJobRepository;

	@Mock
	private FolderJobJpaRepository folderJobJpaRepository;

	@Mock
	private FolderBatchUpdateHelper batchUpdateHelper;

	// Helper methods
	private FolderJob createJob(Long rootId, Long folderId, FolderJobStatus status) {
		return FolderJob.builder()
			.rootId(rootId)
			.id(folderId)
			.currentParentId(folderId)
			.lastFolderId(null)
			.lastFileId(null)
			.parentStack("[]")
			.status(status)
			.build();
	}

	private FolderMetadata createFolder(Long id, Long parentId, String idPath, String namePath) {
		return FolderMetadata.builder()
			.id(id)
			.parentFolderId(parentId)
			.idFullPath(idPath)
			.nameFullPath(namePath)
			.namePathLength(namePath.length())
			.build();
	}

	@Nested
	@DisplayName("ProcessMove 기본 동작")
	class ProcessMoveBasicTest {

		@Test
		@DisplayName("FolderJob이 존재하지 않으면 FOLDER_NOT_FOUND 예외를 던진다")
		void processMove_JobNotFound_ThrowsException() {
			// given
			Long folderId = 1L;
			given(folderJobJpaRepository.findById(folderId)).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> folderMoveProcessor.processMove(folderId))
				.isInstanceOf(CustomException.class)
				.hasMessageContaining("폴더를 찾을 수 없습니다.");
		}

		@Test
		@DisplayName("Job 획득에 실패하면 처리를 중단한다")
		void processMove_FailToAcquireJob_DoesNotProcess() {
			// given
			Long folderId = 1L;
			Long rootId = 10L;
			FolderJob job = createJob(rootId, folderId, FolderJobStatus.WAITING);

			given(folderJobJpaRepository.findById(folderId)).willReturn(Optional.of(job));
			given(folderJobRepository.tryAcquireJob(rootId, folderId)).willReturn(false);

			// when
			folderMoveProcessor.processMove(folderId);

			// then
			verify(folderJobRepository, never()).markJobCompleted(anyLong(), anyLong());
			verify(folderJobRepository, never()).markJobFailed(anyLong(), anyLong(), anyInt());
		}

		@Test
		@DisplayName("하위 폴더와 파일이 없으면 즉시 완료 처리한다")
		void processMove_NoChildren_CompletesImmediately() {
			// given
			Long folderId = 1L;
			Long rootId = 10L;
			FolderJob job = createJob(rootId, folderId, FolderJobStatus.WAITING);
			FolderMetadata root = createFolder(folderId, null, "/1/", "/root/");
			ReflectionTestUtils.setField(folderMoveProcessor, "pageSize", 100);
			ReflectionTestUtils.setField(folderMoveProcessor, "batchLimit", 500);
			ReflectionTestUtils.setField(folderMoveProcessor, "maxRetry", 3);

			given(folderJobJpaRepository.findById(folderId)).willReturn(Optional.of(job));
			given(folderJobRepository.tryAcquireJob(rootId, folderId)).willReturn(true);
			given(folderMetadataJpaRepository.findById(folderId)).willReturn(Optional.of(root));
			given(folderMetadataRepository.findByParentFolderIdWithLastId(eq(folderId), isNull(), anyInt()))
				.willReturn(List.of());
			given(fileMetadataRepository.findFileMetadataByLastId(eq(folderId), isNull(), anyInt()))
				.willReturn(List.of());

			// when
			folderMoveProcessor.processMove(folderId);

			// then
			verify(folderJobRepository, times(1)).markJobCompleted(rootId, folderId);
			verify(folderJobRepository, never()).markJobFailed(anyLong(), anyLong(), anyInt());
		}
	}

	@Nested
	@DisplayName("ProcessMove 하위 항목 처리")
	class ProcessMoveWithChildrenTest {



	}

	@Nested
	@DisplayName("ProcessMove 예외 처리")
	class ProcessMoveExceptionTest {

		@Test
		@DisplayName("하위 폴더가 있으면 배치 업데이트를 호출한다")
		void processMove_WithChildren_CallsBatchUpdate() {
			// given
			Long folderId = 1L;
			Long rootId = 10L;
			FolderJob job = createJob(rootId, folderId, FolderJobStatus.WAITING);

			FolderMetadata root = createFolder(folderId, null, "/1/", "/root/");
			FolderMetadata child1 = createFolder(2L, folderId, "/1/2/", "/root/child1/");
			FolderMetadata child2 = createFolder(3L, folderId, "/1/3/", "/root/child2/");
			List<FolderMetadata> children = List.of(child1, child2);

			ReflectionTestUtils.setField(folderMoveProcessor, "pageSize", 2);
			ReflectionTestUtils.setField(folderMoveProcessor, "batchLimit", 500);
			ReflectionTestUtils.setField(folderMoveProcessor, "maxRetry", 3);

			given(folderJobJpaRepository.findById(folderId)).willReturn(Optional.of(job));
			given(folderJobRepository.tryAcquireJob(rootId, folderId)).willReturn(true);

			// ✅ parent 조회: 1, 2, 3 모두 필요 (자식들이 스택에 push되면서 parent로 조회됨)
			given(folderMetadataJpaRepository.findById(1L)).willReturn(Optional.of(root));
			given(folderMetadataJpaRepository.findById(2L)).willReturn(Optional.of(child1));
			given(folderMetadataJpaRepository.findById(3L)).willReturn(Optional.of(child2));

			// root(1) 자식 폴더 조회
			given(folderMetadataRepository.findByParentFolderIdWithLastId(eq(1L), isNull(), eq(2)))
				.willReturn(children);
			// pageSize=2라서 size==limit이므로 cursor로 한 번 더 호출
			given(folderMetadataRepository.findByParentFolderIdWithLastId(eq(1L), eq(3L), eq(2)))
				.willReturn(List.of());

			// child1(2)의 자식 없음
			given(folderMetadataRepository.findByParentFolderIdWithLastId(eq(2L), isNull(), eq(2)))
				.willReturn(List.of());

			// child2(3)의 자식 없음
			given(folderMetadataRepository.findByParentFolderIdWithLastId(eq(3L), isNull(), eq(2)))
				.willReturn(List.of());

			// 파일 조회 (모든 부모에 대해)
			given(fileMetadataRepository.findFileMetadataByLastId(eq(1L), isNull(), eq(2)))
				.willReturn(List.of());
			given(fileMetadataRepository.findFileMetadataByLastId(eq(2L), isNull(), eq(2)))
				.willReturn(List.of());
			given(fileMetadataRepository.findFileMetadataByLastId(eq(3L), isNull(), eq(2)))
				.willReturn(List.of());

			// 배치 업데이트 호출 스냅샷 저장
			List<List<Long>> flushedSnapshots = new java.util.ArrayList<>();
			willAnswer(inv -> {
				@SuppressWarnings("unchecked")
				List<FolderMetadata> list = (List<FolderMetadata>) inv.getArgument(0);
				flushedSnapshots.add(list.stream().map(FolderMetadata::getId).toList());
				return null;
			}).given(batchUpdateHelper).batchUpdateFoldersAndSaveProgress(
				anyList(), anyLong(), anyLong(), anyLong(), any(), any(), any(Stack.class)
			);

			// when
			folderMoveProcessor.processMove(folderId);

			// then
			assertThat(flushedSnapshots)
				.as("batchUpdateHelper에 전달된 폴더 목록")
				.anySatisfy(ids -> assertThat(ids).containsExactly(2L, 3L));

			then(folderJobRepository).should(times(1)).markJobCompleted(rootId, folderId);
			then(folderJobRepository).should(never()).markJobFailed(anyLong(), anyLong(), anyInt());
		}

		@Test
		@DisplayName("배치 업데이트 중 예외 발생 시 Job을 FAILED로 표시한다")
		void processMove_BatchUpdateFails_MarksFailed() {
			// given
			Long folderId = 1L;
			Long rootId = 10L;
			FolderJob job = createJob(rootId, folderId, FolderJobStatus.WAITING);
			FolderMetadata root = createFolder(folderId, null, "/1/", "/root/");

			given(folderJobJpaRepository.findById(folderId)).willReturn(Optional.of(job));
			given(folderJobRepository.tryAcquireJob(rootId, folderId)).willReturn(true);
			given(folderMetadataJpaRepository.findById(folderId)).willReturn(Optional.of(root));
			given(folderMetadataRepository.findByParentFolderIdWithLastId(anyLong(), any(), anyInt()))
				.willThrow(new RuntimeException("DB Connection Error"));
			ReflectionTestUtils.setField(folderMoveProcessor, "maxRetry", 3);

			// when & then
			assertThatThrownBy(() -> folderMoveProcessor.processMove(folderId))
				.isInstanceOf(CustomException.class)
				.hasMessageContaining("메시지 처리에 실패했습니다.");

			verify(folderJobRepository, times(1)).markJobFailed(rootId, folderId, 3);
		}
	}
}
