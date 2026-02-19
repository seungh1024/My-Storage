package com.woowacamp.storage.domain.folder.repository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.woowacamp.storage.config.IntegrationTestBase;
import com.woowacamp.storage.domain.folder.entity.FolderJob;
import com.woowacamp.storage.domain.folder.utils.FolderJobStatus;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FolderJobRepositoryTest extends IntegrationTestBase {

	@Autowired
	private FolderJobRepository folderJobRepository;

	@Autowired
	private FolderJobJpaRepository folderJobJpaRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Clock appClock;

	@BeforeEach
	void setUp() {
		cleanup();
		folderTreeSetUp.setupFolderTree();
	}

	/**
	 * updatedAt을 직접 설정한 Job 생성 (JPA Auditing 우회)
	 */
	private void createJobWithUpdatedAt(Long folderId, FolderJobStatus status, LocalDateTime updatedAt) {
		jdbcTemplate.update(
			"INSERT INTO folder_job (root_id, folder_id, current_parent_id, last_folder_id, last_file_id, parent_stack, updated_at, status, retry_count) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
			1L, folderId, folderId, null, null, "[]", updatedAt, status.name(), 0
		);
	}

	private FolderJob createJob(Long folderId, FolderJobStatus status) {
		FolderJob job = FolderJob.builder()
			.rootId(1L)
			.id(folderId)
			.currentParentId(folderId)
			.lastFolderId(null)
			.lastFileId(null)
			.parentStack("[]")
			.updatedAt(LocalDateTime.now(appClock))
			.status(status)
			.build();
		return folderJobJpaRepository.save(job);
	}

	@Test
	@DisplayName("WAITING 상태의 Job을 RUNNING으로 변경한다 (CAS 성공)")
	void tryAcquireJob_Success() {
		// given
		createJob(1L, FolderJobStatus.WAITING);

		// when
		boolean acquired = folderJobRepository.tryAcquireJob(1L);

		// then
		assertThat(acquired).isTrue();
		FolderJob updated = folderJobJpaRepository.findById(1L).orElseThrow();
		assertThat(updated.getStatus()).isEqualTo(FolderJobStatus.RUNNING);
	}

	@Test
	@DisplayName("이미 RUNNING 상태의 Job은 획득 실패 (CAS 실패)")
	void tryAcquireJob_AlreadyRunning_Fails() {
		// given
		createJob(1L, FolderJobStatus.RUNNING);

		// when
		boolean acquired = folderJobRepository.tryAcquireJob(1L);

		// then
		assertThat(acquired).isFalse();
		FolderJob stillRunning = folderJobJpaRepository.findById(1L).orElseThrow();
		assertThat(stillRunning.getStatus()).isEqualTo(FolderJobStatus.RUNNING);
	}

	@Test
	@DisplayName("Job을 COMPLETED로 마킹한다")
	void markJobCompleted_Success() {
		// given
		createJob(1L, FolderJobStatus.RUNNING);

		// when
		folderJobRepository.markJobCompleted(1L);

		// then
		FolderJob completed = folderJobJpaRepository.findById(1L).orElseThrow();
		assertThat(completed.getStatus()).isEqualTo(FolderJobStatus.COMPLETED);
	}

	@Test
	@DisplayName("Job을 FAILED로 마킹한다")
	void markJobFailed_Success() {
		// given
		createJob(1L, FolderJobStatus.RUNNING);

		// when
		folderJobRepository.markJobFailed(1L, 3);

		// then
		FolderJob failed = folderJobJpaRepository.findById(1L).orElseThrow();
		assertThat(failed.getStatus()).isEqualTo(FolderJobStatus.FAILED);
		assertThat(failed.getRetryCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("재시도 한계를 넘으면 Job을 TERMINATED로 마킹한다")
	void markJobFailed_TerminatesWhenMaxRetryExceeded() {
		// given
		createJob(1L, FolderJobStatus.RUNNING);
		jdbcTemplate.update("UPDATE folder_job SET retry_count = ? WHERE folder_id = ?", 2, 1L);

		// when
		folderJobRepository.markJobFailed(1L, 3);

		// then
		FolderJob terminated = folderJobJpaRepository.findById(1L).orElseThrow();
		assertThat(terminated.getStatus()).isEqualTo(FolderJobStatus.TERMINATED);
		assertThat(terminated.getRetryCount()).isEqualTo(3);
	}

	@Test
	@DisplayName("오래된 RUNNING Job을 조회한다 (커서 페이징)")
	void findStuckJobsWithCursor_ReturnsOldJobs() {
		// given
		LocalDateTime oldTime = LocalDateTime.now(appClock).minusMinutes(20);

		// JdbcTemplate으로 직접 INSERT (updatedAt 제어)
		createJobWithUpdatedAt(1L, FolderJobStatus.RUNNING, oldTime.minusMinutes(2));
		createJobWithUpdatedAt(2L, FolderJobStatus.RUNNING, oldTime.minusMinutes(1));
		createJobWithUpdatedAt(3L, FolderJobStatus.RUNNING, oldTime);

		// 최근 Job (조회되지 않아야 함)
		createJob(4L, FolderJobStatus.RUNNING);

		// when
		LocalDateTime threshold = LocalDateTime.now(appClock).minusMinutes(10);
		List<FolderJob> stuckJobs = folderJobRepository.findStuckJobsWithCursor(
			List.of(FolderJobStatus.RUNNING, FolderJobStatus.FAILED), threshold, null, 10);

		// then
		assertThat(stuckJobs).hasSize(3);
		assertThat(stuckJobs).extracting(FolderJob::getId)
			.containsExactlyInAnyOrder(1L, 2L, 3L);
	}

	@Test
	@DisplayName("오래된 COMPLETED Job을 조회한다 (커서 페이징)")
	void findCompletedJobsWithCursor_ReturnsOldJobs() {
		// given
		LocalDateTime oldTime = LocalDateTime.now(appClock).minusDays(10);

		// JdbcTemplate으로 직접 INSERT
		createJobWithUpdatedAt(1L, FolderJobStatus.COMPLETED, oldTime.minusDays(2));
		createJobWithUpdatedAt(2L, FolderJobStatus.COMPLETED, oldTime.minusDays(1));

		// 최근 Job (조회되지 않아야 함)
		createJob(3L, FolderJobStatus.COMPLETED);

		// when
		LocalDateTime threshold = LocalDateTime.now(appClock).minusDays(7);
		List<FolderJob> completedJobs = folderJobRepository.findCompletedJobsWithCursor(threshold, null, 10);

		// then
		assertThat(completedJobs).hasSize(2);
		assertThat(completedJobs).extracting(FolderJob::getId)
			.containsExactlyInAnyOrder(1L, 2L);
	}

	@Test
	@DisplayName("Job을 일괄 삭제한다")
	void deleteJobsBatch_Success() {
		// given
		createJob(1L, FolderJobStatus.COMPLETED);
		createJob(2L, FolderJobStatus.COMPLETED);
		createJob(3L, FolderJobStatus.COMPLETED);

		// when
		int deleted = folderJobRepository.deleteJobsBatch(List.of(1L, 2L));

		// then
		assertThat(deleted).isEqualTo(2);
		assertThat(folderJobJpaRepository.findById(1L)).isEmpty();
		assertThat(folderJobJpaRepository.findById(2L)).isEmpty();
		assertThat(folderJobJpaRepository.findById(3L)).isPresent();
	}

	@Test
	@DisplayName("커서 기반 페이징이 올바르게 동작한다")
	void findStuckJobsWithCursor_Pagination_Works() {
		// given
		LocalDateTime oldTime = LocalDateTime.now(appClock).minusMinutes(20);

		for (long i = 1; i <= 5; i++) {
			createJobWithUpdatedAt(i, FolderJobStatus.RUNNING, oldTime);
		}

		LocalDateTime threshold = LocalDateTime.now(appClock).minusMinutes(10);

		// when - 첫 페이지 (2개)
		List<FolderJob> page1 = folderJobRepository.findStuckJobsWithCursor(
			List.of(FolderJobStatus.RUNNING, FolderJobStatus.FAILED), threshold, null, 2);

		// when - 두 번째 페이지
		Long lastId = page1.get(page1.size() - 1).getId();
		List<FolderJob> page2 = folderJobRepository.findStuckJobsWithCursor(
			List.of(FolderJobStatus.RUNNING, FolderJobStatus.FAILED), threshold, lastId, 2);

		// then
		assertThat(page1).hasSize(2);
		assertThat(page2).hasSize(2);
		assertThat(page1.get(0).getId()).isLessThan(page2.get(0).getId());
	}
}
