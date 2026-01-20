package com.woowacamp.storage.domain.folder.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.woowacamp.storage.domain.folder.utils.FolderJobStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "folder_job", indexes = {
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class FolderJob {
	@Id
	private Long id;

	@Column(name = "last_parent_id", nullable = false)
	private Long lastParentId;

	@Column(name = "last_folder_id")
	private Long lastFolderId;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	@NotNull
	private LocalDateTime updatedAt;

	@Column(name = "status", nullable = false)
	@Enumerated(EnumType.STRING)
	private FolderJobStatus status;

	@Builder

	public FolderJob(Long id, Long lastParentId, Long lastFolderId, LocalDateTime updatedAt, FolderJobStatus status) {
		this.id = id;
		this.lastParentId = lastParentId;
		this.lastFolderId = lastFolderId;
		this.updatedAt = updatedAt;
		this.status = status;
	}
}
