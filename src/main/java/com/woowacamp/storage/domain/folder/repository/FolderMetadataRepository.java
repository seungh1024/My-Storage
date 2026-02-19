package com.woowacamp.storage.domain.folder.repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class FolderMetadataRepository {
	private static final String BATCH_UPDATE_FOLDER_SIZE_SQL = """
        UPDATE folder_metadata
        SET folder_size = folder_size + ?, updated_at = CURRENT_TIMESTAMP
        WHERE folder_metadata_id = ?
        """;

	private final FolderMetadataJpaRepository folderMetadataJpaRepository;
	private final JdbcTemplate jdbcTemplate;

	public List<FolderMetadata> findByParentFolderIdWithLastId(long parentFolderId, Long lastId, int size) {
		if (lastId == null) {
			return folderMetadataJpaRepository.findByParentFolderId(parentFolderId, size);
		}

		return folderMetadataJpaRepository.findByParentFolderIdWithLastId(parentFolderId, lastId, size);
	}

	public List<FolderMetadata> findSoftDeletedFolderWithLastId(Long lastId, int size) {
		if (lastId == null) {
			return folderMetadataJpaRepository.findSoftDeletedFolder(size);
		}
		return folderMetadataJpaRepository.findSoftDeletedFolderWithLastId(lastId, size);
	}

	public List<FolderMetadata> findSoftDeletedFolderWithLastIdAndDuration(Long lastId, int size, LocalDateTime timeLimit) {
		if (lastId == null) {
			return folderMetadataJpaRepository.findSoftDeletedFolder(size, timeLimit);
		}
		return folderMetadataJpaRepository.findSoftDeletedFolderWithLastId(lastId, size, timeLimit);
	}

	public void deleteAll(List<FolderMetadata> folderMetadataList) {
		folderMetadataJpaRepository.deleteAllByIdInBatch(folderMetadataList.stream().map(FolderMetadata::getId).toList());
	}

	public void batchUpdateSizeDeltas(Map<Long, Long> deltaByFolderId) {
		if (deltaByFolderId == null || deltaByFolderId.isEmpty()) {
			return;
		}

		List<Map.Entry<Long, Long>> updateEntries = new ArrayList<>();
		for (Map.Entry<Long, Long> entry : deltaByFolderId.entrySet()) {
			if (entry.getValue() == 0L) {
				continue;
			}
			updateEntries.add(entry);
		}
		if (updateEntries.isEmpty()) {
			return;
		}

		jdbcTemplate.batchUpdate(
			BATCH_UPDATE_FOLDER_SIZE_SQL,
			updateEntries,
			updateEntries.size(),
			(ps, entry) -> {
				ps.setLong(1, entry.getValue());
				ps.setLong(2, entry.getKey());
			}
		);
	}

}
