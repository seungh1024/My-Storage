package com.woowacamp.storage.domain.folder.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Repository;

import com.woowacamp.storage.domain.folder.entity.FolderJob;
import com.woowacamp.storage.domain.folder.event.FolderMoveEvent;
import com.woowacamp.storage.domain.folder.utils.FolderJobStatus;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Repository
@RequiredArgsConstructor
public class FolderJobRepository {

	private final FolderJobJpaRepository folderJobJpaRepository;
	private final ApplicationEventPublisher publisher;

	@Transactional
	public List<FolderJob> findStuckJobsWithCursor(LocalDateTime thresholdTime, Long lastId, int pageSize) {
		if (lastId == null) {
			return folderJobJpaRepository.findStuckJobsFirstPage(
				FolderJobStatus.RUNNING, thresholdTime, pageSize);
		}
		return folderJobJpaRepository.findStuckJobsWithCursor(
			FolderJobStatus.RUNNING, thresholdTime, lastId, pageSize);
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
		int deleted = folderJobJpaRepository.deleteByIdIn(jobIds);
		log.debug("[FolderJobTransactionHelper] Deleted {} jobs", deleted);
		return deleted;
	}

	/**
	 * 단일 Job 복구 처리 (스케줄러용)
	 */
	@Transactional
	public void recoverSingleJob(FolderJob job) {
		log.warn("[FolderJobTransactionHelper] Recovering stuck job. jobId={}, updatedAt={}",
			job.getId(), job.getUpdatedAt());

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
	public void markJobCompleted(Long jobId) {
		FolderJob job = folderJobJpaRepository.findById(jobId)
			.orElseThrow(() -> new IllegalStateException("FolderJob not found: " + jobId));

		job.markCompleted();
		folderJobJpaRepository.save(job);

		log.info("[FolderJobTransactionHelper] Job marked as COMPLETED. jobId={}", jobId);
	}

	/**
	 * Job 실패 처리
	 */
	@Transactional
	public void markJobFailed(Long jobId) {
		FolderJob job = folderJobJpaRepository.findById(jobId)
			.orElseThrow(() -> new IllegalStateException("FolderJob not found: " + jobId));

		job.markFailed();
		folderJobJpaRepository.save(job);

		log.error("[FolderJobTransactionHelper] Job marked as FAILED. jobId={}", jobId);
	}

	/**
	 * Job 획득 시도 (CAS 방식)
	 */
	@Transactional
	public boolean tryAcquireJob(Long jobId) {
		int updated = folderJobJpaRepository.updateStatusCAS(
			jobId, FolderJobStatus.WAITING, FolderJobStatus.RUNNING);

		if (updated > 0) {
			log.info("[FolderJobTransactionHelper] Job acquired. jobId={}", jobId);
			return true;
		}

		log.warn("[FolderJobTransactionHelper] Failed to acquire job. jobId={}", jobId);
		return false;
	}
}
