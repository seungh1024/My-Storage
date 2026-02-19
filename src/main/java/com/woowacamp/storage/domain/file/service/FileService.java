package com.woowacamp.storage.domain.file.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.file.dto.FileMoveDto;
import com.woowacamp.storage.domain.file.dto.command.FileMoveLockContext;
import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.file.repository.FileMetadataRepository;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.service.FolderSizeAdjustmentService;
import com.woowacamp.storage.domain.folder.utils.FolderPathParser;
import com.woowacamp.storage.domain.folder.utils.QueryExecuteTemplate;
import com.woowacamp.storage.global.constant.UploadStatus;
import com.woowacamp.storage.global.error.ErrorCode;
import com.woowacamp.storage.global.util.StorageStringUtil;
import com.woowacamp.storage.global.util.ValidateParentsUtil;
import com.woowacamp.storage.lock.annotation.DistributedLock;

import lombok.RequiredArgsConstructor;

import static com.woowacamp.storage.global.constant.CommonConstant.*;
import static com.woowacamp.storage.global.error.ErrorCode.*;

@Service
@RequiredArgsConstructor
public class FileService {
	private final FileMetadataRepository fileMetadataRepository;
	private final FileMetadataJpaRepository fileMetadataJpaRepository;
	private final FolderMetadataJpaRepository folderMetadataRepository;
	private final FolderSizeAdjustmentService folderSizeAdjustmentService;
	private final ValidateParentsUtil validateParentsUtil;

	@Value("${constant.batchSize}")
	private int pageSize;

	/**
	 * FileMetadata의 parentFolderId를 변경한다.
	 * source folder, target folder의 모든 정보를 수정한다.
	 */
	@DistributedLock(keys = """
            {
                @lockKeys.folderJob(#lockContext.rootId()),
                @lockKeys.folderName(#lockContext.targetFolderId(), #lockContext.fileName())
               }
        """)
	public void moveFile(FileMoveLockContext lockContext, FileMoveDto dto) {
		FileMetadata fileMetadata = fileMetadataJpaRepository.findById(lockContext.fileId())
			.orElseThrow(ErrorCode.FILE_NOT_FOUND::baseException);

		FolderMetadata targetFolder = folderMetadataRepository.findByIdNotDeleted(lockContext.targetFolderId())
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);
		if (!targetFolder.getOwnerId().equals(dto.userId())) {
			throw ErrorCode.ACCESS_DENIED.baseException();
		}
		validateMetadata(dto, fileMetadata, targetFolder);

		// 고아 방지를 위해 상위 부모중 삭제 작업 진행 중이면 이동 실패.
		validateParentsUtil.validateParentsFolderLock(targetFolder);

		long originParentId = fileMetadata.getParentFolderId();
		FolderMetadata originParentFolder = folderMetadataRepository.findByIdNotDeleted(originParentId)
			.orElseThrow(ErrorCode.FOLDER_NOT_FOUND::baseException);
		fileMetadata.updateParentFolderId(lockContext.targetFolderId());
		fileMetadataJpaRepository.save(fileMetadata);

		List<Long> sourceParentIds = parsePathIds(originParentFolder.getIdFullPath());
		List<Long> targetParentIds = parsePathIds(targetFolder.getIdFullPath());
		Map<Long, Long> deltaByFolderId = folderSizeAdjustmentService.mergeDeltaByFolderIds(
			sourceParentIds,
			targetParentIds,
			fileMetadata.getFileSize()
		);
		folderSizeAdjustmentService.applySizeDeltas(deltaByFolderId);
	}

	private void validateMetadata(FileMoveDto dto, FileMetadata fileMetadata, FolderMetadata targetFolder) {
		if (!Objects.equals(fileMetadata.getOwnerId(), targetFolder.getOwnerId()) || !Objects.equals(
			fileMetadata.getOwnerId(), dto.userId())) {
			throw ACCESS_DENIED.baseException(StorageStringUtil.format(
				"File is not owned by user. fileId: {}, fileOwnerId: {}, targetFolderOwnerId: {}, userId: {}",
				fileMetadata.getId(), fileMetadata.getOwnerId(), targetFolder.getOwnerId(), dto.userId()));
		}
		if (fileMetadata.getUploadStatus() != UploadStatus.SUCCESS) {
			throw ErrorCode.FILE_NOT_FOUND.baseException();
		}
		if (fileMetadataJpaRepository.existsByParentFolderIdAndUploadFileNameAndUploadStatusNot(targetFolder.getId(),
			fileMetadata.getUploadFileName(), UploadStatus.FAIL)) {
			throw ErrorCode.FILE_NAME_DUPLICATE.baseException();
		}
	}

	@Transactional(readOnly = true)
	public FileMetadata getFileMetadataBy(Long fileId, Long userId) {
		FileMetadata fileMetadata = fileMetadataJpaRepository.findById(fileId)
			.orElseThrow(ErrorCode.FILE_NOT_FOUND::baseException);

		if (!Objects.equals(fileMetadata.getOwnerId(), userId)) {
			throw ACCESS_DENIED.baseException();
		}
		return fileMetadata;
	}

	/**
	 * 단건 삭제는 고아될 일이 없기에 상위 탐지를 하지 않는다.
	 */
	@Transactional
	public void deleteFile(Long fileId, Long userId) {
		FileMetadata fileMetadata = fileMetadataJpaRepository.findByIdAndOwnerIdAndUploadStatusNot(fileId, userId,
			UploadStatus.FAIL).orElseThrow(ACCESS_DENIED::baseException);
		fileMetadataJpaRepository.softDelete(fileMetadata.getId());
		folderMetadataRepository.findById(fileMetadata.getParentFolderId())
			.ifPresent(parentFolder -> {
				List<Long> parentIds = parsePathIds(parentFolder.getIdFullPath());
				Map<Long, Long> deltaByFolderId = folderSizeAdjustmentService.mergeDeltaByFolderIds(
					List.of(),
					parentIds,
					-fileMetadata.getFileSize()
				);
				folderSizeAdjustmentService.applySizeDeltas(deltaByFolderId);
			});
	}

	public void doHardDelete() {
		LocalDateTime timeLimit = LocalDateTime.now().minusDays(hardDeleteDuration);
		QueryExecuteTemplate.<FileMetadata>selectFilesAndExecuteWithCursor(pageSize,
			findFile -> fileMetadataRepository.findSoftDeletedFileWithLastIdAndDuration(
				findFile == null ? null : findFile.getId(), pageSize, timeLimit),
			fileMetadataList -> fileMetadataRepository.deleteAll(fileMetadataList));
	}

	private List<Long> parsePathIds(String idFullPath) {
		return FolderPathParser.parsing(idFullPath)
			.orElseThrow(() -> ErrorCode.FOLDER_PATH_ERROR.baseException(
				StorageStringUtil.format("Failed to parse path. idFullPath={}", idFullPath)))
			.stream()
			.map(Long::parseLong)
			.toList();
	}
}
