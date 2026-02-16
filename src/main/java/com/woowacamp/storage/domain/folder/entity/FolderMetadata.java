package com.woowacamp.storage.domain.folder.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.woowacamp.storage.global.constant.CommonConstant;
import com.woowacamp.storage.global.constant.PermissionType;
import com.woowacamp.storage.global.util.StorageStringUtil;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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

@Builder
@Entity
@Table(name = "folder_metadata", indexes = {
	@Index(name = "folder_idx_parent_folder_id_created_at", columnList = "parent_folder_id, created_at"),
	@Index(name = "folder_idx_parent_folder_id_size", columnList = "parent_folder_id, folder_size"),
	@Index(name = "folder_idx_parent_folder_id_is_deleted", columnList = "parent_folder_id, is_deleted"),
	@Index(name = "folder_idx_find_folder_with_cursor", columnList = "parent_folder_id, is_deleted, folder_metadata_id"),
	@Index(name = "folder_idx_is_deleted_folder_metadata_id", columnList = "is_deleted, folder_metadata_id"),
	@Index(name = "folder_idx_root_deleted_namepath_length",
		columnList = "root_id, is_deleted, name_full_path, name_path_length")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class FolderMetadata {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "folder_metadata_id", columnDefinition = "BIGINT")
	private Long id;

	@Column(name = "root_id", columnDefinition = "BIGINT")
	private Long rootId;

	@Column(name = "owner_id", columnDefinition = "BIGINT")
	private Long ownerId;

	@Column(name = "creator_id", columnDefinition = "BIGINT")
	private Long creatorId;

	@Column(name = "created_at", columnDefinition = "TIMESTAMP NOT NULL")
	@NotNull
	private LocalDateTime createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", columnDefinition = "TIMESTAMP NOT NULL")
	@NotNull
	private LocalDateTime updatedAt;

	@Column(name = "parent_folder_id", columnDefinition = "BIGINT")
	private Long parentFolderId;

	@Column(name = "upload_folder_name", columnDefinition = "VARCHAR(100) NOT NULL")
	@NotNull
	private String uploadFolderName;

	@Column(name = "folder_size", columnDefinition = "BIGINT NOT NULL DEFAULT 0")
	private long size;

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

	@Builder.Default
	@Column(name = "version")
	private long version = 0L;

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
	public FolderMetadata(Long id, Long rootId, Long ownerId, Long creatorId, LocalDateTime createdAt,
		LocalDateTime updatedAt, Long parentFolderId, String uploadFolderName, long size,
		LocalDateTime sharingExpiredAt, PermissionType permissionType, boolean isDeleted, long version,
		String nameFullPath, String idFullPath, Integer namePathLength) {

		this.id = id;
		this.rootId = rootId;
		this.ownerId = ownerId;
		this.creatorId = creatorId;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
		this.parentFolderId = parentFolderId;
		this.uploadFolderName = uploadFolderName;
		this.size = size;
		this.sharingExpiredAt = sharingExpiredAt;
		this.permissionType = permissionType;
		this.isDeleted = isDeleted;
		this.version = version;
		this.nameFullPath = nameFullPath;
		this.idFullPath = idFullPath;
		this.namePathLength = namePathLength;
	}

	public void initOwnerId(Long ownerId) {
		this.ownerId = ownerId;
	}

	public void initCreatorId(Long creatorId) {
		this.creatorId = creatorId;
	}

	public void addSize(long size) {
		this.size += size;
	}

	public void updateUpdatedAt(LocalDateTime now) {
		this.updatedAt = now;
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

	public void updateIdFullPath(String parentIdPath) {
		this.idFullPath = StorageStringUtil.format("{}{}/", parentIdPath, this.id);
	}

	public void updateNameFullPath(String parentNamePath) {
		this.nameFullPath = StorageStringUtil.format("{}{}/", parentNamePath, this.uploadFolderName);
	}

	public void updateNamePathLength(int namePathLength) {
		this.namePathLength = namePathLength;
	}

	public void updateMoveRootPath(String idFullPath, String nameFullPath, int namePathLength) {
		this.idFullPath = idFullPath;
		this.nameFullPath = nameFullPath;
		this.namePathLength = namePathLength;
	}

}
