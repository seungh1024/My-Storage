package com.woowacamp.storage.domain.folder.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.folder.dto.command.FolderJobInsertCommand;
import com.woowacamp.storage.domain.folder.entity.FolderJob;
import com.woowacamp.storage.domain.folder.entity.FolderJobId;
import com.woowacamp.storage.domain.folder.utils.FolderJobStatus;

@Repository
public interface FolderJobJpaRepository extends JpaRepository<FolderJob, FolderJobId> {

	@Query("""
        SELECT fj FROM FolderJob fj
        WHERE fj.id = :folderId
    """)
	Optional<FolderJob> findByFolderId(@Param("folderId") Long folderId);

	Optional<FolderJob> findByRootIdAndId(Long rootId, Long id);

	default Optional<FolderJob> findById(Long folderId) {
		return findByFolderId(folderId);
	}

	@Transactional
	@Modifying
	@Query(value = """
            INSERT INTO folder_job (root_id, folder_id, current_parent_id, last_folder_id,
                                    last_file_id, parent_stack, updated_at, status, retry_count)
            VALUES (:#{#command.rootId}, :#{#command.folderId}, :#{#command.currentParentId},
                    :#{#command.lastFolderId}, :#{#command.lastFileId},
                    :#{#command.parentStack}, CURRENT_TIMESTAMP, :#{#command.status}, :#{#command.retryCount})
        """, nativeQuery = true)
	int insert(@Param("command") FolderJobInsertCommand command);

	@Transactional
	@Modifying
	@Query("""
        UPDATE FolderJob fj
        SET fj.status = :newStatus, fj.updatedAt = CURRENT_TIMESTAMP
        WHERE fj.rootId = :rootId AND fj.id = :id AND fj.status = :expectedStatus
    """)
	int updateStatusCAS(@Param("rootId") Long rootId,
		@Param("id") Long id,
		@Param("expectedStatus") FolderJobStatus expectedStatus,
		@Param("newStatus") FolderJobStatus newStatus);

	@Transactional
	@Modifying
	@Query("""
        UPDATE FolderJob fj
        SET fj.currentParentId = :currentParentId,
            fj.lastFolderId = :lastFolderId,
            fj.lastFileId = :lastFileId,
            fj.parentStack = :parentStack,
            fj.updatedAt = CURRENT_TIMESTAMP
        WHERE fj.rootId = :rootId AND fj.id = :id
    """)
	int updateProgress(@Param("rootId") Long rootId,
		@Param("id") Long id,
		@Param("currentParentId") Long currentParentId,
		@Param("lastFolderId") Long lastFolderId,
		@Param("lastFileId") Long lastFileId,
		@Param("parentStack") String parentStack);

	/**
	 * RUNNING 상태 Job 첫 페이지 조회
	 */
	@Query("""
        SELECT fj FROM FolderJob fj
        WHERE fj.status IN :status
        AND fj.updatedAt < :thresholdTime
        ORDER BY fj.id ASC
        LIMIT :limit
    """)
	List<FolderJob> findStuckJobsFirstPage(@Param("status") List<FolderJobStatus> status,
		@Param("thresholdTime") LocalDateTime thresholdTime,
		@Param("limit") int limit);

	/**
	 * RUNNING 상태 Job ID 커서 기반 페이징
	 */
	@Query("""
        SELECT fj FROM FolderJob fj
        WHERE fj.status IN :status
        AND fj.updatedAt < :thresholdTime
        AND fj.id > :lastId
        ORDER BY fj.id ASC
        LIMIT :limit
    """)
	List<FolderJob> findStuckJobsWithCursor(@Param("status") List<FolderJobStatus> status,
		@Param("thresholdTime") LocalDateTime thresholdTime,
		@Param("lastId") Long lastId,
		@Param("limit") int limit);

	/**
	 * COMPLETED 상태 Job 첫 페이지 조회
	 */
	@Query("""
        SELECT fj FROM FolderJob fj
        WHERE fj.status = :status
        AND fj.updatedAt < :thresholdTime
        ORDER BY fj.id ASC
        LIMIT :limit
    """)
	List<FolderJob> findCompletedJobsFirstPage(@Param("status") FolderJobStatus status,
		@Param("thresholdTime") LocalDateTime thresholdTime,
		@Param("limit") int limit);

	/**
	 * COMPLETED 상태 Job ID 커서 기반 페이징
	 */
	@Query("""
        SELECT fj FROM FolderJob fj
        WHERE fj.status = :status
        AND fj.updatedAt < :thresholdTime
        AND fj.id > :lastId
        ORDER BY fj.id ASC
        LIMIT :limit
    """)
	List<FolderJob> findCompletedJobsWithCursor(@Param("status") FolderJobStatus status,
		@Param("thresholdTime") LocalDateTime thresholdTime,
		@Param("lastId") Long lastId,
		@Param("limit") int limit);

	@Transactional
	@Modifying
	@Query("""
        DELETE FROM FolderJob fj
        WHERE fj.id IN (:folderIds)
    """)
	int deleteByFolderIdIn(@Param("folderIds") List<Long> folderIds);
}
