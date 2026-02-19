package com.woowacamp.storage.domain.folder.scheduler;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.folder.entity.FolderJob;
import com.woowacamp.storage.domain.folder.repository.FolderJobRepository;
import com.woowacamp.storage.domain.folder.utils.QueryExecuteTemplate;
import com.woowacamp.storage.domain.folder.utils.FolderJobStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 실패하거나 중단된 FolderJob을 복구하는 스케줄러
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FolderJobRecoveryScheduler {

	private final FolderJobRepository folderJobRepository;
	private final Clock appClock;

	@Value("${constant.batchSize:1000}")
	private int pageSize;

	@Value("${folder.job.maxRetry:3}")
	private int maxRetry;

	private static final int STUCK_THRESHOLD_MINUTES = 10;
	private static final int CLEANUP_THRESHOLD_DAYS = 7;

	/**
	 * 중단된 Job 복구 (5분마다)
	 * QueryExecuteTemplate 사용
	 */
	@Scheduled(fixedDelay = 300000)
	public void recoverStuckJobs() {
		log.info("[FolderJobRecoveryScheduler] Starting recovery check...");

		LocalDateTime thresholdTime = LocalDateTime.now(appClock).minusMinutes(STUCK_THRESHOLD_MINUTES);
		List<FolderJobStatus> retryableStatuses = List.of(FolderJobStatus.RUNNING, FolderJobStatus.FAILED);

		// QueryExecuteTemplate으로 페이징 처리
		QueryExecuteTemplate.<FolderJob>selectFilesAndExecuteWithCursor(
			pageSize,
			lastJob ->
				folderJobRepository.findStuckJobsWithCursor(retryableStatuses, thresholdTime,
					lastJob == null ? null : lastJob.getId(), pageSize)
			,
			stuckJobs -> {
				for (FolderJob job : stuckJobs) {
					try {
						// 별도 트랜잭션으로 복구
						folderJobRepository.recoverSingleJob(job, maxRetry);
					} catch (Exception e) {
						log.error("[FolderJobRecoveryScheduler] Failed to recover. jobId={}",
							job.getId(), e);
					}
				}
			}
		);

		log.info("[FolderJobRecoveryScheduler] Recovery completed.");
	}

	/**
	 * 완료된 Job 정리 (매일 새벽 3시)
	 * QueryExecuteTemplate 사용 + 커서 기반 페이징
	 */
	@Scheduled(cron = "0 0 3 * * *")
	public void cleanupCompletedJobs() {
		log.info("[FolderJobRecoveryScheduler] Starting cleanup...");

		LocalDateTime thresholdTime = LocalDateTime.now(appClock).minusDays(CLEANUP_THRESHOLD_DAYS);
		int totalDeleted = 0;

		// QueryExecuteTemplate으로 페이징 처리
		QueryExecuteTemplate.<FolderJob>selectFilesAndExecuteWithCursor(
			pageSize,
			lastJob ->
				folderJobRepository.findCompletedJobsWithCursor(thresholdTime, lastJob == null ? null : lastJob.getId(),
					pageSize),
			completedJobs -> {
				if (!completedJobs.isEmpty()) {
					// Job ID 추출
					List<Long> jobIds = new ArrayList<>();
					for (FolderJob job : completedJobs) {
						jobIds.add(job.getId());
					}

					// 별도 트랜잭션으로 삭제
					try {
						int deleted = folderJobRepository.deleteJobsBatch(jobIds);
						log.debug("[FolderJobRecoveryScheduler] Deleted {} jobs in this batch", deleted);
					} catch (Exception e) {
						log.error("[FolderJobRecoveryScheduler] Failed to delete batch. size={}",
							jobIds.size(), e);
					}
				}
			}
		);

		log.info("[FolderJobRecoveryScheduler] Cleanup completed.");
	}
}
