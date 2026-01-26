package com.woowacamp.storage.domain.file.entity;

import java.time.LocalDateTime;

import com.woowacamp.storage.global.constant.CommonConstant;
import com.woowacamp.storage.global.constant.PermissionType;
import com.woowacamp.storage.global.constant.UploadStatus;
import com.woowacamp.storage.global.util.StorageStringUtil;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Table(name = "file_metadata", indexes = {
	@Index(name = "file_idx_parent_folder_id_created_at", columnList = "parent_folder_id, created_at"),
	@Index(name = "file_idx_parent_folder_id_file_size", columnList = "parent_folder_id, file_size"),
	@Index(name = "file_idx_upload_status", columnList = "upload_status"),
	@Index(name = "file_idx_parent_folder_id_file_metadata_id", columnList = "parent_folder_id, file_metadata_id"),
	@Index(name = "file_idx_created_at_file_metadata_id", columnList = "created_at, file_metadata_id"),
	@Index(name = "file_idx_is_deleted", columnList = "is_deleted")})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@ToString
public class FileMetadata {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "file_metadata_id")
	private Long id;

	@Column(name = "root_id", columnDefinition = "BIGINT NOT NULL")
	@NotNull
	private Long rootId;

	@Column(name = "creator_id", columnDefinition = "BIGINT NOT NULL")
	@NotNull
	private Long creatorId;

	@Column(name = "owner_id", columnDefinition = "BIGINT NOT NULL")
	@NotNull
	private Long ownerId;

	@Column(name = "file_type", columnDefinition = "VARCHAR(50)")
	private String fileType;

	@Column(name = "created_at", columnDefinition = "TIMESTAMP NOT NULL")
	@NotNull
	private LocalDateTime createdAt;

	@Column(name = "updated_at", columnDefinition = "TIMESTAMP NOT NULL")
	@NotNull
	private LocalDateTime updatedAt;

	@Column(name = "parent_folder_id", columnDefinition = "BIGINT NOT NULL")
	@NotNull
	private Long parentFolderId;

	@Column(name = "file_size", columnDefinition = "BIGINT NOT NULL")
	@NotNull
	private Long fileSize;

	@Column(name = "upload_file_name", columnDefinition = "VARCHAR(100) NOT NULL")
	@NotNull
	private String uploadFileName;

	@Column(name = "uuid_file_name", columnDefinition = "VARCHAR(100) NOT NULL")
	@NotNull
	private String uuidFileName;

	@Enumerated(EnumType.STRING)
	@Column(name = "upload_status", columnDefinition = "VARCHAR(30) NOT NULL")
	@NotNull
	private UploadStatus uploadStatus;

	@Column(name = "thumbnail_file_name", columnDefinition = "VARCHAR(100)")
	private String thumbnailUUID;

	@Column(name = "sharing_expired_at", columnDefinition = "TIMESTAMP NOT NULL")
	@NotNull
	private LocalDateTime sharingExpiredAt;

	@Column(name = "permission_type", columnDefinition = "VARCHAR(10) NOT NULL")
	@NotNull
	@Enumerated(EnumType.STRING)
	private PermissionType permissionType;

	@Column(name = "is_deleted", columnDefinition = "BOOLEAN DEFAULT false")
	@NotNull
	private boolean isDeleted = false;

	// 폴더명 기반의 전체 경로
	@Column(name = "name_full_path", columnDefinition = "VARCHAR(250)")
	@NotNull
	private String nameFullPath;

	// pk로 만들어진 전체 경로
	@Column(name = "id_full_path", columnDefinition = "VARCHAR(250)")
	@NotNull
	private String idFullPath;

	@Column(name = "name_path_length", columnDefinition = "INT NOT NULL DEFAULT 0")
	private int namePathLength;

	@Builder
	public FileMetadata(Long id, Long rootId, Long creatorId, Long ownerId, String fileType, LocalDateTime createdAt,
		LocalDateTime updatedAt, Long parentFolderId, Long fileSize, String uploadFileName, String uuidFileName,
		UploadStatus uploadStatus, String thumbnailUUID, LocalDateTime sharingExpiredAt,
		PermissionType permissionType, String nameFullPath, String idFullPath, Integer namePathLength) {
		this.id = id;
		this.rootId = rootId;
		this.creatorId = creatorId;
		this.ownerId = ownerId;
		this.fileType = fileType;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
		this.parentFolderId = parentFolderId;
		this.fileSize = fileSize;
		this.uploadFileName = uploadFileName;
		this.uuidFileName = uuidFileName;
		this.uploadStatus = uploadStatus;
		this.thumbnailUUID = thumbnailUUID;
		this.sharingExpiredAt = sharingExpiredAt;
		this.permissionType = permissionType;
		this.nameFullPath = nameFullPath;
		this.idFullPath = idFullPath;
		this.namePathLength = namePathLength;
	}

	public void updateCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public void updateUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}

	public void updateFileSize(long fileSize) {
		this.fileSize = fileSize;
	}

	public void updateFinishUploadStatus() {
		this.uploadStatus = UploadStatus.SUCCESS;
	}

	public void updateParentFolderId(Long parentFolderId) {
		this.parentFolderId = parentFolderId;
	}

	public void updateShareStatus(PermissionType permissionType, LocalDateTime sharingExpiredAt) {
		this.permissionType = permissionType;
		this.sharingExpiredAt = sharingExpiredAt;
	}

	public void cancelShare() {
		this.permissionType = PermissionType.NONE;
		this.sharingExpiredAt = CommonConstant.UNAVAILABLE_TIME;
	}

	public boolean isSharingExpired() {
		return sharingExpiredAt.isBefore(LocalDateTime.now());
	}

	public void updateFailUploadStatus() {
		this.uploadStatus = UploadStatus.FAIL;
	}

	public void updateIdFullPath(String parentIdPath) {
		this.idFullPath = StorageStringUtil.format("{}{}/", parentIdPath, this.id);
	}

	public void updateNameFullPath(String parentNamePath) {
		this.nameFullPath = StorageStringUtil.format("{}{}/", parentNamePath, this.uploadFileName);
	}

	public void updateNamePathLength(int namePathLength) {
		this.namePathLength = namePathLength;
	}
}
