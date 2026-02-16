package com.woowacamp.storage.domain.folder.repository;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class FolderMetadataRepositoryUnitTest {

	@InjectMocks
	private FolderMetadataRepository folderMetadataRepository;

	@Mock
	private FolderMetadataJpaRepository folderMetadataJpaRepository;

	@Mock
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("batchUpdateSizeDeltas: 0이 아닌 delta만 batchUpdate로 전달한다")
	void batchUpdateSizeDeltas_filters_zero_and_updates_once() {
		Map<Long, Long> deltaByFolderId = new LinkedHashMap<>();
		deltaByFolderId.put(10L, -100L);
		deltaByFolderId.put(20L, 0L);
		deltaByFolderId.put(30L, 100L);

		given(jdbcTemplate.batchUpdate(anyString(), anyList(), anyInt(), any())).willReturn(new int[][]{{1, 1}});

		folderMetadataRepository.batchUpdateSizeDeltas(deltaByFolderId);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Map.Entry<Long, Long>>> entriesCaptor = ArgumentCaptor.forClass(List.class);
		then(jdbcTemplate).should(times(1)).batchUpdate(anyString(), entriesCaptor.capture(), eq(2), any());

		List<Map.Entry<Long, Long>> entries = entriesCaptor.getValue();
		assertEquals(2, entries.size());
		assertEquals(10L, entries.get(0).getKey());
		assertEquals(-100L, entries.get(0).getValue());
		assertEquals(30L, entries.get(1).getKey());
		assertEquals(100L, entries.get(1).getValue());
	}

	@Test
	@DisplayName("batchUpdateSizeDeltas: null/empty/전체0 delta면 batchUpdate를 호출하지 않는다")
	void batchUpdateSizeDeltas_noop_cases() {
		folderMetadataRepository.batchUpdateSizeDeltas(null);
		folderMetadataRepository.batchUpdateSizeDeltas(Map.of());
		folderMetadataRepository.batchUpdateSizeDeltas(Map.of(10L, 0L, 20L, 0L));

		then(jdbcTemplate).shouldHaveNoInteractions();
	}
}

