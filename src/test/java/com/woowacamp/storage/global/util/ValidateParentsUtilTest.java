package com.woowacamp.storage.global.util;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folderoperation.repository.projection.ActiveMoveReservationProjection;
import com.woowacamp.storage.domain.folderoperation.service.FolderOperationStateService;
import com.woowacamp.storage.global.error.CustomException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ValidateParentsUtilTest {

	private final FolderOperationStateService folderOperationStateService = mock(FolderOperationStateService.class);
	private final ValidateParentsUtil validateParentsUtil = new ValidateParentsUtil(folderOperationStateService);

	@Test
	@DisplayName("이동 검증 성공: 상위 경로에 ACTIVE operation이 없으면 통과한다")
	void validateParentsFolderLock_success_whenNoActiveOperation() {
		FolderMetadata sourceFolder = folder(10L, 1L, "/1/2/10/");
		FolderMetadata targetFolder = folder(20L, 1L, "/1/3/20/");

		given(folderOperationStateService.findOperationFolderIdsByRootIdAndFolderIds(
			eq(1L), anyList())).willReturn(List.of());

		assertDoesNotThrow(() -> validateParentsUtil.validateParentsFolderLock(sourceFolder, targetFolder));
	}

	@Test
	@DisplayName("이동 검증 실패: 상위 경로에 ACTIVE operation이 있으면 PARENT_LOCKED 예외")
	void validateParentsFolderLock_fail_whenActiveOperationExists() {
		FolderMetadata sourceFolder = folder(10L, 1L, "/1/2/10/");
		FolderMetadata targetFolder = folder(20L, 1L, "/1/3/20/");

		given(folderOperationStateService.findOperationFolderIdsByRootIdAndFolderIds(
			eq(1L), anyList())).willReturn(List.of(1L, 2L));

		assertThrows(CustomException.class,
			() -> validateParentsUtil.validateParentsFolderLock(sourceFolder, targetFolder));
	}

	@Test
	@DisplayName("단일 폴더 검증 성공: rootId가 null이면 folderId를 rootId로 사용한다")
	void validateParentsFolderLock_singleFolder_success_withNullRootId() {
		FolderMetadata targetFolder = folder(30L, null, "/30/31/");

		given(folderOperationStateService.findOperationFolderIdsByRootIdAndFolderIds(
			eq(30L), anyList())).willReturn(List.of());

		assertDoesNotThrow(() -> validateParentsUtil.validateParentsFolderLock(targetFolder));
	}

	@Test
	@DisplayName("단일 폴더 검증 실패: 경로 포맷이 잘못되면 FOLDER_PATH_ERROR 예외")
	void validateParentsFolderLock_singleFolder_fail_whenInvalidPath() {
		FolderMetadata targetFolder = folder(30L, 1L, "invalid-path");

		assertThrows(CustomException.class, () -> validateParentsUtil.validateParentsFolderLock(targetFolder));
		then(folderOperationStateService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("create 검증 성공: ACTIVE MOVE 상태가 없으면 예약 길이 갱신 없이 통과한다")
	void validateMaxNamePathLengthAndReserveForCreate_success_whenNoActiveMove() {
		FolderMetadata parentFolder = folderWithNamePathLength(30L, 1L, "/1/30/", 10);

		given(folderOperationStateService.findActiveMoveOperationFoldersByRootIdAndFolderIds(
			eq(1L), anyList())).willReturn(List.of());

		assertDoesNotThrow(
			() -> validateParentsUtil.validateMaxNamePathLengthAndReserveForCreate(parentFolder, 3, 250));
		then(folderOperationStateService).should(never())
			.batchUpdateActiveMoveProjectedMaxNamePathLengthIfLessThan(anyLong(), anyMap());
	}

	@Test
	@DisplayName("create 검증 성공: 새 경로가 더 길면 ACTIVE MOVE 예약 최대 길이를 갱신한다")
	void validateMaxNamePathLengthAndReserveForCreate_success_whenNeedReservationUpdate() {
		FolderMetadata parentFolder = folderWithNamePathLength(30L, 1L, "/1/30/", 10);
		ActiveMoveReservationProjection state = activeMoveState(30L, 8);

		given(folderOperationStateService.findActiveMoveOperationFoldersByRootIdAndFolderIds(
			eq(1L), anyList())).willReturn(List.of(state));

		assertDoesNotThrow(
			() -> validateParentsUtil.validateMaxNamePathLengthAndReserveForCreate(parentFolder, 10, 250));
		then(folderOperationStateService).should()
			.batchUpdateActiveMoveProjectedMaxNamePathLengthIfLessThan(eq(1L),
				argThat(updateMap -> updateMap.size() == 1 && updateMap.get(30L) == 21));
	}

	@Test
	@DisplayName("create 검증 실패: ACTIVE MOVE 예약 최대 길이가 제한 이상이면 예외를 던진다")
	void validateMaxNamePathLengthAndReserveForCreate_fail_whenProjectedLengthExceeded() {
		FolderMetadata parentFolder = folderWithNamePathLength(30L, 1L, "/1/30/", 10);
		ActiveMoveReservationProjection state = activeMoveState(30L, 250);

		given(folderOperationStateService.findActiveMoveOperationFoldersByRootIdAndFolderIds(
			eq(1L), anyList())).willReturn(List.of(state));

		assertThrows(CustomException.class,
			() -> validateParentsUtil.validateMaxNamePathLengthAndReserveForCreate(parentFolder, 1, 250));
		then(folderOperationStateService).should(never())
			.batchUpdateActiveMoveProjectedMaxNamePathLengthIfLessThan(anyLong(), anyMap());
	}

	private FolderMetadata folder(Long id, Long rootId, String idFullPath) {
		return FolderMetadata.builder()
			.id(id)
			.rootId(rootId)
			.idFullPath(idFullPath)
			.build();
	}

	private FolderMetadata folderWithNamePathLength(Long id, Long rootId, String idFullPath, int namePathLength) {
		return FolderMetadata.builder()
			.id(id)
			.rootId(rootId)
			.idFullPath(idFullPath)
			.namePathLength(namePathLength)
			.build();
	}

	private ActiveMoveReservationProjection activeMoveState(Long folderId, int projectedMaxNamePathLength) {
		return new ActiveMoveReservationProjection() {
			@Override
			public Long getFolderId() {
				return folderId;
			}

			@Override
			public Integer getProjectedMaxNamePathLength() {
				return projectedMaxNamePathLength;
			}
		};
	}
}
