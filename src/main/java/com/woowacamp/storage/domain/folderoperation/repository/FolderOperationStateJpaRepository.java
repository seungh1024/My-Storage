package com.woowacamp.storage.domain.folderoperation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.folderoperation.entity.FolderOperationState;
import com.woowacamp.storage.domain.folderoperation.entity.FolderOperationStateId;
import com.woowacamp.storage.domain.folderoperation.entity.FolderOperationStatus;
import com.woowacamp.storage.domain.folderoperation.entity.FolderOperationType;
import com.woowacamp.storage.domain.folderoperation.repository.projection.ActiveMoveReservationProjection;

public interface FolderOperationStateJpaRepository extends JpaRepository<FolderOperationState, FolderOperationStateId> {

	@Query("""
            SELECT fos.folderId
            FROM FolderOperationState fos
            WHERE fos.rootId = :rootId
              AND fos.folderId IN :folderIds
        """)
	List<Long> findFolderIdsByRootIdAndFolderIds(@Param("rootId") Long rootId,
		@Param("folderIds") List<Long> folderIds);

	@Query("""
            SELECT fos.folderId AS folderId,
                   fos.projectedMaxNamePathLength AS projectedMaxNamePathLength
            FROM FolderOperationState fos
            WHERE fos.rootId = :rootId
              AND fos.folderId IN :folderIds
              AND fos.operationType = :operationType
              AND fos.operationState = :operationState
        """)
	List<ActiveMoveReservationProjection> findActiveMoveReservationsByRootIdAndFolderIdsAndTypeAndState(
		@Param("rootId") Long rootId,
		@Param("folderIds") List<Long> folderIds,
		@Param("operationType") FolderOperationType operationType,
		@Param("operationState") FolderOperationStatus operationState);

	@Query("""
            SELECT MAX(fos.projectedMaxNamePathLength)
            FROM FolderOperationState fos
            WHERE fos.rootId = :rootId
              AND fos.operationType = :operationType
              AND fos.operationState = :operationState
              AND fos.rootIdFullPath LIKE CONCAT(:rootIdFullPathPrefix, '%')
        """)
	Optional<Integer> findMaxProjectedNamePathLengthInActiveMoveSubtreeByPrefix(@Param("rootId") Long rootId,
		@Param("rootIdFullPathPrefix") String rootIdFullPathPrefix,
		@Param("operationType") FolderOperationType operationType,
		@Param("operationState") FolderOperationStatus operationState);

	@Transactional
	@Modifying
	@Query("""
            DELETE FROM FolderOperationState fos
            WHERE fos.rootId = :rootId AND fos.folderId = :folderId
        """)
	int deleteByRootIdAndFolderId(@Param("rootId") Long rootId, @Param("folderId") Long folderId);
}
