package com.woowacamp.storage.domain.folderoperation.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import com.woowacamp.storage.domain.folderoperation.entity.FolderOperationStatus;
import com.woowacamp.storage.domain.folderoperation.entity.FolderOperationType;
import com.woowacamp.storage.domain.folderoperation.repository.FolderOperationStateJpaRepository;
import com.woowacamp.storage.domain.folderoperation.repository.FolderOperationStateRepository;
import com.woowacamp.storage.domain.folderoperation.repository.projection.ActiveMoveReservationProjection;
import com.woowacamp.storage.global.error.CustomException;
import com.woowacamp.storage.global.error.ErrorCode;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class FolderOperationStateServiceTest {

	@Mock
	private FolderOperationStateJpaRepository folderOperationStateJpaRepository;

	@Mock
	private FolderOperationStateRepository folderOperationStateRepository;

	@InjectMocks
	private FolderOperationStateService folderOperationStateService;

	@Nested
	@DisplayName("insertActiveMove")
	class InsertActiveMoveTest {
		@Test
		@DisplayName("성공: ACTIVE MOVE 상태를 insert 한다")
		void insertActiveMove_success() {
			// given
			given(folderOperationStateRepository.insertActiveMove(
				eq(1L),
				eq(10L),
				eq("/1/10/"),
				eq(120),
				eq(10L)
			)).willReturn(1);

			// when & then
			assertDoesNotThrow(
				() -> folderOperationStateService.insertActiveMove(1L, 10L, "/1/10/", 120, 10L));
		}

		@Test
		@DisplayName("실패: 중복 키면 FOLDER_JOB_CONFLICT 예외")
		void insertActiveMove_fail_whenDuplicateKey() {
			// given
			willThrow(new DuplicateKeyException("Duplicate entry"))
				.given(folderOperationStateRepository)
				.insertActiveMove(anyLong(), anyLong(), anyString(), anyInt(), anyLong());

			// when
			CustomException ex = assertThrows(CustomException.class,
				() -> folderOperationStateService.insertActiveMove(1L, 10L, "/1/10/", 120, 10L));

			// then
			assertEquals(ErrorCode.FOLDER_JOB_CONFLICT.getMessage(), ex.getMessage());
		}

		@Test
		@DisplayName("실패: 중복 키가 아닌 무결성 예외는 그대로 전파한다")
		void insertActiveMove_fail_whenNotDuplicateIntegrityViolation() {
			// given
			willThrow(new DataIntegrityViolationException("constraint violation",
				new RuntimeException("Data too long for column 'root_name_full_path'")))
				.given(folderOperationStateRepository)
				.insertActiveMove(anyLong(), anyLong(), anyString(), anyInt(), anyLong());

			// when & then
			assertThrows(DataIntegrityViolationException.class,
				() -> folderOperationStateService.insertActiveMove(1L, 10L, "/1/10/", 120, 10L));
		}
	}

	@Nested
	@DisplayName("find")
	class FindTest {
		@Test
		@DisplayName("findMaxActiveMoveProjectedNamePathLengthByPrefix: MOVE+ACTIVE 조건으로 조회")
		void findMaxActiveMoveProjectedNamePathLengthByPrefix_success() {
			// given
			given(folderOperationStateJpaRepository.findMaxProjectedNamePathLengthInActiveMoveSubtreeByPrefix(
				eq(1L),
				eq("/1/10/"),
				eq(FolderOperationType.MOVE),
				eq(FolderOperationStatus.ACTIVE)
			)).willReturn(Optional.of(180));

			// when
			Optional<Integer> result = folderOperationStateService.findMaxActiveMoveProjectedNamePathLengthByPrefix(1L,
				"/1/10/");

			// then
			assertThat(result).contains(180);
		}

		@Test
		@DisplayName("findOperationFolderIdsByRootIdAndFolderIds: 입력이 비어있으면 즉시 빈 리스트")
		void findOperationFolderIdsByRootIdAndFolderIds_emptyInput() {
			assertThat(folderOperationStateService.findOperationFolderIdsByRootIdAndFolderIds(1L, null)).isEmpty();
			assertThat(folderOperationStateService.findOperationFolderIdsByRootIdAndFolderIds(1L, List.of())).isEmpty();
			then(folderOperationStateJpaRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("findOperationFolderIdsByRootIdAndFolderIds: 입력이 있으면 repository 조회")
		void findOperationFolderIdsByRootIdAndFolderIds_success() {
			// given
			given(folderOperationStateJpaRepository.findFolderIdsByRootIdAndFolderIds(1L, List.of(10L, 20L)))
				.willReturn(List.of(10L));

			// when
			List<Long> result = folderOperationStateService.findOperationFolderIdsByRootIdAndFolderIds(1L,
				List.of(10L, 20L));

			// then
			assertThat(result).containsExactly(10L);
		}

		@Test
		@DisplayName("findActiveMoveOperationFoldersByRootIdAndFolderIds: 입력이 비어있으면 즉시 빈 리스트")
		void findActiveMoveOperationFoldersByRootIdAndFolderIds_emptyInput() {
			assertThat(folderOperationStateService.findActiveMoveOperationFoldersByRootIdAndFolderIds(1L, null)).isEmpty();
			assertThat(folderOperationStateService.findActiveMoveOperationFoldersByRootIdAndFolderIds(1L, List.of()))
				.isEmpty();
			then(folderOperationStateJpaRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("findActiveMoveOperationFoldersByRootIdAndFolderIds: MOVE+ACTIVE 조건으로 조회")
		void findActiveMoveOperationFoldersByRootIdAndFolderIds_success() {
			// given
			ActiveMoveReservationProjection projection = projection(10L, 140);
			given(folderOperationStateJpaRepository.findActiveMoveReservationsByRootIdAndFolderIdsAndTypeAndState(
				eq(1L),
				eq(List.of(10L, 20L)),
				eq(FolderOperationType.MOVE),
				eq(FolderOperationStatus.ACTIVE)
			)).willReturn(List.of(projection));

			// when
			List<ActiveMoveReservationProjection> result =
				folderOperationStateService.findActiveMoveOperationFoldersByRootIdAndFolderIds(1L, List.of(10L, 20L));

			// then
			assertThat(result).hasSize(1);
			assertThat(result.get(0).getFolderId()).isEqualTo(10L);
			assertThat(result.get(0).getProjectedMaxNamePathLength()).isEqualTo(140);
		}

		@Test
		@DisplayName("findActiveMoveFolderIdsByRootIdAndPrefix: MOVE+ACTIVE + prefix 조건으로 조회")
		void findActiveMoveFolderIdsByRootIdAndPrefix_success() {
			given(folderOperationStateJpaRepository.findFolderIdsInActiveMoveSubtreeByPrefix(
				eq(1L),
				eq("/1/10/"),
				eq(FolderOperationType.MOVE),
				eq(FolderOperationStatus.ACTIVE)
			)).willReturn(List.of(10L, 11L));

			List<Long> result = folderOperationStateService.findActiveMoveFolderIdsByRootIdAndPrefix(1L, "/1/10/");

			assertThat(result).containsExactly(10L, 11L);
		}

		@Test
		@DisplayName("findSingleActiveMoveOperationByRootIdAndFolderIds: 결과가 없으면 empty")
		void findSingleActiveMoveOperationByRootIdAndFolderIds_empty() {
			given(folderOperationStateJpaRepository.findActiveMoveReservationsByRootIdAndFolderIdsAndTypeAndState(
				eq(1L),
				eq(List.of(10L, 20L)),
				eq(FolderOperationType.MOVE),
				eq(FolderOperationStatus.ACTIVE)
			)).willReturn(List.of());

			Optional<ActiveMoveReservationProjection> result =
				folderOperationStateService.findSingleActiveMoveOperationByRootIdAndFolderIds(1L, List.of(10L, 20L));

			assertThat(result).isEmpty();
		}

		@Test
		@DisplayName("findSingleActiveMoveOperationByRootIdAndFolderIds: 입력이 비어있으면 empty")
		void findSingleActiveMoveOperationByRootIdAndFolderIds_emptyInput() {
			assertThat(folderOperationStateService.findSingleActiveMoveOperationByRootIdAndFolderIds(1L, null)).isEmpty();
			assertThat(folderOperationStateService.findSingleActiveMoveOperationByRootIdAndFolderIds(1L, List.of()))
				.isEmpty();
			then(folderOperationStateJpaRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("findSingleActiveMoveOperationByRootIdAndFolderIds: 결과가 1건이면 반환")
		void findSingleActiveMoveOperationByRootIdAndFolderIds_single() {
			ActiveMoveReservationProjection projection = projection(10L, 140);
			given(folderOperationStateJpaRepository.findActiveMoveReservationsByRootIdAndFolderIdsAndTypeAndState(
				eq(1L),
				eq(List.of(10L, 20L)),
				eq(FolderOperationType.MOVE),
				eq(FolderOperationStatus.ACTIVE)
			)).willReturn(List.of(projection));

			Optional<ActiveMoveReservationProjection> result =
				folderOperationStateService.findSingleActiveMoveOperationByRootIdAndFolderIds(1L, List.of(10L, 20L));

			assertThat(result).isPresent();
			assertThat(result.get().getFolderId()).isEqualTo(10L);
		}

		@Test
		@DisplayName("findSingleActiveMoveOperationByRootIdAndFolderIds: 결과가 2건 이상이면 예외")
		void findSingleActiveMoveOperationByRootIdAndFolderIds_fail_whenMultipleRows() {
			ActiveMoveReservationProjection first = projection(10L, 140);
			ActiveMoveReservationProjection second = projection(20L, 150);
			List<Long> folderIds = List.of(10L, 20L);
			given(folderOperationStateJpaRepository.findActiveMoveReservationsByRootIdAndFolderIdsAndTypeAndState(
				eq(1L),
				eq(folderIds),
				eq(FolderOperationType.MOVE),
				eq(FolderOperationStatus.ACTIVE)
			)).willReturn(List.of(first, second));

			assertThrows(CustomException.class,
				() -> folderOperationStateService.findSingleActiveMoveOperationByRootIdAndFolderIds(1L,
					folderIds));
		}
	}

	@Nested
	@DisplayName("batchUpdateActiveMoveProjectedMaxNamePathLengthIfLessThan")
	class BatchUpdateTest {
		@Test
		@DisplayName("입력이 비어있으면 batch update를 호출하지 않는다")
		void batchUpdate_skipWhenEmpty() {
			folderOperationStateService.batchUpdateActiveMoveProjectedMaxNamePathLengthIfLessThan(1L, null);
			folderOperationStateService.batchUpdateActiveMoveProjectedMaxNamePathLengthIfLessThan(1L, Map.of());
			then(folderOperationStateRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("입력이 있으면 batch update를 수행한다")
		void batchUpdate_success() {
			// given
			Map<Long, Integer> updates = Map.of(10L, 130, 20L, 140);

			// when
			folderOperationStateService.batchUpdateActiveMoveProjectedMaxNamePathLengthIfLessThan(1L, updates);

			// then
			then(folderOperationStateRepository).should(times(1))
				.batchUpdateActiveMoveProjectedMaxNamePathLengthIfLessThan(1L, updates);
		}
	}

	@Test
	@DisplayName("delete: rootId, folderId 기준으로 삭제한다")
	void delete_success() {
		// given
		given(folderOperationStateJpaRepository.deleteByRootIdAndFolderId(1L, 10L)).willReturn(1);

		// when
		int deleted = folderOperationStateService.delete(1L, 10L);

		// then
		assertThat(deleted).isEqualTo(1);
	}

	private ActiveMoveReservationProjection projection(Long folderId, Integer projectedMaxNamePathLength) {
		return new ActiveMoveReservationProjection() {
			@Override
			public Long getFolderId() {
				return folderId;
			}

			@Override
			public Integer getProjectedMaxNamePathLength() {
				return projectedMaxNamePathLength;
			}

			@Override
			public String getRootNameFullPath() {
				return "/1/" + folderId + "/";
			}
		};
	}
}
