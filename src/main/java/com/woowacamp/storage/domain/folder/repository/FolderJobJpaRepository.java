package com.woowacamp.storage.domain.folder.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.woowacamp.storage.domain.folder.entity.FolderJob;

@Repository
public interface FolderJobJpaRepository extends JpaRepository<FolderJob, Long> {

	@Modifying
	@Query(value = """
			INSERT INTO folder_job (id,last_parent_id,last_folder_id,updated_at,status) 
			VALUES (:folderId, :lastParentId, :lastFolderId, CURRENT_TIMESTAMP, :status)
		""", nativeQuery = true)
	int insert(@Param("folderId") Long folderId, @Param("lastParentId") Long lastParentId,
		@Param("lastFolderId") Long lastFolderId, @Param("status") String status);
}
