package com.woowacamp.storage.domain.folder.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.folder.entity.FolderMetadata;

import jakarta.persistence.LockModeType;

public interface FolderMetadataRepository extends JpaRepository<FolderMetadata, Long>, FolderCustomRepository {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	boolean existsByParentFolderIdAndUploadFolderName(Long parentFolderId, String uploadFolderName);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = """
			select f.parentFolderId from FolderMetadata f where f.id = :id
		""")
	Optional<Long> findParentFolderIdById(@Param("id") Long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT f FROM FolderMetadata f WHERE f.id = :id")
	Optional<FolderMetadata> findByIdForUpdate(long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = """
			select f.id from FolderMetadata f where f.parentFolderId = :parentFolderId
		""")
	List<Long> findIdsByParentFolderIdForUpdate(@Param("parentFolderId") Long parentFolderId);

	// 부모 폴더에 락을 걸고 하위 폴더를 조회하는 메소드
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = """
			select f from FolderMetadata f where f.parentFolderId = :parentFolderId
		""")
	List<FolderMetadata> findByParentFolderIdForUpdate(Long parentFolderId);

	// 부모 폴더에 락을 걸지 않고 하위 폴더를 조회하는 메소드
	@Query(value = """
		SELECT f from FolderMetadata  f WHERE f.parentFolderId = :parentFolderId AND f.isDeleted = FALSE
	""")
	List<FolderMetadata> findByParentFolderId(@Param("parentFolderId") Long parentFolderId);

	@Modifying
	@Query(value = """
			delete from FolderMetadata f
			where f.parentFolderId = :parentFolderId
		""")
	void deleteOrphanFolders(@Param("parentFolderId") long parentFolderId);

	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query(value = """
			select f from FolderMetadata f
			where f.id = :folderId
		""")
	Optional<FolderMetadata> findByIdForShare(@Param("folderId") Long folderId);

	List<FolderMetadata> findByOwnerId(Long ownerId);

	@Modifying
	@Query("""
			update FolderMetadata f set f.size = f.size + :fileSize, f.updatedAt = :now where f.id = :id
		""")
	void updateFolderInfo(@Param("fileSize") long fileSize, @Param("now") LocalDateTime now, @Param("id") Long id);

	@Transactional
	@Modifying
	@Query("""
		UPDATE FolderMetadata f set f.size = f.size + :size where f.id IN (:ids)
	""")
	void updateAllSizeByIdInBatch(@Param("size") long size, @Param("ids") List<Long> ids);

	@Transactional
	@Modifying
	@Query("""
		UPDATE FolderMetadata f set f.isDeleted = true where f.id = :id
	""")
	void softDeleteById(@Param("id") Long id);

	@Transactional
	@Modifying
	@Query("""
		UPDATE FolderMetadata f set f.isDeleted = true where f.id IN (:ids)
	""")
	void softDeleteAllByIdInBatch(@Param("ids") List<Long> ids);
}
