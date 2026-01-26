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
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 폴더 이동 작업의 배치 처리 상태를 관리하는 엔티티
 *
 * id: 이동 시작하는 서브트리의 루트 폴더 ID (PK)
 *     - 동일 폴더에 대한 중복 작업 방지
 *     - INSERT 실패 시 이미 작업 진행 중임을 의미
 */
@Entity
@Table(name = "folder_job", indexes = {
	@Index(name = "idx_folder_job_status_updated_at", columnList = "status, updated_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class FolderJob {

	/**
	 * 이동 작업의 대상 폴더 ID (서브트리 루트)
	 * PK로 사용하여 동일 폴더의 중복 작업 방지
	 */
	@Id
	@Column(name = "folder_id")
	private Long id;

	/**
	 * 현재 처리 중인 부모 폴더 ID
	 */
	@Column(name = "current_parent_id", nullable = false)
	private Long currentParentId;

	/**
	 * 해당 부모의 자식 중 마지막으로 처리한 폴더 ID
	 */
	@Column(name = "last_folder_id")
	private Long lastFolderId;

	/**
	 * 해당 부모의 자식 중 마지막으로 처리한 파일 ID
	 */
	@Column(name = "last_file_id")
	private Long lastFileId;

	/**
	 * DFS 탐색을 위한 부모 ID 스택 (JSON 배열)
	 * 예: "[1,5,10]" - 깊이 125 × 8bytes ≈ 1~2KB
	 */
	@Column(name = "parent_stack", columnDefinition = "TEXT")
	private String parentStack;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	@NotNull
	private LocalDateTime updatedAt;

	@Column(name = "status", nullable = false, length = 20)
	@Enumerated(EnumType.STRING)
	private FolderJobStatus status;

	@Builder
	public FolderJob(Long id, Long currentParentId, Long lastFolderId,
		Long lastFileId, String parentStack, LocalDateTime updatedAt,
		FolderJobStatus status) {
		this.id = id;
		this.currentParentId = currentParentId;
		this.lastFolderId = lastFolderId;
		this.lastFileId = lastFileId;
		this.parentStack = parentStack;
		this.updatedAt = updatedAt;
		this.status = status;
	}

	public void updateProgress(Long currentParentId, Long lastFolderId, Long lastFileId, String parentStack) {
		this.currentParentId = currentParentId;
		this.lastFolderId = lastFolderId;
		this.lastFileId = lastFileId;
		this.parentStack = parentStack;
	}

	public void updateStatus(FolderJobStatus status) {
		this.status = status;
	}

	public void markCompleted() {
		this.status = FolderJobStatus.COMPLETED;
	}

	public void markRunning() {
		this.status = FolderJobStatus.RUNNING;
	}

	public void markFailed() {
		this.status = FolderJobStatus.FAILED;
	}

	public void resetFolderProgress() {
		this.lastFolderId = null;
	}

	public void resetFileProgress() {
		this.lastFileId = null;
	}
}