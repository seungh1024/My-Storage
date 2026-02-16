package com.woowacamp.storage.domain.folderoperation.repository;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.woowacamp.storage.domain.folderoperation.entity.FolderOperationStatus;
import com.woowacamp.storage.domain.folderoperation.entity.FolderOperationType;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class FolderOperationStateRepository {
	private static final String INSERT_ACTIVE_MOVE_SQL = """
        INSERT INTO folder_operation_state (
            root_id, folder_id, operation_type, operation_state, root_id_full_path,
            projected_max_name_path_length, job_id, created_at, updated_at
        )
        VALUES (
            ?, ?, ?, ?, ?,
            ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
        )
        """;

	private static final String UPDATE_ACTIVE_MOVE_PROJECTED_MAX_NAME_PATH_LENGTH_SQL = """
        UPDATE folder_operation_state
        SET projected_max_name_path_length = ?, updated_at = CURRENT_TIMESTAMP
        WHERE root_id = ?
          AND folder_id = ?
          AND operation_type = ?
          AND operation_state = ?
          AND projected_max_name_path_length < ?
        """;

	private final JdbcTemplate jdbcTemplate;

	public int insertActiveMove(Long rootId, Long folderId, String rootIdFullPath, int projectedMaxNamePathLength,
		Long jobId) {
		return jdbcTemplate.update(
			INSERT_ACTIVE_MOVE_SQL,
			rootId,
			folderId,
			FolderOperationType.MOVE.name(),
			FolderOperationStatus.ACTIVE.name(),
			rootIdFullPath,
			projectedMaxNamePathLength,
			jobId
		);
	}

	public void batchUpdateActiveMoveProjectedMaxNamePathLengthIfLessThan(Long rootId,
		Map<Long, Integer> projectedMaxNamePathLengthByFolderId) {
		List<Map.Entry<Long, Integer>> updateEntries = projectedMaxNamePathLengthByFolderId.entrySet().stream().toList();
		jdbcTemplate.batchUpdate(
			UPDATE_ACTIVE_MOVE_PROJECTED_MAX_NAME_PATH_LENGTH_SQL,
			updateEntries,
			updateEntries.size(),
			(ps, entry) -> {
				int projectedMaxNamePathLength = entry.getValue();
				ps.setInt(1, projectedMaxNamePathLength);
				ps.setLong(2, rootId);
				ps.setLong(3, entry.getKey());
				ps.setString(4, FolderOperationType.MOVE.name());
				ps.setString(5, FolderOperationStatus.ACTIVE.name());
				ps.setInt(6, projectedMaxNamePathLength);
			}
		);
	}
}
