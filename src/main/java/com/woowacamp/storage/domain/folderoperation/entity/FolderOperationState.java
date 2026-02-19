package com.woowacamp.storage.domain.folderoperation.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@IdClass(FolderOperationStateId.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "folder_operation_state", indexes = {
	@Index(name = "idx_folder_operation_state_filter_prefix_max",
		columnList = "root_id, operation_state, operation_type, root_name_full_path, projected_max_name_path_length")
})
public class FolderOperationState {

	@Id
	@Column(name = "root_id", nullable = false)
	private Long rootId;

	@Id
	@Column(name = "folder_id", nullable = false)
	private Long folderId;

	@Column(name = "operation_type", nullable = false, length = 20)
	@Enumerated(EnumType.STRING)
	@NotNull
	private FolderOperationType operationType;

	@Column(name = "operation_state", nullable = false, length = 20)
	@Enumerated(EnumType.STRING)
	@NotNull
	private FolderOperationStatus operationState;

	@Column(name = "root_name_full_path", columnDefinition = "VARCHAR(250)", nullable = false)
	@NotNull
	private String rootNameFullPath;

	@Column(name = "projected_max_name_path_length", nullable = false)
	private int projectedMaxNamePathLength;

	@Column(name = "job_id")
	private Long jobId;

	@Column(name = "created_at", nullable = false)
	@NotNull
	private LocalDateTime createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	@NotNull
	private LocalDateTime updatedAt;

	@Builder
	public FolderOperationState(Long rootId, Long folderId, FolderOperationType operationType,
		FolderOperationStatus operationState, String rootNameFullPath, int projectedMaxNamePathLength,
		Long jobId, LocalDateTime createdAt, LocalDateTime updatedAt) {
		this.rootId = rootId;
		this.folderId = folderId;
		this.operationType = operationType;
		this.operationState = operationState;
		this.rootNameFullPath = rootNameFullPath;
		this.projectedMaxNamePathLength = projectedMaxNamePathLength;
		this.jobId = jobId;
		this.createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
		this.updatedAt = updatedAt == null ? LocalDateTime.now() : updatedAt;
	}

	public void updateProjectedInfo(String rootNameFullPath, int projectedMaxNamePathLength, Long jobId) {
		this.rootNameFullPath = rootNameFullPath;
		this.projectedMaxNamePathLength = projectedMaxNamePathLength;
		this.jobId = jobId;
	}

	public void updateProjectedMaxNamePathLength(int projectedMaxNamePathLength) {
		this.projectedMaxNamePathLength = projectedMaxNamePathLength;
	}

	public void updateState(FolderOperationStatus operationState) {
		this.operationState = operationState;
	}
}
