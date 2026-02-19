package com.woowacamp.storage.domain.folder.service;

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

import com.woowacamp.storage.domain.folder.repository.FolderMetadataRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class FolderSizeAdjustmentServiceTest {

	@InjectMocks
	private FolderSizeAdjustmentService folderSizeAdjustmentService;

	@Mock
	private FolderMetadataRepository folderMetadataRepository;

	@Test
	@DisplayName("mergeDeltaByFolderIds: source(-)와 target(+)를 합쳐 id별 최종 delta를 만든다")
	void merge_delta_by_folder_ids() {
		Map<Long, Long> deltaByFolderId = folderSizeAdjustmentService.mergeDeltaByFolderIds(
			List.of(10L, 20L),
			List.of(20L, 30L),
			100L
		);

		assertEquals(3, deltaByFolderId.size());
		assertEquals(-100L, deltaByFolderId.get(10L));
		assertEquals(0L, deltaByFolderId.get(20L));
		assertEquals(100L, deltaByFolderId.get(30L));
	}

	@Test
	@DisplayName("applySizeDeltas: 0이 아닌 delta만 repository로 전달한다")
	void apply_size_deltas_filters_zero() {
		Map<Long, Long> deltaByFolderId = new LinkedHashMap<>();
		deltaByFolderId.put(10L, -100L);
		deltaByFolderId.put(20L, 0L);
		deltaByFolderId.put(30L, 100L);

		folderSizeAdjustmentService.applySizeDeltas(deltaByFolderId);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<Long, Long>> deltaCaptor = ArgumentCaptor.forClass(Map.class);
		then(folderMetadataRepository).should(times(1)).batchUpdateSizeDeltas(deltaCaptor.capture());

		Map<Long, Long> filteredDeltaByFolderId = deltaCaptor.getValue();
		assertEquals(2, filteredDeltaByFolderId.size());
		assertEquals(-100L, filteredDeltaByFolderId.get(10L));
		assertEquals(100L, filteredDeltaByFolderId.get(30L));
	}

	@Test
	@DisplayName("applySizeDeltas: null/empty/전체 0인 경우 repository를 호출하지 않는다")
	void apply_size_deltas_noop_cases() {
		folderSizeAdjustmentService.applySizeDeltas(null);
		folderSizeAdjustmentService.applySizeDeltas(Map.of());
		folderSizeAdjustmentService.applySizeDeltas(Map.of(10L, 0L, 20L, 0L));

		then(folderMetadataRepository).shouldHaveNoInteractions();
	}
}

