package com.woowacamp.storage.domain.folder.repository;

import java.util.LinkedHashMap;
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

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FolderMetadataRepositoryIntegrationTest extends IntegrationTestBase {

	@Autowired
	private FolderMetadataRepository folderMetadataRepository;

	@BeforeEach
	void setUp() {
		cleanup();
		folderTreeSetUp.setupFolderTree();
	}

	@Test
	@DisplayName("batchUpdateSizeDeltas: 여러 id delta를 한 번에 반영한다")
	void batchUpdateSizeDeltas_updates_multiple_ids() {
		FolderMetadata sourceFolder = folderTreeSetUp.getSubFolders().get(0);
		FolderMetadata targetFolder = folderTreeSetUp.getSubFolders().get(1);

		long sourceSizeBefore = folderMetadataJpaRepository.findById(sourceFolder.getId()).orElseThrow().getSize();
		long targetSizeBefore = folderMetadataJpaRepository.findById(targetFolder.getId()).orElseThrow().getSize();

		Map<Long, Long> deltaByFolderId = new LinkedHashMap<>();
		deltaByFolderId.put(sourceFolder.getId(), -200L);
		deltaByFolderId.put(targetFolder.getId(), 200L);
		deltaByFolderId.put(folderTreeSetUp.getRootFolder().getId(), 0L);

		folderMetadataRepository.batchUpdateSizeDeltas(deltaByFolderId);

		long sourceSizeAfter = folderMetadataJpaRepository.findById(sourceFolder.getId()).orElseThrow().getSize();
		long targetSizeAfter = folderMetadataJpaRepository.findById(targetFolder.getId()).orElseThrow().getSize();
		long rootSizeAfter = folderMetadataJpaRepository.findById(folderTreeSetUp.getRootFolder().getId()).orElseThrow()
			.getSize();

		assertThat(sourceSizeAfter).isEqualTo(sourceSizeBefore - 200L);
		assertThat(targetSizeAfter).isEqualTo(targetSizeBefore + 200L);
		assertThat(rootSizeAfter).isEqualTo(folderTreeSetUp.getRootFolder().getSize());
	}

	@Test
	@DisplayName("batchUpdateSizeDeltas: null/empty/전체0은 변경 없이 종료된다")
	void batchUpdateSizeDeltas_noop_cases() {
		FolderMetadata folder = folderTreeSetUp.getSubFolders().get(0);
		long before = folderMetadataJpaRepository.findById(folder.getId()).orElseThrow().getSize();

		folderMetadataRepository.batchUpdateSizeDeltas(null);
		folderMetadataRepository.batchUpdateSizeDeltas(Map.of());
		folderMetadataRepository.batchUpdateSizeDeltas(Map.of(folder.getId(), 0L));

		long after = folderMetadataJpaRepository.findById(folder.getId()).orElseThrow().getSize();
		assertThat(after).isEqualTo(before);
	}
}
