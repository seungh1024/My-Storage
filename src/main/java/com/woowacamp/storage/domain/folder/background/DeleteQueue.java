package com.woowacamp.storage.domain.folder.background;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataRepository;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataRepository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;

/**
 * 파일과 폴더를 큐잉하여 처리하는 클래스
 */
@Component
@RequiredArgsConstructor
public class DeleteQueue {
	// 멀티스레딩 환경을 고려해서 BlockingQueue 사용
	private LinkedBlockingQueue<FolderMetadata> folderDeleteQueue;
	private LinkedBlockingQueue<FileMetadata> fileDeleteQueue;
	private final FolderMetadataRepository folderMetadataRepository;
	private final FileMetadataRepository fileMetadataRepository;
	private final static int DELETE_DELAY = 1000 * 30 * 5;
	private final static int BATCH_SIZE = 100;

	@PostConstruct
	public void init() {
		folderDeleteQueue = new LinkedBlockingQueue<>();
		fileDeleteQueue = new LinkedBlockingQueue<>();
	}

	@PreDestroy
	public void cleanUp() {
		deleteScheduling();
		folderBatchDelete();
		fileBatchDelete();
	}

	public void addFolderList(List<FolderMetadata> folderMetadataList) {
		folderDeleteQueue.addAll(folderMetadataList);
		if (folderDeleteQueue.size() >= BATCH_SIZE) {
			folderBatchDelete();
		}
	}

	/**
	 * 파일은 원격 파일을 하나씩 삭제하는 과정이 있어서 하나씩 추가
	 * @param fileMetadata
	 */
	public void addFile(FileMetadata fileMetadata) {
		fileDeleteQueue.offer(fileMetadata);
		if (fileDeleteQueue.size() >= BATCH_SIZE) {
			fileBatchDelete();
		}
	}

	/**
	 * 큐잉된 파일 및 폴더를 배치 쿼리로 삭제하는 스케줄러
	 * 멀티스레드 환경에서 큐에 작업을 추가할 때마다 확인을 하는 것 보다 단일 스레드로 처리하는 것이 더 효율적이어서 스케줄러로 처리
	 */
	@Scheduled(fixedDelay = DELETE_DELAY)
	private void deleteScheduling() {
		while (folderDeleteQueue.size() >= BATCH_SIZE){
			folderBatchDelete();
		}

		while (fileDeleteQueue.size() >= BATCH_SIZE){
			fileBatchDelete();
		}
	}

	private void folderBatchDelete() {
		List<FolderMetadata> folderMetadataList = new ArrayList<>();
		folderDeleteQueue.drainTo(folderMetadataList, BATCH_SIZE);
		List<Long> batchList = folderMetadataList.stream().map(FolderMetadata::getId).toList();
		folderMetadataRepository.deleteAllByIdInBatch(batchList);
	}

	private void fileBatchDelete() {
		List<FileMetadata> fileMetadataList = new ArrayList<>();
		fileDeleteQueue.drainTo(fileMetadataList, BATCH_SIZE);
		List<Long> batchList = fileMetadataList.stream().map(FileMetadata::getId).toList();
		fileMetadataRepository.deleteAllByIdInBatch(batchList);
	}
}
