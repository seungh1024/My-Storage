package com.woowacamp.storage.domain.dummy;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.global.util.StorageStringUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Repository
@RequiredArgsConstructor
@Slf4j
public class FolderDummyRepository {
	private final JdbcTemplate jdbcTemplate;

	public void saveAll(List<FolderMetadata> folderMetadataList) {
		String sql = StorageStringUtil.format("""
			INSERT INTO folder_metadata (folder_metadata_id, root_id, owner_id, creator_id, created_at,
			updated_at, parent_folder_id, upload_folder_name, folder_size, sharing_expired_at,
			permission_type, is_deleted, name_full_path, id_full_path,version)
						
			VALUES(?,?,?,?,?,
			?,?,?,?,?,
			?,?,?,?,0)
			""");

		jdbcTemplate.batchUpdate(sql, folderMetadataList, folderMetadataList.size(),
			(PreparedStatement ps, FolderMetadata folderMetadata) -> {
				ps.setLong(1, folderMetadata.getId());
				ps.setLong(2, folderMetadata.getRootId());
				ps.setLong(3, folderMetadata.getOwnerId());
				ps.setLong(4, folderMetadata.getCreatorId());
				ps.setTimestamp(5, Timestamp.valueOf(folderMetadata.getCreatedAt()));
				ps.setTimestamp(6, Timestamp.valueOf(folderMetadata.getUpdatedAt()));
				setNullableLong(ps, 7, folderMetadata.getParentFolderId());
				ps.setString(8, folderMetadata.getUploadFolderName());
				ps.setLong(9, folderMetadata.getSize());
				ps.setTimestamp(10, Timestamp.valueOf(folderMetadata.getSharingExpiredAt()));
				ps.setString(11, folderMetadata.getPermissionType().name());
				ps.setBoolean(12, folderMetadata.isDeleted());
				ps.setString(13, folderMetadata.getNameFullPath());
				ps.setString(14, folderMetadata.getIdFullPath());
			});

		log.info("[Batch FolderMetadata Insert] last id: {}",
			folderMetadataList.get(folderMetadataList.size() - 1).getId());
	}

	private void setNullableLong(PreparedStatement ps, int idx, Long value) throws SQLException {
		if (value == null)
			ps.setNull(idx, java.sql.Types.BIGINT);
		else
			ps.setLong(idx, value);
	}

	public void batchUpdateNamePathLength(List<FolderMetadata> folderMetadataList) {
		String sql = """
			UPDATE folder_metadata
			SET name_path_length = ?
			WHERE folder_metadata_id = ?
			     """;

		jdbcTemplate.batchUpdate(sql, folderMetadataList, folderMetadataList.size(), (PreparedStatement ps, FolderMetadata folderMetadata)  -> {
			ps.setInt(1, folderMetadata.getNamePathLength());
			ps.setLong(2, folderMetadata.getId());
		});

		log.info("[Batch FolderMetadata Update] size: {}, last id: {}", folderMetadataList.size(), folderMetadataList.get(folderMetadataList.size() - 1).getId());
	}

}
