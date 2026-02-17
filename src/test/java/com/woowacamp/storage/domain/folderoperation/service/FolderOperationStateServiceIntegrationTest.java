package com.woowacamp.storage.domain.folderoperation.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import com.woowacamp.storage.config.IntegrationTestBase;
import com.woowacamp.storage.domain.folderoperation.entity.FolderOperationState;
import com.woowacamp.storage.domain.folderoperation.entity.FolderOperationStateId;
import com.woowacamp.storage.domain.folderoperation.entity.FolderOperationStatus;
import com.woowacamp.storage.domain.folderoperation.entity.FolderOperationType;
import com.woowacamp.storage.domain.folderoperation.repository.FolderOperationStateJpaRepository;
import com.woowacamp.storage.domain.folderoperation.repository.projection.ActiveMoveReservationProjection;
import com.woowacamp.storage.global.error.CustomException;
import com.woowacamp.storage.global.error.ErrorCode;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FolderOperationStateServiceIntegrationTest extends IntegrationTestBase {

	@Autowired
	private FolderOperationStateService folderOperationStateService;

	@Autowired
	private FolderOperationStateJpaRepository folderOperationStateJpaRepository;

	@BeforeEach
	void setUp() {
		folderOperationStateJpaRepository.deleteAllInBatch();
	}

	@AfterEach
	void tearDown() {
		folderOperationStateJpaRepository.deleteAllInBatch();
	}

	@Nested
	@DisplayName("insertActiveMove")
	class InsertActiveMoveTest {
		@Test
		@DisplayName("(root_id, folder_id) 복합 PK 기준으로 동일 folder_id라도 root_id가 다르면 저장된다")
		void insertActiveMove_success_withCompositePrimaryKey() {
			// when
			folderOperationStateService.insertActiveMove(1L, 10L, "/1/10/", 120, 1000L);
			folderOperationStateService.insertActiveMove(2L, 10L, "/2/10/", 130, 1001L);

			// then
			assertThat(folderOperationStateJpaRepository.count()).isEqualTo(2);
			assertThat(folderOperationStateJpaRepository.findById(new FolderOperationStateId(1L, 10L))).isPresent();
			assertThat(folderOperationStateJpaRepository.findById(new FolderOperationStateId(2L, 10L))).isPresent();
		}

		@Test
		@DisplayName("동일 (root_id, folder_id) 중복 insert는 FOLDER_JOB_CONFLICT 예외")
		void insertActiveMove_fail_whenDuplicatePrimaryKey() {
			// given
			folderOperationStateService.insertActiveMove(1L, 10L, "/1/10/", 120, 1000L);

			// when
			CustomException ex = assertThrows(CustomException.class,
				() -> folderOperationStateService.insertActiveMove(1L, 10L, "/1/10/", 140, 1002L));

			// then
			assertThat(ex.getMessage()).isEqualTo(ErrorCode.FOLDER_JOB_CONFLICT.getMessage());
		}

		@Test
		@DisplayName("중복키가 아닌 무결성 위반은 DataIntegrityViolationException 그대로 전파된다")
		void insertActiveMove_fail_whenNotDuplicateIntegrityViolation() {
			// given
			String tooLongRootPath = "/" + "1234567890/".repeat(30);
			assertThat(tooLongRootPath.length()).isGreaterThan(250);

			// when & then
			assertThrows(DataIntegrityViolationException.class,
				() -> folderOperationStateService.insertActiveMove(1L, 11L, tooLongRootPath, 120, 1000L));
		}
	}

	@Nested
	@DisplayName("find")
	class FindTest {
		@Test
		@DisplayName("prefix + MOVE + ACTIVE 조건으로 projected max를 조회한다")
		void findMaxActiveMoveProjectedNamePathLengthByPrefix_success() {
			// given
			saveState(1L, 10L, FolderOperationType.MOVE, FolderOperationStatus.ACTIVE, "/123/10/", 140, 100L);
			saveState(1L, 11L, FolderOperationType.MOVE, FolderOperationStatus.ACTIVE, "/123/10/11/", 180, 101L);
			saveState(1L, 12L, FolderOperationType.MOVE, FolderOperationStatus.DONE, "/123/10/12/", 250, 102L);
			saveState(1L, 13L, FolderOperationType.DELETE, FolderOperationStatus.ACTIVE, "/123/10/13/", 300, 103L);

			// when
			Optional<Integer> result = folderOperationStateService.findMaxActiveMoveProjectedNamePathLengthByPrefix(1L,
				"/123/10/");

			// then
			assertThat(result).contains(180);
		}

		@Test
		@DisplayName("folder id 조회는 root_id 기준으로 교집합만 반환한다")
		void findOperationFolderIdsByRootIdAndFolderIds_success() {
			// given
			saveState(1L, 20L, FolderOperationType.MOVE, FolderOperationStatus.ACTIVE, "/1/20/", 120, 200L);
			saveState(1L, 21L, FolderOperationType.DELETE, FolderOperationStatus.ACTIVE, "/1/21/", 121, 201L);
			saveState(2L, 20L, FolderOperationType.MOVE, FolderOperationStatus.ACTIVE, "/2/20/", 122, 202L);

			// when
			List<Long> result = folderOperationStateService.findOperationFolderIdsByRootIdAndFolderIds(
				1L,
				List.of(20L, 21L, 22L)
			);

			// then
			assertThat(result).containsExactlyInAnyOrder(20L, 21L);
		}

		@Test
		@DisplayName("active move projection 조회는 MOVE + ACTIVE만 반환한다")
		void findActiveMoveOperationFoldersByRootIdAndFolderIds_success() {
			// given
			saveState(1L, 30L, FolderOperationType.MOVE, FolderOperationStatus.ACTIVE, "/1/30/", 150, 300L);
			saveState(1L, 31L, FolderOperationType.MOVE, FolderOperationStatus.DONE, "/1/31/", 200, 301L);
			saveState(1L, 32L, FolderOperationType.DELETE, FolderOperationStatus.ACTIVE, "/1/32/", 250, 302L);

			// when
			List<ActiveMoveReservationProjection> result =
				folderOperationStateService.findActiveMoveOperationFoldersByRootIdAndFolderIds(1L, List.of(30L, 31L, 32L));

			// then
			assertThat(result).hasSize(1);
			assertThat(result.get(0).getFolderId()).isEqualTo(30L);
			assertThat(result.get(0).getProjectedMaxNamePathLength()).isEqualTo(150);
		}

		@Test
		@DisplayName("prefix 하위 ACTIVE MOVE folderId만 조회한다")
		void findActiveMoveFolderIdsByRootIdAndPrefix_success() {
			saveState(1L, 70L, FolderOperationType.MOVE, FolderOperationStatus.ACTIVE, "/1/70/", 120, 700L);
			saveState(1L, 71L, FolderOperationType.MOVE, FolderOperationStatus.ACTIVE, "/1/70/71/", 130, 701L);
			saveState(1L, 72L, FolderOperationType.MOVE, FolderOperationStatus.DONE, "/1/70/72/", 140, 702L);
			saveState(1L, 73L, FolderOperationType.DELETE, FolderOperationStatus.ACTIVE, "/1/70/73/", 150, 703L);
			saveState(1L, 80L, FolderOperationType.MOVE, FolderOperationStatus.ACTIVE, "/1/80/", 160, 704L);

			List<Long> result = folderOperationStateService.findActiveMoveFolderIdsByRootIdAndPrefix(1L, "/1/70/");

			assertThat(result).containsExactlyInAnyOrder(70L, 71L);
		}

		@Test
		@DisplayName("단건 조회: 결과가 1건이면 해당 ACTIVE MOVE를 반환한다")
		void findSingleActiveMoveOperationByRootIdAndFolderIds_single() {
			saveState(1L, 90L, FolderOperationType.MOVE, FolderOperationStatus.ACTIVE, "/1/90/", 120, 900L);

			Optional<ActiveMoveReservationProjection> result =
				folderOperationStateService.findSingleActiveMoveOperationByRootIdAndFolderIds(1L, List.of(90L, 91L));

			assertThat(result).isPresent();
			assertThat(result.get().getFolderId()).isEqualTo(90L);
		}

		@Test
		@DisplayName("단건 조회: 결과가 2건 이상이면 예외를 던진다")
		void findSingleActiveMoveOperationByRootIdAndFolderIds_fail_whenMultipleRows() {
			saveState(1L, 91L, FolderOperationType.MOVE, FolderOperationStatus.ACTIVE, "/1/91/", 120, 901L);
			saveState(1L, 92L, FolderOperationType.MOVE, FolderOperationStatus.ACTIVE, "/1/92/", 121, 902L);

			assertThrows(CustomException.class,
				() -> folderOperationStateService.findSingleActiveMoveOperationByRootIdAndFolderIds(1L, List.of(91L, 92L)));
		}
	}

	@Nested
	@DisplayName("batchUpdateActiveMoveProjectedMaxNamePathLengthIfLessThan")
	class BatchUpdateTest {
		@Test
		@DisplayName("기존 값보다 큰 값만 반영하고 작은 값은 유지한다")
		void batchUpdate_success_withMonotonicIncreaseOnly() {
			// given
			saveState(1L, 40L, FolderOperationType.MOVE, FolderOperationStatus.ACTIVE, "/1/40/", 100, 400L);
			saveState(1L, 41L, FolderOperationType.MOVE, FolderOperationStatus.ACTIVE, "/1/41/", 200, 401L);

			// when
			folderOperationStateService.batchUpdateActiveMoveProjectedMaxNamePathLengthIfLessThan(
				1L,
				Map.of(40L, 150, 41L, 180)
			);

			// then
			FolderOperationState updated40 = folderOperationStateJpaRepository.findById(new FolderOperationStateId(1L, 40L))
				.orElseThrow();
			FolderOperationState updated41 = folderOperationStateJpaRepository.findById(new FolderOperationStateId(1L, 41L))
				.orElseThrow();
			assertThat(updated40.getProjectedMaxNamePathLength()).isEqualTo(150);
			assertThat(updated41.getProjectedMaxNamePathLength()).isEqualTo(200);
		}

		@Test
		@DisplayName("batchUpdate 중 예외 발생 시 예외가 전파되고 업데이트는 반영되지 않는다")
		void batchUpdate_fail_whenInvalidParameter() {
			// given
			saveState(1L, 50L, FolderOperationType.MOVE, FolderOperationStatus.ACTIVE, "/1/50/", 100, 500L);
			Map<Long, Integer> invalidUpdates = new HashMap<>();
			invalidUpdates.put(50L, 150);
			invalidUpdates.put(null, 160);

			// when & then
			assertThrows(NullPointerException.class,
				() -> folderOperationStateService.batchUpdateActiveMoveProjectedMaxNamePathLengthIfLessThan(1L,
					invalidUpdates));

			FolderOperationState unchanged = folderOperationStateJpaRepository.findById(new FolderOperationStateId(1L, 50L))
				.orElseThrow();
			assertThat(unchanged.getProjectedMaxNamePathLength()).isEqualTo(100);
		}
	}

	@Nested
	@DisplayName("delete")
	class DeleteTest {
		@Test
		@DisplayName("root_id + folder_id 기준으로 삭제한다")
		void delete_success() {
			// given
			saveState(1L, 60L, FolderOperationType.MOVE, FolderOperationStatus.ACTIVE, "/1/60/", 100, 600L);

			// when
			int deletedCount = folderOperationStateService.delete(1L, 60L);

			// then
			assertThat(deletedCount).isEqualTo(1);
			assertThat(folderOperationStateJpaRepository.findById(new FolderOperationStateId(1L, 60L))).isEmpty();
		}

		@Test
		@DisplayName("삭제 대상이 없으면 0을 반환한다")
		void delete_noTarget() {
			assertThat(folderOperationStateService.delete(1L, 999L)).isZero();
		}
	}

	private void saveState(Long rootId, Long folderId, FolderOperationType operationType, FolderOperationStatus operationStatus,
		String rootNameFullPath, int projectedMaxNamePathLength, Long jobId) {
		folderOperationStateJpaRepository.save(
			FolderOperationState.builder()
				.rootId(rootId)
				.folderId(folderId)
				.operationType(operationType)
				.operationState(operationStatus)
				.rootNameFullPath(rootNameFullPath)
				.projectedMaxNamePathLength(projectedMaxNamePathLength)
				.jobId(jobId)
				.build()
		);
	}
}
