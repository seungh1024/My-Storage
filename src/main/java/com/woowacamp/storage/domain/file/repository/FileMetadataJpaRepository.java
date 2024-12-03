package com.woowacamp.storage.domain.file.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.global.constant.UploadStatus;

import jakarta.persistence.LockModeType;

public interface FileMetadataJpaRepository extends JpaRepository<FileMetadata, Long>, FileCustomRepository {

	boolean existsByUuidFileName(String uuidFileName);

	boolean existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(Long parentFolderId, String uploadFileName,
		UploadStatus uploadStatus);

	@Transactional
	void deleteByUuidFileName(String uuidFileName);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = """
			select f from FileMetadata f where f.id = :id and f.uploadStatus != 'FAIL'
		""")
	Optional<FileMetadata> findByIdForUpdate(@Param("id") Long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<FileMetadata> findByIdAndOwnerIdAndUploadStatusNot(Long id, Long ownerId, UploadStatus uploadStatus);

	// 부모 폴더에 락을 걸고 조회하는 메소드
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = """
			select f from FileMetadata f where f.parentFolderId=:parentFolderId and f.uploadStatus != 'FAIL'
		""")
	List<FileMetadata> findByParentFolderIdForUpdate(Long parentFolderId);

	@Modifying
	@Query(value = """
			update FileMetadata f
			set f.fileSize = :fileSize, f.uploadStatus = :uploadStatus, f.createdAt = NOW(), f.updatedAt = NOW()
			where f.id = :fileId
		""")
	int finalizeMetadata(@Param("fileId") long fileId, @Param("fileSize") long fileSize,
		@Param("uploadStatus") UploadStatus uploadStatus);

	@Modifying
	@Query(value = """
			select * from file_metadata f
			where f.parent_folder_id = :orphanParentId limit 50;
		""", nativeQuery = true)
	List<FileMetadata> findOrphanFiles(@Param("orphanParentId") int orphanParentId);

	boolean existsByParentFolderIdAndUploadStatus(Long parentFolderId, UploadStatus uploadStatus);

	@Transactional
	@Modifying
	@Query("""
			update FileMetadata f set f.uploadStatus = 'FAIL', f.updatedAt = NOW() where f.id = :fileMetadataId
		""")
	void updateUploadStatusById(@Param("fileMetadataId") Long fileMetadataId);

	@Transactional
	@Modifying
	@Query("""
			update FileMetadata f set f.uploadStatus = 'FAIL', f.updatedAt = NOW() where f.uuidFileName = :uuid
		""")
	void updateUploadStatusByUuid(@Param("uuid") String uuid);

	@Transactional
	@Query(value = """
			select * from file_metadata f where f.upload_status = 'FAIL' limit 50;
		""", nativeQuery = true)
	List<FileMetadata> findFailedFileMetadata();

	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query(value = """
			select f from FileMetadata f
			where f.id = :fileId
		""")
	Optional<FileMetadata> findByIdForShare(@Param("fileId") long fileId);

	List<FileMetadata> findByOwnerId(Long ownerId);

	List<FileMetadata> findByParentFolderId(Long parentId);

	@Query("""
			SELECT f
			FROM FileMetadata f
			WHERE f.parentFolderId = :parentId
			ORDER BY f.id
			LIMIT :size
		""")
	List<FileMetadata> findByParentFolderId(@Param("parentId") long parentId, @Param("size") int size);

	@Query("""
			SELECT f
			FROM FileMetadata f
			WHERE f.parentFolderId = :parentId AND f.id > :lastId
			ORDER BY f.id
			LIMIT :size
		""")
	List<FileMetadata> findByParentFolderIdWithLastId(@Param("parentId") long parentId, @Param("lastId") Long lastId,
		@Param("size") int size);
}
