package com.woowacamp.storage.domain.folderoperation.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class FolderOperationStateRepositoryUnitTest {

	@InjectMocks
	private FolderOperationStateRepository folderOperationStateRepository;

	@Mock
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("insertActiveMove: ACTIVE MOVE 상태로 insert 한다")
	void insertActiveMove_success() {
		given(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any(), any())).willReturn(1);

		int inserted = folderOperationStateRepository.insertActiveMove(1L, 10L, "/1/10/", 120, 1000L);

		assertThat(inserted).isEqualTo(1);
		then(jdbcTemplate).should(times(1)).update(
			anyString(),
			eq(1L),
			eq(10L),
			eq("MOVE"),
			eq("ACTIVE"),
			eq("/1/10/"),
			eq(120),
			eq(1000L)
		);
	}

	@Test
	@DisplayName("batchUpdateActiveMoveProjectedMaxNamePathLengthIfLessThan: map 엔트리를 batchUpdate로 전달한다")
	void batchUpdateActiveMoveProjectedMaxNamePathLengthIfLessThan_success() {
		Map<Long, Integer> updates = new LinkedHashMap<>();
		updates.put(10L, 130);
		updates.put(20L, 140);

		given(jdbcTemplate.batchUpdate(anyString(), anyList(), anyInt(), any())).willReturn(new int[][]{{1, 1}});

		folderOperationStateRepository.batchUpdateActiveMoveProjectedMaxNamePathLengthIfLessThan(1L, updates);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Map.Entry<Long, Integer>>> entriesCaptor = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		then(jdbcTemplate).should(times(1)).batchUpdate(sqlCaptor.capture(), entriesCaptor.capture(), eq(2), any());

		assertThat(sqlCaptor.getValue()).contains("UPDATE folder_operation_state");
		assertThat(sqlCaptor.getValue()).contains("projected_max_name_path_length");
		assertThat(entriesCaptor.getValue()).hasSize(2);
		assertThat(entriesCaptor.getValue().get(0).getKey()).isEqualTo(10L);
		assertThat(entriesCaptor.getValue().get(0).getValue()).isEqualTo(130);
		assertThat(entriesCaptor.getValue().get(1).getKey()).isEqualTo(20L);
		assertThat(entriesCaptor.getValue().get(1).getValue()).isEqualTo(140);
	}
}
