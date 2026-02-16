package com.woowacamp.storage.domain.folderoperation.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.folderoperation.entity.FolderOperationStatus;
import com.woowacamp.storage.domain.folderoperation.entity.FolderOperationType;
import com.woowacamp.storage.domain.folderoperation.repository.FolderOperationStateJpaRepository;
import com.woowacamp.storage.domain.folderoperation.repository.FolderOperationStateRepository;
import com.woowacamp.storage.domain.folderoperation.repository.projection.ActiveMoveReservationProjection;
import com.woowacamp.storage.global.error.ErrorCode;
import com.woowacamp.storage.global.util.StorageStringUtil;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FolderOperationStateService {
	private final FolderOperationStateJpaRepository folderOperationStateJpaRepository;
	private final FolderOperationStateRepository folderOperationStateRepository;

	@Transactional
	public void insertActiveMove(Long rootId, Long folderId, String rootIdFullPath, int projectedMaxNamePathLength,
		Long jobId) {
		try {
			folderOperationStateRepository.insertActiveMove(rootId, folderId, rootIdFullPath,
				projectedMaxNamePathLength, jobId);
		} catch (DuplicateKeyException e) {
			throw ErrorCode.FOLDER_JOB_CONFLICT.baseException(
				StorageStringUtil.format("Folder operation already exists. rootId={}, folderId={}", rootId, folderId),
				e);
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

		folderOperationStateRepository.batchUpdateActiveMoveProjectedMaxNamePathLengthIfLessThan(
			rootId, projectedMaxNamePathLengthByFolderId);
	}

	@Transactional
	public int delete(Long rootId, Long folderId) {
		return folderOperationStateJpaRepository.deleteByRootIdAndFolderId(rootId, folderId);
	}

}
