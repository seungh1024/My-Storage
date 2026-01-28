package com.woowacamp.storage.domain.folder.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataRepository;
import com.woowacamp.storage.domain.folder.entity.FolderJob;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.helper.FolderBatchUpdateHelper;
import com.woowacamp.storage.domain.folder.repository.FolderJobJpaRepository;
import com.woowacamp.storage.domain.folder.repository.FolderJobRepository;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataRepository;
import com.woowacamp.storage.domain.folder.utils.QueryExecuteTemplate;
import com.woowacamp.storage.domain.folder.utils.StackSerializationUtil;
import com.woowacamp.storage.global.error.ErrorCode;
import com.woowacamp.storage.global.util.StorageStringUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 폴더 이동 시 서브 트리의 경로를 DFS로 배치 업데이트하는 프로세서
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FolderMoveProcessor {

	private final FolderMetadataRepository folderMetadataRepository;
	private final FolderMetadataJpaRepository folderMetadataJpaRepository;
	private final FileMetadataRepository fileMetadataRepository;
	private final FolderJobRepository folderJobRepository;
	private final FolderJobJpaRepository folderJobJpaRepository;

	private final FolderBatchUpdateHelper batchUpdateHelper;
	private final Environment environment;

	@Value("${constant.batchSize}")
	private int pageSize;

	@Value("${constant.batchLimit}")
	private int batchLimit;

	/**
	 * 폴더 이동의 실제 배치 작업을 수행
	 * 트랜잭션 없음
	 */
	public void processMove(Long rootFolderId) {
		log.info("[FolderMoveProcessor] Start processing. rootFolderId={}", rootFolderId);

		// 1. FolderJob 조회
		FolderJob job = folderJobJpaRepository.findById(rootFolderId)
			.orElseThrow(() -> ErrorCode.FOLDER_NOT_FOUND.baseException(
				StorageStringUtil.format("FolderJob not found. folderId: {}", rootFolderId)));

		// 2. Job 획득 시도 (CAS) - 별도 트랜잭션
		boolean acquired = folderJobRepository.tryAcquireJob(rootFolderId);
		if (!acquired) {
			log.warn("[FolderMoveProcessor] Job already running. rootFolderId={}", rootFolderId);
			return;
		}

		try {
			// 3. DFS 배치 업데이트
			processDFS(job);

			// 4. 완료 처리 - 별도 트랜잭션
			folderJobRepository.markJobCompleted(rootFolderId);

			log.info("[FolderMoveProcessor] Completed successfully. rootFolderId={}", rootFolderId);

		} catch (Exception e) {
			log.error("[FolderMoveProcessor] Failed. rootFolderId={}", rootFolderId, e);

			// 실패 처리 - 별도 트랜잭션
			folderJobRepository.markJobFailed(rootFolderId);

			throw ErrorCode.MESSAGE_CONSUME_FAILED.baseException(
				StorageStringUtil.format("FolderMove failed. folderId: {}", rootFolderId), e);
		}
	}

	/**
	 * DFS로 서브 트리 탐색 및 배치 업데이트
	 * 트랜잭션 없음
	 */
	private void processDFS(FolderJob job) {
		Stack<Long> parentStack = StackSerializationUtil.deserialize(job.getParentStack());

		if (parentStack.isEmpty()) {
			parentStack.push(job.getId());
			job.resetFolderProgress();
			job.resetFileProgress();
		}

		List<FolderMetadata> folderBuffer = new ArrayList<>();
		List<FileMetadata> fileBuffer = new ArrayList<>();

		while (!parentStack.isEmpty()) {
			Long currentParentId = parentStack.pop();

			FolderMetadata parent = folderMetadataJpaRepository.findById(currentParentId)
				.orElseThrow(() -> ErrorCode.FOLDER_NOT_FOUND.baseException(
					StorageStringUtil.format("Parent not found. parentId: {}", currentParentId)));

			// ✅ 새로운 부모를 처리할 때마다 커서 초기화 체크
			boolean isNewParent = !currentParentId.equals(job.getCurrentParentId());
			if (isNewParent) {
				// 새로운 부모이므로 커서 초기화
				job.updateCurrentParent(currentParentId);
				job.resetFolderProgress();  // lastFolderId = null
				job.resetFileProgress();     // lastFileId = null
			}

			// 폴더 처리
			processFoldersOfParent(job, currentParentId, parentStack, folderBuffer, parent);

			// 파일 처리
			processFilesOfParent(job, currentParentId, fileBuffer, parentStack, parent);

			if (!parentStack.isEmpty() && (!folderBuffer.isEmpty() || !fileBuffer.isEmpty())) {
				flushBuffers(job, folderBuffer, fileBuffer, parentStack.peek(), null, null, parentStack);
			}
		}

		// 남은 버퍼 flush
		if (!folderBuffer.isEmpty() || !fileBuffer.isEmpty()) {
			flushBuffers(job, folderBuffer, fileBuffer, job.getId(), null, null, parentStack);
		}
	}

	/**
	 * 특정 부모의 자식 폴더 처리
	 */
	private void processFoldersOfParent(FolderJob job, Long parentId,
		Stack<Long> parentStack, List<FolderMetadata> folderBuffer, FolderMetadata parent) {
		Long lastFolderId = job.getId().equals(parentId) ? job.getLastFolderId() : null;

		Long[] lastProcessed = new Long[1];
		QueryExecuteTemplate.<FolderMetadata>selectFilesAndExecuteWithCursor(pageSize,
			lastFolder -> folderMetadataRepository.findByParentFolderIdWithLastId(parentId,
				lastFolder == null ? lastFolderId : lastFolder.getId(), pageSize),
			childFolders -> {
				String parentNamePath = parent.getNameFullPath();
				String parentIdPath = parent.getIdFullPath();

				for (FolderMetadata childFolder : childFolders) {
					childFolder.updateIdFullPath(parentIdPath);
					childFolder.updateNameFullPath(parentNamePath);
					childFolder.updateNamePathLength(childFolder.getNameFullPath().length());

					folderBuffer.add(childFolder);

					if (folderBuffer.size() >= batchLimit) {
						batchUpdateHelper.batchUpdateFoldersAndSaveProgress(
							folderBuffer, job.getId(), parentId, childFolder.getId(), null, parentStack);
						folderBuffer.clear();
					}

					parentStack.push(childFolder.getId());

					lastProcessed[0] = childFolder.getId();
				}
			});

		Long lastProcessedId = lastProcessed[0];
		if (!folderBuffer.isEmpty()) {
			batchUpdateHelper.batchUpdateFoldersAndSaveProgress(
				folderBuffer, job.getId(), parentId, lastProcessedId, null, parentStack);
			folderBuffer.clear();
		} else {
			batchUpdateHelper.saveProgressOnly(job.getId(), parentId, lastProcessedId, null, parentStack);
		}
	}

	/**
	 * 특정 부모의 자식 파일 처리
	 */
	private void processFilesOfParent(FolderJob job, Long parentId,
		List<FileMetadata> fileBuffer, Stack<Long> parentStack, FolderMetadata parent) {
		Long lastFileId = job.getId().equals(parentId) ? job.getLastFileId() : null;

		Long[] lastProcessed = new Long[1];
		QueryExecuteTemplate.<FileMetadata>selectFilesAndExecuteWithCursor(pageSize,
			lastFile -> fileMetadataRepository.findFileMetadataByLastId(parentId,
				lastFile == null ? lastFileId : lastFile.getId(), pageSize),
			childFiles -> {

				String parentNamePath = parent.getNameFullPath();
				String parentIdPath = parent.getIdFullPath();

				for (FileMetadata childFile : childFiles) {
					childFile.updateIdFullPath(parentIdPath);
					childFile.updateNameFullPath(parentNamePath);
					childFile.updateNamePathLength(childFile.getNameFullPath().length());

					fileBuffer.add(childFile);

					if (fileBuffer.size() >= batchLimit) {
						// 파일 처리 시에는 스택 변경 없음
						Stack<Long> emptyStack = new Stack<>();
						batchUpdateHelper.batchUpdateFilesAndSaveProgress(
							fileBuffer, job.getId(), parentId, null, childFile.getId(), emptyStack);
						fileBuffer.clear();
					}

					lastProcessed[0] = childFile.getId();
				}
			}
		);

		Long lastProcessedId = lastProcessed[0];
		if (!fileBuffer.isEmpty()) {
			Stack<Long> emptyStack = new Stack<>();
			batchUpdateHelper.batchUpdateFilesAndSaveProgress(
				fileBuffer, job.getId(), parentId, null, lastProcessedId, emptyStack);
			fileBuffer.clear();
		} else {
			Stack<Long> emptyStack = new Stack<>();
			batchUpdateHelper.saveProgressOnly(job.getId(), parentId, null, lastProcessedId, emptyStack);
		}

	}

	/**
	 * 폴더와 파일 버퍼를 함께 flush
	 */
	private void flushBuffers(FolderJob job, List<FolderMetadata> folderBuffer, List<FileMetadata> fileBuffer,
		Long currentParentId, Long lastFolderId, Long lastFileId, Stack<Long> parentStack) {
		if (!folderBuffer.isEmpty()) {
			batchUpdateHelper.batchUpdateFoldersAndSaveProgress(
				folderBuffer, job.getId(), currentParentId, lastFolderId, lastFileId, parentStack);
			folderBuffer.clear();
		}

		if (!fileBuffer.isEmpty()) {
			batchUpdateHelper.batchUpdateFilesAndSaveProgress(
				fileBuffer, job.getId(), currentParentId, lastFolderId, lastFileId, parentStack);
			fileBuffer.clear();
		}
	}
}