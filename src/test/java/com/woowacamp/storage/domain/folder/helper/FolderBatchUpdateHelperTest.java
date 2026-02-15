package com.woowacamp.storage.domain.folder.helper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderJobJpaRepository;

@ExtendWith(MockitoExtension.class)
class FolderBatchUpdateHelperTest {

	@InjectMocks
	private FolderBatchUpdateHelper batchUpdateHelper;

	@Mock
	private JdbcTemplate jdbcTemplate;

	@Mock
	private FolderJobJpaRepository folderJobJpaRepository;

	@Mock
	private Clock appClock;

	@Captor
	private ArgumentCaptor<String> sqlCaptor;

	@BeforeEach
	void setUp() {
		given(appClock.getZone()).willReturn(ZoneOffset.UTC);
		given(appClock.instant()).willReturn(Instant.parse("2026-02-15T00:00:00Z"));
	}

	@Nested
	@DisplayName("batchUpdateFoldersAndSaveProgress 테스트")
	class BatchUpdateFoldersTest {

		@Test
		@DisplayName("폴더 리스트가 비어있으면 배치 업데이트를 하지 않는다")
		void batchUpdateFolders_EmptyList_DoesNotExecuteBatch() {
			// given
			List<FolderMetadata> emptyList = new ArrayList<>();
			Stack<Long> stack = new Stack<>();
			stack.push(1L);

			// when
			batchUpdateHelper.batchUpdateFoldersAndSaveProgress(
				emptyList, 1L, 10L, 1L, null, null, stack);

			// then
			verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList(), anyInt(), any());
			verify(folderJobJpaRepository, times(1)).updateProgress(
				eq(1L), eq(10L), eq(1L), isNull(), isNull(), anyString(), any());
		}

