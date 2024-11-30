package com.woowacamp.storage.domain.folder.background;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Function;

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
 * 현재 파일, 폴더의 삭제 작업과 용량 업데이트를 처리함
 */
@Component
@RequiredArgsConstructor
public class BackgroundQueue {
	// 멀티스레딩 환경을 고려해서 BlockingQueue 사용
	private LinkedBlockingQueue<FolderMetadata> folderDeleteQueue;
	private LinkedBlockingQueue<FileMetadata> fileDeleteQueue;
	private LinkedBlockingQueue<FolderMetadata> folderUpdateQueue;
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

	public void addForDeleteFolder(List<FolderMetadata> folderMetadataList) {
		folderDeleteQueue.addAll(folderMetadataList);
		if (folderDeleteQueue.size() >= BATCH_SIZE) {
			folderBatchDelete();
		}
	}

	/**
	 * 파일은 원격 파일을 하나씩 삭제하는 과정이 있어서 하나씩 추가
	 * @param fileMetadata
	 */
	public void addForDeleteFile(FileMetadata fileMetadata) {
		fileDeleteQueue.offer(fileMetadata);
		if (fileDeleteQueue.size() >= BATCH_SIZE) {
			fileBatchDelete();
		}
	}

	private void folderBatchDelete() {
		this.<FolderMetadata>doBatchJob(folderDeleteQueue,
			folderList -> folderList.stream().map(FolderMetadata::getId).toList(),
			batchList -> folderMetadataRepository.deleteAllByIdInBatch(batchList));
	}

	private void fileBatchDelete() {
		this.<FileMetadata>doBatchJob(fileDeleteQueue,
			fileList -> fileList.stream().map(FileMetadata::getId).toList(),
			batchList -> fileMetadataRepository.deleteAllByIdInBatch(batchList));
	}

	/**
	 * 큐에 있는 데이터를 BATCH_SIZE만큼 추출하여 PK 리스트로 변환 후, 해당 데이터를 바탕으로 배치 작업을 수행
	 * @param queue
	 * @param function
	 * @param consumer
	 * @param <T>
	 */
	private <T> void doBatchJob(LinkedBlockingQueue<T> queue, Function<List<T>, List<Long>> function,
		Consumer<List<Long>> consumer) {
		List<T> metadataList = new ArrayList<>();
		queue.drainTo(metadataList, BATCH_SIZE);
		List<Long> batchList = function.apply(metadataList);
		consumer.accept(batchList);
	}

	/**
	 * 큐잉된 파일 및 폴더를 배치 쿼리로 삭제하는 스케줄러
	 * 멀티스레드 환경에서 큐에 작업을 추가할 때마다 확인을 하는 것 보다 단일 스레드로 처리하는 것이 더 효율적이어서 스케줄러로 처리
	 */
	@Scheduled(fixedDelay = DELETE_DELAY)
	private void deleteScheduling() {
		while (folderDeleteQueue.size() >= BATCH_SIZE) {
			folderBatchDelete();
		}

		while (fileDeleteQueue.size() >= BATCH_SIZE) {
			fileBatchDelete();
		}
	}

}
