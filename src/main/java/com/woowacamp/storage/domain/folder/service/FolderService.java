package com.woowacamp.storage.domain.folder.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.file.repository.FileMetadataRepository;
import com.woowacamp.storage.domain.folder.dto.CursorType;
import com.woowacamp.storage.domain.folder.dto.FolderContentsDto;
import com.woowacamp.storage.domain.folder.dto.FolderContentsSortField;
import com.woowacamp.storage.domain.folder.dto.request.CreateFolderReqDto;
import com.woowacamp.storage.domain.folder.dto.request.FolderMoveDto;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.event.FolderSizeEvent;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataRepository;
import com.woowacamp.storage.domain.folder.utils.FolderSearchUtil;
import com.woowacamp.storage.domain.folder.utils.QueryExecuteTemplate;
import com.woowacamp.storage.domain.message.event.MessageInfoEvent;
import com.woowacamp.storage.domain.user.entity.User;
import com.woowacamp.storage.domain.user.repository.UserRepository;
import com.woowacamp.storage.global.background.BackgroundJob;
import com.woowacamp.storage.global.constant.CommonConstant;
import com.woowacamp.storage.global.constant.PermissionType;
import com.woowacamp.storage.global.constant.UploadStatus;
import com.woowacamp.storage.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.woowacamp.storage.domain.folder.entity.FolderMetadataFactory.*;
import static com.woowacamp.storage.global.constant.CommonConstant.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FolderService {
	private static final long INITIAL_CURSOR_ID = 0L;
	private final FileMetadataJpaRepository fileMetadataJpaRepository;
	private final FileMetadataRepository fileMetadataRepository;
	private final FolderMetadataJpaRepository folderMetadataJpaRepository;
	private final FolderMetadataRepository folderMetadataRepository;
	private final RedisLockService redisLockService;
	private final UserRepository userRepository;
	private final FolderSearchUtil folderSearchUtil;
	private final Executor searchThreadPoolExecutor;
	private final BackgroundJob backgroundJob;
	private final FolderCommitService folderCommitService;

	private final ApplicationEventPublisher publisher;

	@Value("${constant.batchSize}")
	private int pageSize;
	@Value("${constant.retryCnt}")
	private int retryCnt;

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void checkFolderOwnedBy(long folderId, long userId) {
		FolderMetadata folderMetadata = folderMetadataJpaRepository.findByIdForUpdate(folderId)
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);

		if (!folderMetadata.getOwnerId().equals(userId)) {
			throw ErrorCode.ACCESS_DENIED.baseException();
		}
	}

	@Transactional(readOnly = true)
	public FolderContentsDto getFolderContents(Long folderId, Long cursorId, CursorType cursorType, int limit,
		FolderContentsSortField sortBy, Sort.Direction sortDirection, LocalDateTime dateTime, Long size,
		boolean ownerRequested) {
		List<FolderMetadata> folders = new ArrayList<>();
		List<FileMetadata> files = new ArrayList<>();

		if (cursorType.equals(CursorType.FILE)) {
			files = fetchFiles(folderId, cursorId, limit, sortBy, sortDirection, dateTime, size, ownerRequested);
		} else if (cursorType.equals(CursorType.FOLDER)) {
			folders = fetchFolders(folderId, cursorId, limit, sortBy, sortDirection, dateTime, size, ownerRequested);
			if (folders.size() < limit) {
				files = fetchFiles(folderId, INITIAL_CURSOR_ID, limit - folders.size(), sortBy, sortDirection, dateTime,
					size, ownerRequested);
			}
		}

		return new FolderContentsDto(folders, files);
	}

	/**
	 * 폴더 단위가 아닌, 사용자 기준으로 락을 걸고 작업을 한다.
	 * 따라서 폴더 이동의 경우는 동시에 처리되지 않는다.
	 * 동시에 여러 폴더 이동이 진행될때 싸이클이 발생할 수 있기 때문에 락을 걸고 진행.
	 */
	@Transactional
	public void moveFolder(Long sourceFolderId, FolderMoveDto dto) {
		FolderMetadata sourceFolder = folderMetadataJpaRepository.findByIdNotDeleted(sourceFolderId)
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);
		FolderMetadata targetFolder = folderMetadataJpaRepository.findByIdNotDeleted(dto.targetFolderId())
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);

		folderSearchUtil.folderLockCheck(sourceFolder.getId(), null);
		int targetFolderDepth = folderSearchUtil.folderLockCheck(targetFolder.getId(),
			sourceFolderId);// target은 source의 자식이면 안된다.

		// 락을 건 후에 삭제되지 않았는지 체크
		folderMetadataJpaRepository.findByIdNotDeleted(targetFolder.getId())
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);

		validateInvalidMove(targetFolder, sourceFolder);

		validateFolderDepth(sourceFolder.getId(), targetFolder.getId(), targetFolderDepth);

		// 목적지에 동일 폴더를 생성하지 않도록 락이 필요하다. 누군가 폴더를 생성해서 같은 이름이 생길 수 있기 때문.
		// 또한 트랜잭션 내부에서 락을 사용하면 커밋 전에 락이 해제되기 때문에 일관성이 깨질 수 있다.
		String moveFolderLock = targetFolder.getId() + "/" + sourceFolder.getUploadFolderName();
		redisLockService.runWithWatchdogLock(moveFolderLock,
			() -> folderCommitService.duplicatedCheckAndMoveCommit(targetFolder.getCopy(), sourceFolder.getCopy()));

		// TODO 하위 경로 공유 상태 변경 필요
		// eventPublisher.publishEvent(
		// 	new FolderMoveEvent(this, sourceFolder,
		// 		folderMetadataJpaRepository.findById(dto.targetFolderId()).get()));
	}

	/**
	 * root folder를 이동하려하는지 확인
	 * 같은 폴더 내에서 이동하려하는지 확인
	 */
	private void validateInvalidMove(FolderMetadata targetFolder, FolderMetadata sourceFolder) {
		if (Objects.equals(sourceFolder.getId(), targetFolder.getId())) {
			throw ErrorCode.FOLDER_MOVE_NOT_AVAILABLE.baseException();
		}
		if (sourceFolder.getParentFolderId() == null) {
			throw ErrorCode.FOLDER_MOVE_NOT_AVAILABLE.baseException();
		}
		if (Objects.equals(sourceFolder.getParentFolderId(), targetFolder.getId())) {
			throw ErrorCode.FOLDER_MOVE_NOT_AVAILABLE.baseException();
		}
	}

	/**
	 * sourceFolder의 최대 깊이 + 이동하려는 폴더의 깊이가 50을 넘는지 확인
	 */
	private void validateFolderDepth(Long sourceFolderId, Long targetFolderId, int targetFolderCurrentDepth) {
		int sourceFolderLeafDepth = getLeafDepth(sourceFolderId, 1, targetFolderId);
		if (sourceFolderLeafDepth + targetFolderCurrentDepth > MAX_FOLDER_DEPTH) {
			throw ErrorCode.EXCEED_MAX_FOLDER_DEPTH.baseException();
		}
	}

	/**
	 * currentFolderId로부터 최대 깊이를 구하는 dfs
	 * 이 과정 중, targetFolderId가 포함돼 있으면 예외 발생
	 */
	private int getLeafDepth(long currentFolderId, int currentDepth, long targetFolderId) {
		List<Long> childFolderIds = folderMetadataJpaRepository.findIdsByParentFolderId(currentFolderId);
		if (isExistsPendingFile(currentFolderId)) {
			throw ErrorCode.CANNOT_MOVE_FOLDER_WHEN_UPLOADING.baseException();
		}
		if (childFolderIds.isEmpty()) {
			return currentDepth;
		}
		int result = 0;
		for (Long childFolderId : childFolderIds) {
			if (Objects.equals(childFolderId, targetFolderId)) {
				throw ErrorCode.FOLDER_MOVE_NOT_AVAILABLE.baseException();
			}
			result = Math.max(result, getLeafDepth(childFolderId, currentDepth + 1, targetFolderId));
		}
		return result;
	}

	private boolean isExistsPendingFile(long currentFolderId) {
		return fileMetadataJpaRepository.existsByParentFolderIdAndUploadStatus(currentFolderId, UploadStatus.PENDING);
	}

	private List<FileMetadata> fetchFiles(Long folderId, Long cursorId, int limit, FolderContentsSortField sortBy,
		Sort.Direction direction, LocalDateTime dateTime, Long size, boolean ownerRequested) {
		List<FileMetadata> files = fileMetadataJpaRepository.selectFilesWithPagination(folderId, cursorId, sortBy,
			direction, limit, dateTime, size);
		if (!ownerRequested) {
			files = files.stream().filter(file -> !file.isSharingExpired()).toList();
		}
		return files;
	}

	private List<FolderMetadata> fetchFolders(Long folderId, Long cursorId, int limit, FolderContentsSortField sortBy,
		Sort.Direction direction, LocalDateTime dateTime, Long size, boolean ownerRequested) {
		List<FolderMetadata> folders = folderMetadataJpaRepository.selectFoldersWithPagination(folderId, cursorId,
			sortBy, direction, limit, dateTime, size);
		if (!ownerRequested) {
			folders = folders.stream().filter(folder -> !folder.isSharingExpired()).toList();
		}
		return folders;
	}

	/**
	 *
	 */

	// TODO : 현재 테스트를 위해 쓰기 권한으로 생성한다. 테스트가 끝나면 제거 필요
	@Transactional
	public Long createFolder(CreateFolderReqDto req) {
		User user = userRepository.findById(req.userId()).orElseThrow(ErrorCode.USER_NOT_FOUND::baseException);
		validateFolderName(req);

		long parentFolderId = req.parentFolderId();
		FolderMetadata parentFolder = folderMetadataJpaRepository.findById(parentFolderId)
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);
		validateFolder(req);
		FolderMetadata folderMetadata = createFolderMetadata(user, parentFolder, req);
		folderMetadata.updateShareStatus(PermissionType.WRITE, LocalDateTime.now().plusYears(1));
		FolderMetadata newFolder = folderMetadataJpaRepository.save(folderMetadata);

		return newFolder.getId();
	}

	/**
	 * 같은 depth(부모 폴더가 같음)에 동일한 이름의 폴더가 있는지 확인
	 * 최대 depth가 50 이하인지 확인
	 */
	private void validateFolder(CreateFolderReqDto req) {
		if (folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(req.parentFolderId(),
			req.uploadFolderName())) {
			throw ErrorCode.INVALID_FILE_NAME.baseException();
		}
		int depth = folderSearchUtil.folderDepthCheck(req.parentFolderId());
		if (depth >= MAX_FOLDER_DEPTH) {
			throw ErrorCode.EXCEED_MAX_FOLDER_DEPTH.baseException();
		}
	}

	/**
	 * 부모 폴더가 요청한 사용자의 폴더인지 확인
	 */
	private void validatePermission(FolderMetadata folderMetadata, long userId) {
		if (!folderMetadata.getOwnerId().equals(userId)) {
			throw ErrorCode.ACCESS_DENIED.baseException();
		}
	}

	/**
	 * 폴더 이름에 금칙어가 있는지 확인
	 */
	private static void validateFolderName(CreateFolderReqDto req) {
		if (Arrays.stream(CommonConstant.FILE_NAME_BLACK_LIST)
			.anyMatch(character -> req.uploadFolderName().indexOf(character) != -1)) {
			throw ErrorCode.INVALID_FILE_NAME.baseException();
		}
	}

	/**
	 *
	 * 하위 폴더 및 파일까지 탐색하여 삭제를 진행합니다.
	 * BFS로 탐색하며, leaf 노드부터 제거합니다.
	 */
	public void deleteFolder(Long folderId, Long userId) {
		FolderMetadata folderMetadata = folderMetadataJpaRepository.findById(folderId)
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);

		if (folderMetadata.isDeleted()) {
			throw ErrorCode.FOLDER_NOT_FOUND.baseException();
		}

		if (!folderMetadata.getOwnerId().equals(userId)) {
			throw ErrorCode.ACCESS_DENIED.baseException();
		}

		// 부모 폴더 정보가 없으면 루트 폴더를 제거하는 요청으로 예외를 반환한다.
		if (folderMetadata.getParentFolderId() == null) {
			throw ErrorCode.INVALID_DELETE_REQUEST.baseException();
		}

		// 상위에 이동, 삭제 작업이 없는지 확인한다.
		// 이런 작업이 시간이 오래 걸리니까 이런 작업을 비동기 처리하고 Future 같은걸로 받아서 처리해도 좋을 것 같다.
		// 다만 위의 검증 과정이 메모리에서 이뤄지는 거라서 별 차이 없을 것 같다. 만약 검증 과정이 복잡하거나, 다른 추가 작업이 발생한다면 고려할만 하다고 생각한다.
		folderSearchUtil.folderLockCheck(folderMetadata.getId(), null);

		// 삭제 요청이 들어온 폴더를 제거한다.
		folderMetadataJpaRepository.softDeleteById(folderMetadata.getId());

		// 삭제는 스레드 풀이 처리하도록 한다.
		deleteFolderTree(folderMetadata);

		// 삭제한 폴더의 용량 계산을 진행한다.
		// metadataService.calculateSize(folderMetadata.getParentFolderId());
	}

	/**
	 * 현재 폴더 기준으로 하위 파일 트리를 제거하는 메소드
	 * 하위 폴더 N개를 조회하고, N개를 순회하며 재귀로 탐색 진행
	 */
	public void deleteFolderTree(FolderMetadata folderMetadata) {
		long folderId = folderMetadata.getId();
		log.info("[Delete Start Pk] {}", folderId);

		// pageSize만큼 페이징 처리하여 하위 폴더 조회하고, 각각에 대해 재귀호출 진행
		searchThreadPoolExecutor.execute(
			() -> QueryExecuteTemplate.<FolderMetadata>selectFilesAndExecuteWithCursor(pageSize,
				findFolder -> folderMetadataRepository.findByParentFolderIdWithLastId(folderId,
					findFolder == null ? null : findFolder.getId(), pageSize),
				folderMetadataList -> folderMetadataList.forEach(folder -> deleteFolderTree(folder))));

		//현재 폴더 삭제 -> 리프부터 삭제
		fileDeleteWithParentFolder(folderMetadata);
		backgroundJob.addForDeleteFolder(folderMetadata);

		// 삭제 시작한 폴더의 하위 파일 제거
		searchThreadPoolExecutor.execute(() -> {
			fileDeleteWithParentFolder(folderMetadata);
		});
	}

	// 부모 폴더 조건까지 포함해서 file 리스트 페이징 조회. 그리고 여기서 바로 삭제 쿼리 날리면 BackgroundJob은 필요 없지 않나?
	// 만약 하위 폴더 수가 적은데 트리 깊이가 깊은 경우에는 BackgroundJob이 도움은 될듯. DB 접근 횟수를 줄여주니까
	private void fileDeleteWithParentFolder(FolderMetadata folderMetadata) {
		searchThreadPoolExecutor.execute(() -> {
			QueryExecuteTemplate.<FileMetadata>selectFilesAndExecuteWithCursor(pageSize,
				findFile -> fileMetadataRepository.findFileMetadataByLastId(folderMetadata.getId(),
					findFile == null ? null : findFile.getId(), pageSize),
				fileMetadataList -> backgroundJob.addForDeleteFile(fileMetadataList));
		});
	}

	// TODO 파일 업로드 구현되면 원격 파일 삭제
	private void deleteBinaryFile(FileMetadata fileMetadata) {

	}

	public void doHardDelete() {
		LocalDateTime timeLimit = LocalDateTime.now().minusDays(hardDeleteDuration);
		QueryExecuteTemplate.<FolderMetadata>selectFilesAndExecuteWithCursor(pageSize,
			findFolder -> folderMetadataRepository.findSoftDeletedFolderWithLastIdAndDuration(
				findFolder == null ? null : findFolder.getId(), pageSize, timeLimit),
			folderMetadataList -> folderMetadataRepository.deleteAll(folderMetadataList));
	}

	public void findOrphanFolderAndSoftDelete() {
		QueryExecuteTemplate.<FolderMetadata>selectFilesAndExecuteWithCursor(pageSize,
			findFolder -> folderMetadataRepository.findSoftDeletedFolderWithLastId(
				findFolder == null ? null : findFolder.getId(), pageSize),
			folderMetadataList -> folderMetadataList.forEach(folder -> deleteFolderTree(folder)));
	}

	@Transactional
	public int updateFolderSize(UUID uuid, long id, long size) {
		FolderMetadata folderMetadata = folderMetadataJpaRepository.findById(id)
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);

		// 최상위 폴더라면 용량 처리할 필요가 없다
		if (folderMetadata.getParentFolderId() == null) {
			publisher.publishEvent(new MessageInfoEvent(uuid, folderMetadata.getId()));
			return 1;
		}
		// 사이즈 업데이트
		int result = folderMetadataJpaRepository.updateFolderSize(size, folderMetadata.getId(),
			folderMetadata.getVersion());
		if (result == 0) {
			return result;
		}

		// MessageInfo 완료 처리
		publisher.publishEvent(new MessageInfoEvent(uuid, folderMetadata.getId()));

		// 상위 폴더 이벤트 전파
		publisher.publishEvent(new FolderSizeEvent(folderMetadata.getParentFolderId(), size));

		return result;
	}

}
