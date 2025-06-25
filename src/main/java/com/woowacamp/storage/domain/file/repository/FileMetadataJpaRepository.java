package com.woowacamp.storage.domain.file.repository;

import java.time.LocalDateTime;
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

	boolean existsByParentFolderIdAndUploadStatus(Long parentFolderId, UploadStatus uploadStatus);

	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query(value = """
			select f from FileMetadata f
			where f.id = :fileId
		""")
	Optional<FileMetadata> findByIdForShare(@Param("fileId") long fileId);

	List<FileMetadata> findByOwnerId(Long ownerId);

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

	@Query("""
			SELECT SUM(f.fileSize)
			FROM FileMetadata f
			WHERE f.parentFolderId = :parentId
		""")
	Optional<Long> sumChildFileSize(@Param("parentId") long parentId);

	@Query("""
			SELECT f
			FROM FileMetadata f
			WHERE f.uploadStatus = 'FAIL'
			ORDER BY f.id
			LIMIT :size
		""")
	List<FileMetadata> findUploadFailureList(int size);

	@Query("""
			SELECT f
			FROM FileMetadata f
			WHERE f.uploadStatus = 'FAIL' and f.id > :lastId
			ORDER BY f.id
			LIMIT :size
		""")
	List<FileMetadata> findUploadFailureListWithLastId(Long lastId, int size);

	@Query("""
			SELECT f
			FROM FileMetadata f
			WHERE f.createdAt < :timeLimit
			ORDER BY f.createdAt, f.id
			LIMIT :size
		""")
	List<FileMetadata> findUploadPendingList(int size, LocalDateTime timeLimit);

	@Query("""
			SELECT f
			FROM FileMetadata f
			WHERE f.createdAt < :timeLimit
			and f.id > :lastId
			ORDER BY f.createdAt, f.id
			LIMIT :size
		""")
	List<FileMetadata> findUploadPendingListWithLastId(Long lastId, int size, LocalDateTime timeLimit);

	@Transactional
	@Modifying
	@Query("""
			UPDATE FileMetadata f SET f.isDeleted = true, f.updatedAt = NOW() WHERE f.id IN (:ids)
		""")
	void softDeleteAllByIdInBatch(@Param("ids") List<Long> batchList);

	@Query("""
			SELECT f
			FROM FileMetadata f
			WHERE f.isDeleted = true
			AND f.updatedAt < :duration
			ORDER BY f.id
			LIMIT :size
		""")
	List<FileMetadata> findSoftDeletedFile(@Param("size") int size, @Param("duration") LocalDateTime timeLimit);

	@Query("""
			SELECT f
			FROM FileMetadata f
			WHERE f.isDeleted = true 
			AND f.id > :lastId
			AND f.updatedAt < :duration
			ORDER BY f.id
			LIMIT :size
		""")
	List<FileMetadata> findSoftDeletedFileWithLastId(@Param("lastId") Long lastId, @Param("size") int size,
		@Param("duration") LocalDateTime timeLimit);
}
