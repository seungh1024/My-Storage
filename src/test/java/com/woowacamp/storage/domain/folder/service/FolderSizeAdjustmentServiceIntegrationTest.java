package com.woowacamp.storage.domain.folder.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.woowacamp.storage.config.IntegrationTestBase;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.utils.FolderPathParser;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FolderSizeAdjustmentServiceIntegrationTest extends IntegrationTestBase {

	@Autowired
	private FolderSizeAdjustmentService folderSizeAdjustmentService;

	@BeforeEach
	void setUp() {
		cleanup();
		folderTreeSetUp.setupFolderTree();
	}

	@Test
	@DisplayName("merge + apply: source는 감소, target은 증가로 반영된다")
	void merge_and_apply_move_delta() {
		FolderMetadata sourceFolder = folderTreeSetUp.getSubFolders().get(0);
		FolderMetadata targetFolder = folderTreeSetUp.getSubFolders().get(1);

		List<Long> sourceIds = parsePathIds(sourceFolder.getIdFullPath());
		List<Long> targetIds = parsePathIds(targetFolder.getIdFullPath());
		Map<Long, Long> deltaByFolderId = folderSizeAdjustmentService.mergeDeltaByFolderIds(sourceIds, targetIds, 300L);

		long sourceSizeBefore = folderMetadataJpaRepository.findById(sourceFolder.getId()).orElseThrow().getSize();
		long targetSizeBefore = folderMetadataJpaRepository.findById(targetFolder.getId()).orElseThrow().getSize();

		folderSizeAdjustmentService.applySizeDeltas(deltaByFolderId);

		long sourceSizeAfter = folderMetadataJpaRepository.findById(sourceFolder.getId()).orElseThrow().getSize();
		long targetSizeAfter = folderMetadataJpaRepository.findById(targetFolder.getId()).orElseThrow().getSize();

		assertThat(sourceSizeAfter).isEqualTo(sourceSizeBefore - 300L);
		assertThat(targetSizeAfter).isEqualTo(targetSizeBefore + 300L);
	}

	@Test
	@DisplayName("applySizeDeltas: null/empty/전체0은 반영하지 않는다")
	void apply_size_deltas_noop_cases() {
		FolderMetadata folder = folderTreeSetUp.getSubFolders().get(0);
		long before = folderMetadataJpaRepository.findById(folder.getId()).orElseThrow().getSize();

		folderSizeAdjustmentService.applySizeDeltas(null);
		folderSizeAdjustmentService.applySizeDeltas(Map.of());
		folderSizeAdjustmentService.applySizeDeltas(Map.of(folder.getId(), 0L));

		long after = folderMetadataJpaRepository.findById(folder.getId()).orElseThrow().getSize();
		assertThat(after).isEqualTo(before);
	}

	private List<Long> parsePathIds(String idFullPath) {
		return FolderPathParser.parsing(idFullPath)
			.orElseThrow()
			.stream()
			.map(Long::parseLong)
			.toList();
	}
}

