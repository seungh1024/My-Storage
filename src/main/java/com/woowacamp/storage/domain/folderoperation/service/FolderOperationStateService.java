package com.woowacamp.storage.domain.folderoperation.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.folderoperation.entity.FolderOperationStatus;
import com.woowacamp.storage.domain.folderoperation.entity.FolderOperationType;
import com.woowacamp.storage.domain.folderoperation.repository.FolderOperationStateJpaRepository;
import com.woowacamp.storage.domain.folderoperation.repository.projection.ActiveMoveReservationProjection;
import com.woowacamp.storage.global.error.ErrorCode;
import com.woowacamp.storage.global.util.StorageStringUtil;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FolderOperationStateService {
	private static final String UPDATE_ACTIVE_MOVE_PROJECTED_MAX_NAME_PATH_LENGTH_SQL = """
        UPDATE folder_operation_state
        SET projected_max_name_path_length = ?, updated_at = CURRENT_TIMESTAMP
        WHERE root_id = ?
          AND folder_id = ?
          AND operation_type = ?
          AND operation_state = ?
          AND projected_max_name_path_length < ?
        """;

	private final FolderOperationStateJpaRepository folderOperationStateJpaRepository;
	private final JdbcTemplate jdbcTemplate;

	@Transactional
	public void insertActiveMove(Long rootId, Long folderId, String rootIdFullPath, int projectedMaxNamePathLength,
		Long jobId) {
		try {
			folderOperationStateJpaRepository.insert(rootId, folderId, FolderOperationType.MOVE.name(),
				FolderOperationStatus.ACTIVE.name(), rootIdFullPath, projectedMaxNamePathLength, jobId);
		} catch (DuplicateKeyException e) {
			throw ErrorCode.FOLDER_JOB_CONFLICT.baseException(
				StorageStringUtil.format("Folder operation already exists. rootId={}, folderId={}", rootId, folderId),
				e);
		} catch (DataIntegrityViolationException e) {
			throw e;
		}
	}

	@Transactional(readOnly = true)
	public Optional<Integer> findMaxActiveMoveProjectedNamePathLengthByPrefix(Long rootId, String rootIdFullPathPrefix) {
		return folderOperationStateJpaRepository.findMaxProjectedNamePathLengthInActiveMoveSubtreeByPrefix(rootId,
			rootIdFullPathPrefix,
			FolderOperationType.MOVE, FolderOperationStatus.ACTIVE);
	}

	@Transactional(readOnly = true)
	public List<Long> findOperationFolderIdsByRootIdAndFolderIds(Long rootId, List<Long> folderIds) {
		if (folderIds == null || folderIds.isEmpty()) {
			return List.of();
		}
		return folderOperationStateJpaRepository.findFolderIdsByRootIdAndFolderIds(rootId, folderIds);
	}

	@Transactional(readOnly = true)
	public List<ActiveMoveReservationProjection> findActiveMoveOperationFoldersByRootIdAndFolderIds(Long rootId,
		List<Long> folderIds) {
		if (folderIds == null || folderIds.isEmpty()) {
			return List.of();
		}
		return folderOperationStateJpaRepository.findActiveMoveReservationsByRootIdAndFolderIdsAndTypeAndState(
			rootId,
			folderIds,
			FolderOperationType.MOVE,
			FolderOperationStatus.ACTIVE
		);
	}

	@Transactional
	public void batchUpdateActiveMoveProjectedMaxNamePathLengthIfLessThan(Long rootId,
		Map<Long, Integer> projectedMaxNamePathLengthByFolderId) {
		if (projectedMaxNamePathLengthByFolderId == null || projectedMaxNamePathLengthByFolderId.isEmpty()) {
			return;
		}

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

	@Transactional
	public int delete(Long rootId, Long folderId) {
		return folderOperationStateJpaRepository.deleteByRootIdAndFolderId(rootId, folderId);
	}

}
