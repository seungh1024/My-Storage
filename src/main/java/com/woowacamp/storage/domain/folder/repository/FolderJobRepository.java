package com.woowacamp.storage.domain.folder.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Repository;

import com.woowacamp.storage.domain.folder.entity.FolderJob;
import com.woowacamp.storage.domain.folder.event.FolderMoveEvent;
import com.woowacamp.storage.domain.folder.utils.FolderJobStatus;
import com.woowacamp.storage.domain.folderoperation.repository.FolderOperationStateJpaRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Repository
@RequiredArgsConstructor
public class FolderJobRepository {
	private static final String LOG_JOB_TERMINATED =
		"[FolderJobTransactionHelper] Job terminated (max retries exceeded). jobId={}";

	private final FolderJobJpaRepository folderJobJpaRepository;
	private final FolderMetadataJpaRepository folderMetadataJpaRepository;
	private final FolderOperationStateJpaRepository folderOperationStateJpaRepository;
	private final ApplicationEventPublisher publisher;

	@Transactional
	public List<FolderJob> findStuckJobsWithCursor(List<FolderJobStatus> statuses,
		LocalDateTime thresholdTime, Long lastId, int pageSize) {
		if (lastId == null) {
			return folderJobJpaRepository.findStuckJobsFirstPage(
				statuses, thresholdTime, pageSize);
		}
		return folderJobJpaRepository.findStuckJobsWithCursor(
			statuses, thresholdTime, lastId, pageSize);
	}

	@Transactional
	public List<FolderJob> findCompletedJobsWithCursor(LocalDateTime thresholdTime, Long lastId, int pageSize) {
		if (lastId == null) {
			return folderJobJpaRepository.findCompletedJobsFirstPage(
				FolderJobStatus.COMPLETED, thresholdTime, pageSize);
		}
		return folderJobJpaRepository.findCompletedJobsWithCursor(
			FolderJobStatus.COMPLETED, thresholdTime, lastId, pageSize);
	}

	@Transactional
	public int deleteJobsBatch(List<Long> jobIds) {
		int deleted = folderJobJpaRepository.deleteByFolderIdIn(jobIds);
		log.debug("[FolderJobTransactionHelper] Deleted {} jobs", deleted);
		return deleted;
	}

	/**
	 * 단일 Job 복구 처리 (스케줄러용)
	 */
	@Transactional
	public void recoverSingleJob(FolderJob job, int maxRetry) {
		log.warn("[FolderJobTransactionHelper] Recovering stuck job. jobId={}, updatedAt={}",
			job.getId(), job.getUpdatedAt());

		if (job.getRetryCount() >= maxRetry) {
			job.markTerminated();
			folderJobJpaRepository.save(job);
			folderMetadataJpaRepository.releaseMovingLock(job.getId());
			log.error(LOG_JOB_TERMINATED, job.getId());
			return;
		}

		if (job.getStatus() == FolderJobStatus.RUNNING) {
			job.incrementRetryCount();
			if (job.getRetryCount() >= maxRetry) {
				job.markTerminated();
				folderJobJpaRepository.save(job);
				folderMetadataJpaRepository.releaseMovingLock(job.getId());
				log.error(LOG_JOB_TERMINATED, job.getId());
				return;
			}
		}

		job.updateStatus(FolderJobStatus.WAITING);
		folderJobJpaRepository.save(job);

		// 새 메시지 발행
		publisher.publishEvent(new FolderMoveEvent(job.getId()));

		log.info("[FolderJobTransactionHelper] Job recovered to WAITING. jobId={}", job.getId());
	}

	/**
	 * Job 완료 처리
	 */
	@Transactional
	public void markJobCompleted(Long rootId, Long jobId) {
		FolderJob job = folderJobJpaRepository.findByRootIdAndId(rootId, jobId)
			.orElseThrow(() -> new IllegalStateException(
				String.format("FolderJob not found. rootId=%d, folderId=%d", rootId, jobId)));

		job.markCompleted();
		folderJobJpaRepository.save(job);
		folderMetadataJpaRepository.releaseMovingLock(jobId);
		folderOperationStateJpaRepository.deleteByRootIdAndFolderId(rootId, jobId);

		log.info("[FolderJobTransactionHelper] Job marked as COMPLETED. rootId={}, jobId={}", rootId, jobId);
	}

	@Transactional
	public void markJobCompleted(Long jobId) {
		FolderJob job = folderJobJpaRepository.findById(jobId)
			.orElseThrow(() -> new IllegalStateException("FolderJob not found: " + jobId));
		markJobCompleted(job.getRootId(), job.getId());
	}

	/**
	 * Job 실패 처리
	 */
	@Transactional
	public boolean markJobFailed(Long rootId, Long jobId, int maxRetry) {
		FolderJob job = folderJobJpaRepository.findByRootIdAndId(rootId, jobId)
			.orElseThrow(() -> new IllegalStateException(
				String.format("FolderJob not found. rootId=%d, folderId=%d", rootId, jobId)));

		job.incrementRetryCount();
		if (job.getRetryCount() >= maxRetry) {
			job.markTerminated();
			folderJobJpaRepository.save(job);
			folderMetadataJpaRepository.releaseMovingLock(jobId);
			log.error(LOG_JOB_TERMINATED, jobId);
			return true;
		}

		job.markFailed();
		folderJobJpaRepository.save(job);

		log.error("[FolderJobTransactionHelper] Job marked as FAILED. jobId={}", jobId);
		return false;
	}

	@Transactional
	public boolean markJobFailed(Long jobId, int maxRetry) {
		FolderJob job = folderJobJpaRepository.findById(jobId)
			.orElseThrow(() -> new IllegalStateException("FolderJob not found: " + jobId));
		return markJobFailed(job.getRootId(), job.getId(), maxRetry);
	}

	/**
	 * Job 획득 시도 (CAS 방식)
	 */
	@Transactional
	public boolean tryAcquireJob(Long rootId, Long jobId) {
		int updated = folderJobJpaRepository.updateStatusCAS(
			rootId, jobId, FolderJobStatus.WAITING, FolderJobStatus.RUNNING);

		if (updated > 0) {
			log.info("[FolderJobTransactionHelper] Job acquired. rootId={}, jobId={}", rootId, jobId);
			return true;
		}

		log.warn("[FolderJobTransactionHelper] Failed to acquire job. rootId={}, jobId={}", rootId, jobId);
		return false;
	}

	@Transactional
	public boolean tryAcquireJob(Long jobId) {
		FolderJob job = folderJobJpaRepository.findById(jobId)
			.orElseThrow(() -> new IllegalStateException("FolderJob not found: " + jobId));
		return tryAcquireJob(job.getRootId(), job.getId());
	}
}