		@Test
		@DisplayName("폴더 리스트가 null이면 배치 업데이트를 하지 않는다")
		void batchUpdateFolders_NullList_DoesNotExecuteBatch() {
			// given
			Stack<Long> stack = new Stack<>();
			stack.push(1L);

			// when
			batchUpdateHelper.batchUpdateFoldersAndSaveProgress(
				null, 1L, 10L, 1L, null, null, stack);

			// then
			verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList(), anyInt(), any());
			verify(folderJobJpaRepository, times(1)).updateProgress(
				anyLong(), anyLong(), anyLong(), any(), any(), anyString(), any());
		}

		@Test
		@DisplayName("폴더 리스트를 배치 업데이트하고 진행 상황을 저장한다")
		void batchUpdateFolders_Success() {
			// given
			List<FolderMetadata> folders = List.of(
				createFolder(1L, "/1/", "/root/folder1/", 20),
				createFolder(2L, "/1/2/", "/root/folder1/folder2/", 30)
			);
			Stack<Long> stack = new Stack<>();
			stack.push(1L);

			// batchUpdate는 int[][] 리턴 (각 배치의 영향받은 행 수)
			given(jdbcTemplate.batchUpdate(anyString(), anyList(), anyInt(), any()))
				.willReturn(new int[][]{{1, 1}}); // 하나의 배치에서 2개 처리

			// when
			batchUpdateHelper.batchUpdateFoldersAndSaveProgress(
				folders, 1L, 10L, 1L, 2L, null, stack);

			// then
			verify(jdbcTemplate, times(1)).batchUpdate(
				sqlCaptor.capture(), eq(folders), eq(2), any(ParameterizedPreparedStatementSetter.class));

			String executedSql = sqlCaptor.getValue();
			assertThat(executedSql).contains("UPDATE folder_metadata");
			assertThat(executedSql).contains("id_full_path");
			assertThat(executedSql).contains("name_full_path");

			verify(folderJobJpaRepository, times(1)).updateProgress(
				eq(1L), eq(10L), eq(1L), eq(2L), isNull(), eq("[1]"), any());
		}

		@Test
		@DisplayName("대량 폴더 배치 업데이트 시 여러 배치로 나뉘어 처리된다")
		void batchUpdateFolders_LargeBatch_ProcessedInMultipleBatches() {
			// given
			List<FolderMetadata> folders = new ArrayList<>();
			for (long i = 1; i <= 5; i++) {
				folders.add(createFolder(i, "/" + i + "/", "/root/folder" + i + "/", 20));
			}
			Stack<Long> stack = new Stack<>();

			// batchSize=2일 때: [[1,1], [1,1], [1]] 형태로 리턴
			given(jdbcTemplate.batchUpdate(anyString(), anyList(), anyInt(), any()))
				.willReturn(new int[][]{{1, 1}, {1, 1}, {1}});

			// when
			batchUpdateHelper.batchUpdateFoldersAndSaveProgress(
				folders, 1L, 10L, 1L, 5L, null, stack);

			// then
			verify(jdbcTemplate, times(1)).batchUpdate(anyString(), eq(folders), eq(5), any());
			verify(folderJobJpaRepository, times(1)).updateProgress(
				anyLong(), anyLong(), anyLong(), anyLong(), any(), anyString(), any());
		}

		private FolderMetadata createFolder(Long id, String idPath, String namePath, int pathLength) {
			return FolderMetadata.builder()
				.id(id)
				.idFullPath(idPath)
				.nameFullPath(namePath)
				.namePathLength(pathLength)
				.build();
		}
	}

	@Nested
	@DisplayName("batchUpdateFilesAndSaveProgress 테스트")
	class BatchUpdateFilesTest {

		@Test
		@DisplayName("파일 리스트가 비어있으면 배치 업데이트를 하지 않는다")
		void batchUpdateFiles_EmptyList_DoesNotExecuteBatch() {
			// given
			List<FileMetadata> emptyList = new ArrayList<>();
			Stack<Long> stack = new Stack<>();

			// when
			batchUpdateHelper.batchUpdateFilesAndSaveProgress(
				emptyList, 1L, 10L, 1L, null, null, stack);

			// then
			verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList(), anyInt(), any());
			verify(folderJobJpaRepository, times(1)).updateProgress(
				anyLong(), anyLong(), anyLong(), any(), any(), anyString(), any());
		}

		@Test
		@DisplayName("파일 리스트를 배치 업데이트하고 진행 상황을 저장한다")
		void batchUpdateFiles_Success() {
			// given
			List<FileMetadata> files = List.of(
				createFile(1L, "/1/", "/root/file1.txt", 20),
				createFile(2L, "/1/", "/root/file2.txt", 20)
			);
			Stack<Long> stack = new Stack<>();

			given(jdbcTemplate.batchUpdate(anyString(), anyList(), anyInt(), any()))
				.willReturn(new int[][]{{1, 1}});

			// when
			batchUpdateHelper.batchUpdateFilesAndSaveProgress(
				files, 1L, 10L, 1L, null, 2L, stack);

			// then
			verify(jdbcTemplate, times(1)).batchUpdate(
				sqlCaptor.capture(), eq(files), eq(2), any(ParameterizedPreparedStatementSetter.class));

			String executedSql = sqlCaptor.getValue();
			assertThat(executedSql).contains("UPDATE file_metadata");

			verify(folderJobJpaRepository, times(1)).updateProgress(
				eq(1L), eq(10L), eq(1L), isNull(), eq(2L), eq("[]"), any());
		}

		private FileMetadata createFile(Long id, String idPath, String namePath, int pathLength) {
			return FileMetadata.builder()
				.id(id)
				.idFullPath(idPath)
				.nameFullPath(namePath)
				.namePathLength(pathLength)
				.build();
		}
	}

	@Nested
	@DisplayName("saveProgressOnly 테스트")
	class SaveProgressOnlyTest {

		@Test
		@DisplayName("진행 상황만 저장한다")
		void saveProgressOnly_Success() {
			// given
			Stack<Long> stack = new Stack<>();
			stack.push(1L);
			stack.push(5L);

			// when
			batchUpdateHelper.saveProgressOnly(1L, 10L, 5L, 3L, null, stack);

			// then
			verify(folderJobJpaRepository, times(1)).updateProgress(
				eq(1L), eq(10L), eq(5L), eq(3L), isNull(), eq("[1,5]"), any());
			verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList(), anyInt(), any());
		}
	}
}
