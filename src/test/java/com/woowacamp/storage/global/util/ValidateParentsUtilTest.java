package com.woowacamp.storage.global.util;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.folderoperation.repository.projection.ActiveMoveReservationProjection;
import com.woowacamp.storage.domain.folderoperation.service.FolderOperationStateService;
import com.woowacamp.storage.global.error.CustomException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ValidateParentsUtilTest {

	private final FolderOperationStateService folderOperationStateService = mock(FolderOperationStateService.class);
	private final FolderMetadataJpaRepository folderMetadataJpaRepository = mock(FolderMetadataJpaRepository.class);
	private final ValidateParentsUtil validateParentsUtil = new ValidateParentsUtil(folderOperationStateService,
		folderMetadataJpaRepository);

	@Test
	@DisplayName("이동 검증 성공: 상위 경로에 ACTIVE operation이 없으면 통과한다")
	void validateParentsFolderLock_success_whenNoActiveOperation() {
		List<Long> sourceParentIds = List.of(1L, 2L, 10L);
		List<Long> targetParentIds = List.of(1L, 3L, 20L);

		given(folderOperationStateService.findOperationFolderIdsByRootIdAndFolderIds(
			eq(1L), anyList())).willReturn(List.of());

		assertDoesNotThrow(() -> validateParentsUtil.validateParentsFolderLock(1L, sourceParentIds, targetParentIds));
	}

	@Test
	@DisplayName("이동 검증 실패: 상위 경로에 ACTIVE operation이 있으면 PARENT_LOCKED 예외")
	void validateParentsFolderLock_fail_whenActiveOperationExists() {
		List<Long> sourceParentIds = List.of(1L, 2L, 10L);
		List<Long> targetParentIds = List.of(1L, 3L, 20L);

		given(folderOperationStateService.findOperationFolderIdsByRootIdAndFolderIds(
			eq(1L), anyList())).willReturn(List.of(1L, 2L));

		assertThrows(CustomException.class,
			() -> validateParentsUtil.validateParentsFolderLock(1L, sourceParentIds, targetParentIds));
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
	@DisplayName("create 검증 성공: ACTIVE MOVE 상태가 없으면 부모 경로를 그대로 반환한다")
	void validateAndResolveForCreate_success_whenNoActiveMove() {
		FolderMetadata parentFolder = folderWithNamePathLength(30L, 1L, "/1/30/", 10);

		given(folderOperationStateService.findSingleActiveMoveOperationByRootIdAndFolderIds(
			eq(1L), anyList())).willReturn(Optional.empty());

		ValidateParentsUtil.CreatePathValidationResult result = assertDoesNotThrow(
			() -> validateParentsUtil.validateAndResolveForCreate(parentFolder, 3, 250));
		assertEquals("/1/30/", result.projectedParentIdFullPath());
		assertEquals("/name/path/", result.projectedParentNameFullPath());
		assertFalse(result.hasReservation());
		then(folderOperationStateService).should(never())
			.updateActiveMoveProjectedMaxNamePathLengthIfLessThan(anyLong(), anyLong(), anyInt());
	}

	@Test
	@DisplayName("create 검증 성공: 상위 ACTIVE MOVE가 있으면 이동 후 예상 경로와 예약 정보를 반환한다")
	void validateAndResolveForCreate_success_whenNeedReservationUpdate() {
		FolderMetadata parentFolder = folderWithNamePathLength(30L, 1L, "/1/30/", "/r/src/", 10);
		ActiveMoveReservationProjection state = activeMoveState(30L, 8, "/1/40/30/");

		given(folderOperationStateService.findSingleActiveMoveOperationByRootIdAndFolderIds(
			eq(1L), anyList())).willReturn(Optional.of(state));
		given(folderMetadataJpaRepository.findByIdNotDeleted(30L)).willReturn(Optional.of(
			folderWithNamePathLength(30L, 1L, "/1/40/30/", "/r/dst/src/", 11)
		));

		ValidateParentsUtil.CreatePathValidationResult result = assertDoesNotThrow(
			() -> validateParentsUtil.validateAndResolveForCreate(parentFolder, 10, 250));
		assertEquals("/1/40/30/", result.projectedParentIdFullPath());
		assertEquals("/r/dst/src/", result.projectedParentNameFullPath());
		assertTrue(result.hasReservation());
		assertEquals(30L, result.reservationFolderId());
		assertEquals(22, result.reservationProjectedMaxNamePathLength());
	}

	@Test
	@DisplayName("create 검증 실패: ACTIVE MOVE 예약 최대 길이가 제한 이상이면 예외를 던진다")
	void validateAndResolveForCreate_fail_whenProjectedLengthExceeded() {
		FolderMetadata parentFolder = folderWithNamePathLength(30L, 1L, "/1/30/", "/r/src/", 10);
		ActiveMoveReservationProjection state = activeMoveState(30L, 251, "/1/40/30/");

		given(folderOperationStateService.findSingleActiveMoveOperationByRootIdAndFolderIds(
			eq(1L), anyList())).willReturn(Optional.of(state));
		given(folderMetadataJpaRepository.findByIdNotDeleted(30L)).willReturn(Optional.of(
			folderWithNamePathLength(30L, 1L, "/1/40/30/", "/r/dst/src/", 11)
		));

		assertThrows(CustomException.class,
			() -> validateParentsUtil.validateAndResolveForCreate(parentFolder, 1, 250));
		then(folderOperationStateService).should(never())
			.updateActiveMoveProjectedMaxNamePathLengthIfLessThan(anyLong(), anyLong(), anyInt());
	}

	private FolderMetadata folder(Long id, Long rootId, String idFullPath) {
		return FolderMetadata.builder()
			.id(id)
			.rootId(rootId)
			.idFullPath(idFullPath)
			.build();
	}

	private FolderMetadata folderWithNamePathLength(Long id, Long rootId, String idFullPath, int namePathLength) {
		return folderWithNamePathLength(id, rootId, idFullPath, "/name/path/", namePathLength);
	}

	private FolderMetadata folderWithNamePathLength(Long id, Long rootId, String idFullPath, String nameFullPath,
		int namePathLength) {
		return FolderMetadata.builder()
			.id(id)
			.rootId(rootId)
			.idFullPath(idFullPath)
			.nameFullPath(nameFullPath)
			.namePathLength(namePathLength)
			.build();
	}

	private ActiveMoveReservationProjection activeMoveState(Long folderId, int projectedMaxNamePathLength,
		String rootIdFullPath) {
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
			public String getRootIdFullPath() {
				return rootIdFullPath;
			}
		};
	}
}
