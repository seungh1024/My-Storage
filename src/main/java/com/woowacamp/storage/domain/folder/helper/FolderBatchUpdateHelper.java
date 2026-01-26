package com.woowacamp.storage.domain.folder.helper;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Stack;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderJobJpaRepository;
import com.woowacamp.storage.domain.folder.utils.StackSerializationUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * JdbcTemplate을 사용한 고성능 배치 업데이트 헬퍼
 * 배치 업데이트 + FolderJob 진행 상황 저장을 하나의 트랜잭션으로 처리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FolderBatchUpdateHelper {

	private final JdbcTemplate jdbcTemplate;
	private final FolderJobJpaRepository folderJobJpaRepository;

	/**
	 * 폴더 배치 업데이트 + 진행 상황 저장
	 * 하나의 트랜잭션으로 원자성 보장
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void batchUpdateFoldersAndSaveProgress(
		List<FolderMetadata> folders, Long jobId, Long currentParentId,
		Long lastFolderId, Long lastFileId, Stack<Long> parentStack) {

		// 1. 폴더 배치 업데이트
		if (folders != null && !folders.isEmpty()) {
			String sql = """
				UPDATE folder_metadata
				SET id_full_path = ?,
					name_full_path = ?,
					name_path_length = ?,
					updated_at = CURRENT_TIMESTAMP
				WHERE folder_metadata_id = ?
			""";

			jdbcTemplate.batchUpdate(sql, folders, folders.size(),
				(PreparedStatement ps, FolderMetadata folder) -> {
					ps.setString(1, folder.getIdFullPath());
					ps.setString(2, folder.getNameFullPath());
					ps.setInt(3, folder.getNamePathLength());
					ps.setLong(4, folder.getId());
				});

			log.debug("[FolderBatchUpdateHelper] Batch updated {} folders", folders.size());
		}

		// 2. 진행 상황 저장
		String stackJson = StackSerializationUtil.serialize(parentStack);
		folderJobJpaRepository.updateProgress(
			jobId, currentParentId, lastFolderId, lastFileId, stackJson);

		log.debug("[FolderBatchUpdateHelper] Progress saved. jobId={}, currentParentId={}, " +
				"lastFolderId={}, lastFileId={}, stackSize={}",
			jobId, currentParentId, lastFolderId, lastFileId, parentStack.size());
	}

	/**
	 * 파일 배치 업데이트 + 진행 상황 저장
	 * 하나의 트랜잭션으로 원자성 보장
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void batchUpdateFilesAndSaveProgress(
		List<FileMetadata> files, Long jobId, Long currentParentId,
		Long lastFolderId, Long lastFileId, Stack<Long> parentStack) {

		// 1. 파일 배치 업데이트
		if (files != null && !files.isEmpty()) {
			String sql = """
				UPDATE file_metadata
				SET id_full_path = ?,
					name_full_path = ?,
					name_path_length = ?,
					updated_at = CURRENT_TIMESTAMP
				WHERE file_metadata_id = ?
			""";

			jdbcTemplate.batchUpdate(sql, files, files.size(),
				(PreparedStatement ps, FileMetadata file) -> {
					ps.setString(1, file.getIdFullPath());
					ps.setString(2, file.getNameFullPath());
					ps.setInt(3, file.getNamePathLength());
					ps.setLong(4, file.getId());
				});

			log.debug("[FolderBatchUpdateHelper] Batch updated {} files", files.size());
		}

		// 2. 진행 상황 저장
		String stackJson = StackSerializationUtil.serialize(parentStack);
		folderJobJpaRepository.updateProgress(
			jobId, currentParentId, lastFolderId, lastFileId, stackJson);

		log.debug("[FolderBatchUpdateHelper] Progress saved. jobId={}, currentParentId={}, " +
				"lastFolderId={}, lastFileId={}",
			jobId, currentParentId, lastFolderId, lastFileId);
	}

	/**
	 * 진행 상황만 저장 (버퍼가 비어있을 때)
	 * 별도 트랜잭션으로 처리
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void saveProgressOnly(Long jobId, Long currentParentId, Long lastFolderId,
		Long lastFileId, Stack<Long> parentStack) {
		String stackJson = StackSerializationUtil.serialize(parentStack);
		folderJobJpaRepository.updateProgress(
			jobId, currentParentId, lastFolderId, lastFileId, stackJson);

		log.debug("[FolderBatchUpdateHelper] Progress only saved. jobId={}, currentParentId={}, " +
				"lastFolderId={}, lastFileId={}",
			jobId, currentParentId, lastFolderId, lastFileId);
	}
}