package com.woowacamp.storage.domain.folder.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
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
import com.woowacamp.storage.domain.folder.dto.MovePlan;
import com.woowacamp.storage.domain.folder.dto.request.CreateFolderReqDto;
import com.woowacamp.storage.domain.folder.dto.request.FolderMoveDto;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.event.FolderMoveEvent;
import com.woowacamp.storage.domain.folder.event.FolderSizeEvent;
import com.woowacamp.storage.domain.folder.repository.FolderJobJpaRepository;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataRepository;
import com.woowacamp.storage.domain.folder.utils.FolderJobStatus;
import com.woowacamp.storage.domain.folder.utils.QueryExecuteTemplate;
import com.woowacamp.storage.domain.folderoperation.service.FolderOperationStateService;
import com.woowacamp.storage.domain.message.event.MessageInfoEvent;
import com.woowacamp.storage.domain.message.repository.MessageInfoJpaRepository;
import com.woowacamp.storage.domain.message.util.MessageStatus;
import com.woowacamp.storage.domain.user.entity.User;
import com.woowacamp.storage.domain.user.repository.UserRepository;
import com.woowacamp.storage.global.background.BackgroundJob;
import com.woowacamp.storage.global.constant.CommonConstant;
import com.woowacamp.storage.global.error.ErrorCode;
import com.woowacamp.storage.global.util.StorageStringUtil;
import com.woowacamp.storage.global.util.ValidateParentsUtil;
import com.woowacamp.storage.lock.annotation.DistributedLock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.woowacamp.storage.domain.folder.entity.FolderMetadataFactory.*;
import static com.woowacamp.storage.global.constant.CommonConstant.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FolderService {
	private static final String FOLDER_ID_MESSAGE = "Folder id: {}";
	private static final long INITIAL_CURSOR_ID = 0L;
	private final FileMetadataJpaRepository fileMetadataJpaRepository;
	private final FileMetadataRepository fileMetadataRepository;
	private final FolderMetadataJpaRepository folderMetadataJpaRepository;
	private final FolderMetadataRepository folderMetadataRepository;
	private final UserRepository userRepository;
	private final Executor searchThreadPoolExecutor;
	private final BackgroundJob backgroundJob;
	private final FolderJobJpaRepository folderJobJpaRepository;
	private final ValidateParentsUtil validateParentsUtil;

	private final ApplicationEventPublisher publisher;
	private final MessageInfoJpaRepository messageInfoJpaRepository;

	private final FolderOperationStateService folderOperationStateService;

	@Value("${constant.batchSize}")
	private int pageSize;

	@Value("${folder.path.maxLength}")
	private int maxPathLength;

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

		FolderContentsDto folderContentsDto = new FolderContentsDto(folders, files);

		return folderContentsDto;
	}

	/**
	 * 폴더 단위 락을 획득한 이후 폴더의 추가 검증과 실제 이동 작업 이벤트를 생성.
	 * 이동 시 중복 이름을 허용하지 않으므로 분산락 획득 후 진행
	 *
	 */
	@DistributedLock(keys = """
		{
		    @lockKeys.folderJob(#dto.rootId()),
		       @lockKeys.folderName(#dto.targetFolderId(), #dto.folderName())
		}
		""")
	public void moveFolder(Long sourceFolderId, FolderMoveDto dto) {
		FolderMetadata sourceFolder = folderMetadataJpaRepository.findByIdNotDeleted(sourceFolderId)
			.orElseThrow(() -> ErrorCode.FOLDER_NOT_FOUND.baseException(
				StorageStringUtil.format(FOLDER_ID_MESSAGE, sourceFolderId)));
		Long sourceRootId = sourceFolder.getRootId() == null ? sourceFolder.getId() : sourceFolder.getRootId();

		validateFolderOwner(sourceFolder, dto.userId());

		FolderMetadata targetFolder = folderMetadataJpaRepository.findByIdNotDeleted(dto.targetFolderId())
			.orElseThrow(() -> ErrorCode.FOLDER_NOT_FOUND.baseException(
				StorageStringUtil.format(FOLDER_ID_MESSAGE, dto.targetFolderId())));
		validateFolderOwner(targetFolder, dto.userId());

		FolderMetadata sourceParentFolder = folderMetadataJpaRepository.findByIdNotDeleted(
				sourceFolder.getParentFolderId())
			.orElseThrow(() -> ErrorCode.FOLDER_NOT_FOUND.baseException(
				StorageStringUtil.format(FOLDER_ID_MESSAGE, sourceFolder.getParentFolderId())));
		validateFolderOwner(sourceParentFolder, dto.userId());

		validateInvalidMove(targetFolder, sourceFolder);

		MovePlan movePlan = buildMovePlanAndValidate(sourceFolder, targetFolder, sourceParentFolder);
		getFolderJobLock(sourceRootId, sourceFolderId, movePlan);

		validateParentsUtil.validateParentsFolderLock(sourceParentFolder, targetFolder);

		Long originalParentId = sourceFolder.getParentFolderId();
		sourceFolder.updateParentFolderId(targetFolder.getId());
		sourceFolder.updateMoveRootPath(
			movePlan.nextSourceIdFullPath(),
			movePlan.nextSourceNameFullPath(),
			movePlan.nextSourceNamePathLength()
		);
		sourceFolder.markMoving();
		folderMetadataJpaRepository.save(sourceFolder);

		log.info("[moveFolder] About to publish events. sourceId={}, originalParentId={}, targetId={}, size={}",
			sourceFolderId, originalParentId, targetFolder.getId(), sourceFolder.getSize());
		publisher.publishEvent(new FolderSizeEvent(originalParentId, -sourceFolder.getSize()));
		log.info("[moveFolder] Published FolderSizeEvent for originalParent. parentId={}, size={}",
			originalParentId, -sourceFolder.getSize());
		publisher.publishEvent(new FolderSizeEvent(targetFolder.getId(), sourceFolder.getSize()));
		log.info("[moveFolder] Published FolderSizeEvent for target. targetId={}, size={}",
			targetFolder.getId(), sourceFolder.getSize());

		publisher.publishEvent(new FolderMoveEvent(sourceFolderId));
		log.info("[moveFolder] Published FolderMoveEvent. folderId={}", sourceFolderId);
		log.info("[moveFolder] All events published successfully.");
	}

	/**
	 * root folder를 이동하려하는지 확인
	 * 같은 폴더 내에서 이동하려하는지 확인
	 * 이미 작업 중인 폴더인지 확인(source, target 모두 확인한다. source,target의 상위 작업 보장과 순환 구조 방지를 위함이다.)
	 */
	private void validateInvalidMove(FolderMetadata targetFolder, FolderMetadata sourceFolder) {
		Long sourceRootId = sourceFolder.getRootId() == null ? sourceFolder.getId() : sourceFolder.getRootId();
		Long targetRootId = targetFolder.getRootId() == null ? targetFolder.getId() : targetFolder.getRootId();

		if (!Objects.equals(targetRootId, sourceRootId)) {
			throw ErrorCode.FOLDER_MOVE_NOT_AVAILABLE.baseException(
				StorageStringUtil.format("Root id mismatch. sourceRootId: {}, targetRootId: {}",
					sourceFolder.getRootId(), targetFolder.getRootId()));
		}
		if (Objects.equals(sourceFolder.getId(), targetFolder.getId())) {
			throw ErrorCode.FOLDER_MOVE_NOT_AVAILABLE.baseException();
		}
		if (sourceFolder.getParentFolderId() == null) {
			throw ErrorCode.FOLDER_MOVE_NOT_AVAILABLE.baseException();
		}
		if (Objects.equals(sourceFolder.getParentFolderId(), targetFolder.getId())) {
			throw ErrorCode.FOLDER_MOVE_NOT_AVAILABLE.baseException();
		}
		// target folder가 source folder의 하위 폴더인지 확인
		if (targetFolder.getIdFullPath().startsWith(sourceFolder.getIdFullPath())) {
			throw ErrorCode.FOLDER_MOVE_NOT_AVAILABLE.baseException();
		}

		validateDuplicatedFolderName(targetFolder.getId(), sourceFolder.getUploadFolderName());
	}

	public void validateFolderOwner(FolderMetadata folderMetadata, long userId) {
		if (!folderMetadata.getOwnerId().equals(userId)) {
			throw ErrorCode.ACCESS_DENIED.baseException();
		}
	}

	private void validateDuplicatedFolderName(Long parentFolderId, String folderName) {
		if (folderMetadataJpaRepository.existsByParentFolderIdAndUploadFolderName(parentFolderId, folderName)) {
			throw ErrorCode.FILE_NAME_DUPLICATE.baseException();
		}
	}

	private MovePlan buildMovePlanAndValidate(FolderMetadata sourceFolder, FolderMetadata targetFolder, FolderMetadata sourceParentFolder) {
		Long sourceRootId = sourceFolder.getRootId() == null ? sourceFolder.getId() : sourceFolder.getRootId();
		int sourceParentPathLength = sourceParentFolder.getNamePathLength();
		int targetParentPathLength = targetFolder.getNamePathLength();
		int longestFolderLength = folderMetadataJpaRepository.findMaxFolderNamePathLengthByPrefix(sourceRootId,
				sourceFolder.getNameFullPath())
			.orElse(sourceFolder.getNamePathLength());
		int longestFileLength = fileMetadataJpaRepository.findMaxFileNamePathLengthByPrefix(sourceRootId,
			sourceFolder.getNameFullPath()).orElse(0); // 파일이 없으면 0

		int currentMaxNamePathLength = Math.max(longestFileLength, longestFolderLength); // source folder 하위의 가장 긴 경로 길이
		int parentPathLengthChange = targetParentPathLength - sourceParentPathLength; // 부모 경로 길이 변화량
		int projectedMaxNamePathLength = currentMaxNamePathLength + parentPathLengthChange; // 기존 폴더의 최대 경로 길이
		int projectedActiveMoveMaxNamePathLength = folderOperationStateService.findMaxActiveMoveProjectedNamePathLengthByPrefix(
				sourceRootId,
				sourceFolder.getIdFullPath())
			.map(maxProjectedNamePathLength -> maxProjectedNamePathLength + parentPathLengthChange)
			.orElse(Integer.MIN_VALUE); // 활성 이동 상태의 최대 경로 길이
		int finalProjectedMaxNamePathLength = Math.max(projectedMaxNamePathLength, projectedActiveMoveMaxNamePathLength);

		if (finalProjectedMaxNamePathLength >= maxPathLength) {
			throw ErrorCode.EXCEED_MAX_PATH_LENGTH.baseException(
				StorageStringUtil.format(
					"Total Path is too long. currentProjectedMax={}, activeMoveProjectedMax={}, parentPathLengthChange={}, finalProjectedMax={}",
					currentMaxNamePathLength,
					projectedActiveMoveMaxNamePathLength,
					parentPathLengthChange,
					finalProjectedMaxNamePathLength
				));
		}

		String nextSourceIdFullPath = StorageStringUtil.format(
			"{}{}/",
			targetFolder.getIdFullPath(),
			sourceFolder.getId()
		);
		String nextSourceNameFullPath = StorageStringUtil.format("{}{}/",
			targetFolder.getNameFullPath(), sourceFolder.getUploadFolderName());
		int nextSourceNamePathLength = nextSourceNameFullPath.length();

		return new MovePlan(
			parentPathLengthChange,
			finalProjectedMaxNamePathLength,
			nextSourceIdFullPath,
			nextSourceNameFullPath,
			nextSourceNamePathLength
		);
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

	@DistributedLock(keys = """
		{
		    @lockKeys.folderJob(#req.rootId()),
		    @lockKeys.folderName(#req.parentFolderId(), #req.uploadFolderName())
		}
		""")
	public Long createFolder(CreateFolderReqDto req) {
		User user = userRepository.findById(req.userId()).orElseThrow(ErrorCode.USER_NOT_FOUND::baseException);

		long parentFolderId = req.parentFolderId();
		FolderMetadata parentFolder = folderMetadataJpaRepository.findById(parentFolderId)
			.orElseThrow(() -> ErrorCode.FOLDER_NOT_FOUND.baseException(
				StorageStringUtil.format("folder not found when creating folder. find folder id:{}", parentFolderId)));
		validateFolder(req, parentFolder);
		validateFolderOwner(parentFolder, req.userId());


		FolderMetadata folderMetadata = createFolderMetadata(user, parentFolder, req);

		// 저장 및 pk 전체 경로 업데이트
		FolderMetadata newFolder = folderMetadataJpaRepository.save(folderMetadata);
		newFolder.updateIdFullPath(parentFolder.getIdFullPath());
		folderMetadataJpaRepository.save(newFolder);

		return newFolder.getId();
	}

	/**
	 * 금칙어 확인
	 * 같은 depth(부모 폴더가 같음)에 동일한 이름의 폴더가 있는지 확인
	 * 최대 경로 길이 250 이내인지 확인
	 */
	private void validateFolder(CreateFolderReqDto req, FolderMetadata parentFolder) {
		// 금칙어 확인
		if (Arrays.stream(CommonConstant.FILE_NAME_BLACK_LIST)
			.anyMatch(character -> req.uploadFolderName().indexOf(character) != -1)) {
			throw ErrorCode.INVALID_FILE_NAME.baseException();
		}

		// 동일 이름 폴더 확인
		validateDuplicatedFolderName(req.parentFolderId(), req.uploadFolderName());

		// 최대 경로 길이 확인



		// 경로 구분자 때문에 +1을 해줘야 한다.
		int pathLength = parentFolder.getNamePathLength() + req.uploadFolderName().length() + 1;
		if (pathLength >= maxPathLength) {
			throw ErrorCode.EXCEED_MAX_PATH_LENGTH.baseException(
				StorageStringUtil.format("Total Path is too long. path length: {}", pathLength));
		}

		validateParentsUtil.validateMaxNamePathLengthAndReserveForCreate(
			parentFolder,
			req.uploadFolderName().length(),
			maxPathLength
		);
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
		// folderSearchUtil.folderLockCheck(folderMetadata.getId(), null);

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
	public int updateFolderSize(Long id, Long folderId, long size) {
		FolderMetadata folderMetadata = folderMetadataJpaRepository.findById(folderId)
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);

		// 처리되지 않은 메세지가 없다면 리턴
		if (!messageInfoJpaRepository.existsByIdAndStatusIn(id, List.of(MessageStatus.SENT, MessageStatus.PENDING))) {
			log.info("[update folder size] early returned id: {}, folderId: {}, size: {}", id, folderId, size);
			return 1;
		}

		// 사이즈 업데이트
		int result = 0;
		int cnt = 3;
		while (result == 0 && cnt-- > 0) {
			result = folderMetadataJpaRepository.updateFolderSizeWithVersion(size, folderMetadata.getId(),
				folderMetadata.getVersion());
			folderMetadata = folderMetadataJpaRepository.findById(folderId)
				.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);
			log.info("[update folder size] id: {}, folderId: {}, size: {}, update result: {}, version: {}", id,
				folderId, size, result, folderMetadata.getVersion());
		}
		log.info("[update folder size] id: {}, folderId: {}, size: {}, update result: {}", id, folderId, size, result);
		if (result == 0) {
			return 0;
		}
		log.info(
			"[update folder size] id: {}, folderId: {}, size: {}, folderMetadata.id: {}, folderMetadata.rootId:{}, folderMetadata.size:{}",
			id, folderId, size, folderMetadata.getId(), folderMetadata.getRootId(), folderMetadata.getSize());

		// MessageInfo 완료 처리
		publisher.publishEvent(new MessageInfoEvent(id));

		// 상위 폴더 이벤트 전파
		if (folderMetadata.getParentFolderId() != null) {
			publisher.publishEvent(new FolderSizeEvent(folderMetadata.getParentFolderId(), size));
		}

		return result;
	}

	/**
	 * 루트 폴더 기준으로 락을 잡는다. 이 락은 작업 대상 폴더의 락을 잡기 위해 짧게 유지하는 락이다.
	 * 대상 폴더의 락을 잡기 전에 발생하는 동시성 문제를 방지하는 용도이다.
	 * 비용이 크지 않은 검증 과정들을 빠르게 처리 후, 해당 폴더의 제어권을 확보한다.
	 */
	public void getFolderJobLock(Long rootId, Long sourceFolderId, MovePlan movePlan) {
		folderOperationStateService.insertActiveMove(
			rootId,
			sourceFolderId,
			movePlan.nextSourceIdFullPath(),
			movePlan.projectedMaxNamePathLength(),
			sourceFolderId
		);
		try {
			int insertResult = folderJobJpaRepository.insert(
				rootId,                // rootId (PK)
				sourceFolderId,        // folderId (PK)
				sourceFolderId,        // currentParentId
				null,                  // lastFolderId
				null,                  // lastFileId
				"[]",                  // parentStack
				FolderJobStatus.WAITING.name(),
				0
			);

			if (insertResult != 1) {
				throw ErrorCode.FOLDER_JOB_CREATE_FAILED.baseException(
					StorageStringUtil.format("Failed to create folder job. folderId: {}", sourceFolderId));
			}
		} catch (DataIntegrityViolationException e) {
			throw ErrorCode.FOLDER_JOB_CONFLICT.baseException(
				StorageStringUtil.format("Other job already running. folderId: {}", sourceFolderId), e);
		}
	}

}
