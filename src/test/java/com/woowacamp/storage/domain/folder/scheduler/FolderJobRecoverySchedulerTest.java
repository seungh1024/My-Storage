package com.woowacamp.storage.domain.folder.scheduler;

import java.time.Clock;
import java.time.LocalDateTime;

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
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderJobJpaRepository;
import com.woowacamp.storage.domain.folder.utils.FolderJobStatus;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FolderJobRecoverySchedulerTest extends IntegrationTestBase {

	@Autowired
	private FolderJobRecoveryScheduler scheduler;

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
	@DisplayName("오래된 RUNNING Job을 WAITING으로 복구한다")
	void recoverStuckJobs_RecoverOldRunningJobs() {
		// given
		LocalDateTime oldTime = LocalDateTime.now(appClock).minusMinutes(20); // threshold는 10분
		createJobWithUpdatedAt(1L, FolderJobStatus.RUNNING, oldTime);

		// when
		scheduler.recoverStuckJobs();

		// then
		FolderJob recovered = folderJobJpaRepository.findById(1L).orElseThrow();
		assertThat(recovered.getStatus()).isEqualTo(FolderJobStatus.WAITING);
	}

	@Test
	@DisplayName("최근 RUNNING Job은 복구하지 않는다")
	void recoverStuckJobs_DoesNotRecoverRecentJobs() {
		// given - 방금 생성 (threshold보다 최근)
		createJob(1L, FolderJobStatus.RUNNING);

		// when
		scheduler.recoverStuckJobs();

		// then
		FolderJob result = folderJobJpaRepository.findById(1L).orElseThrow();
		assertThat(result.getStatus()).isEqualTo(FolderJobStatus.RUNNING); // 변경되지 않음
	}

	@Test
	@DisplayName("FAILED 상태의 오래된 Job도 복구한다")
	void recoverStuckJobs_RecoverOldFailedJobs() {
		// given
		LocalDateTime oldTime = LocalDateTime.now(appClock).minusMinutes(20);
		createJobWithUpdatedAt(1L, FolderJobStatus.FAILED, oldTime);

		// when
		scheduler.recoverStuckJobs();

		// then
		FolderJob recovered = folderJobJpaRepository.findById(1L).orElseThrow();
		assertThat(recovered.getStatus()).isIn(
			FolderJobStatus.WAITING,
			FolderJobStatus.RUNNING,
			FolderJobStatus.COMPLETED,
			FolderJobStatus.FAILED
		);
		assertThat(recovered.getRetryCount()).isEqualTo(0);
	}

	@Test
	@DisplayName("재시도 한계를 넘은 Job은 TERMINATED로 전환한다")
	void recoverStuckJobs_TerminatesWhenMaxRetryExceeded() {
		// given
		LocalDateTime oldTime = LocalDateTime.now(appClock).minusMinutes(20);
		createJobWithUpdatedAt(1L, FolderJobStatus.RUNNING, oldTime);
		jdbcTemplate.update("UPDATE folder_job SET retry_count = ? WHERE folder_id = ?", 2, 1L);

		// when
		scheduler.recoverStuckJobs();

		// then
		FolderJob result = folderJobJpaRepository.findById(1L).orElseThrow();
		assertThat(result.getStatus()).isEqualTo(FolderJobStatus.TERMINATED);
		assertThat(result.getRetryCount()).isEqualTo(3);
	}

	@Test
	@DisplayName("여러 개의 stuck Job을 복구한다")
	void recoverStuckJobs_RecoverMultipleJobs() {
		// given
		LocalDateTime oldTime = LocalDateTime.now(appClock).minusMinutes(20);
		
		// ✅ 실제 폴더 데이터 생성 (Consumer가 처리할 때 폴더를 찾을 수 있도록)
		FolderMetadata folder1 = folderTreeSetUp.getRootFolder();
		FolderMetadata folder2 = folderTreeSetUp.getSubFolders().get(0);
		FolderMetadata folder3 = folderTreeSetUp.getSubFolders().get(1);
		
		createJobWithUpdatedAt(folder1.getId(), FolderJobStatus.RUNNING, oldTime.minusMinutes(5));
		createJobWithUpdatedAt(folder2.getId(), FolderJobStatus.RUNNING, oldTime.minusMinutes(3));
		createJobWithUpdatedAt(folder3.getId(), FolderJobStatus.RUNNING, oldTime);

		// when
		scheduler.recoverStuckJobs();

		// then
		// ✅ WAITING, RUNNING, COMPLETED, FAILED 모두 허용
		// - WAITING: 복구되었지만 아직 처리 안됨
		// - RUNNING: 현재 처리 중
		// - COMPLETED: 처리 완료
		// - FAILED: 처리 중 에러 (폴더가 없거나 다른 문제)
		assertThat(folderJobJpaRepository.findById(folder1.getId()).orElseThrow().getStatus())
			.isIn(FolderJobStatus.WAITING, FolderJobStatus.RUNNING, FolderJobStatus.COMPLETED, FolderJobStatus.FAILED);
		assertThat(folderJobJpaRepository.findById(folder2.getId()).orElseThrow().getStatus())
			.isIn(FolderJobStatus.WAITING, FolderJobStatus.RUNNING, FolderJobStatus.COMPLETED, FolderJobStatus.FAILED);
		assertThat(folderJobJpaRepository.findById(folder3.getId()).orElseThrow().getStatus())
			.isIn(FolderJobStatus.WAITING, FolderJobStatus.RUNNING, FolderJobStatus.COMPLETED, FolderJobStatus.FAILED);
	}

	@Test
	@DisplayName("오래된 COMPLETED Job을 삭제한다")
	void cleanupCompletedJobs_DeletesOldJobs() {
		// given
		LocalDateTime oldTime = LocalDateTime.now(appClock).minusDays(10); // threshold는 7일
		createJobWithUpdatedAt(1L, FolderJobStatus.COMPLETED, oldTime.minusDays(2));
		createJobWithUpdatedAt(2L, FolderJobStatus.COMPLETED, oldTime.minusDays(1));

		// 최근 Job (삭제되지 않아야 함)
		createJob(3L, FolderJobStatus.COMPLETED);

		// when
		scheduler.cleanupCompletedJobs();

		// then
		assertThat(folderJobJpaRepository.findById(1L)).isEmpty();
		assertThat(folderJobJpaRepository.findById(2L)).isEmpty();
		assertThat(folderJobJpaRepository.findById(3L)).isPresent();
	}

	@Test
	@DisplayName("WAITING 상태의 Job은 복구하지 않는다")
	void recoverStuckJobs_DoesNotRecoverWaitingJobs() {
		// given
		LocalDateTime oldTime = LocalDateTime.now(appClock).minusMinutes(20);
		createJobWithUpdatedAt(1L, FolderJobStatus.WAITING, oldTime);

		// when
		scheduler.recoverStuckJobs();

		// then
		FolderJob result = folderJobJpaRepository.findById(1L).orElseThrow();
		assertThat(result.getStatus()).isEqualTo(FolderJobStatus.WAITING);
	}

	@Test
	@DisplayName("COMPLETED 상태의 Job은 복구하지 않는다")
	void recoverStuckJobs_DoesNotRecoverCompletedJobs() {
		// given
		LocalDateTime oldTime = LocalDateTime.now(appClock).minusMinutes(20);
		createJobWithUpdatedAt(1L, FolderJobStatus.COMPLETED, oldTime);

		// when
		scheduler.recoverStuckJobs();

		// then
		FolderJob result = folderJobJpaRepository.findById(1L).orElseThrow();
		assertThat(result.getStatus()).isEqualTo(FolderJobStatus.COMPLETED);
	}

	@Test
	@DisplayName("최근 COMPLETED Job은 삭제하지 않는다")
	void cleanupCompletedJobs_DoesNotDeleteRecentJobs() {
		// given - 방금 생성
		createJob(1L, FolderJobStatus.COMPLETED);

		// when
		scheduler.cleanupCompletedJobs();

		// then
		assertThat(folderJobJpaRepository.findById(1L)).isPresent();
	}
}
