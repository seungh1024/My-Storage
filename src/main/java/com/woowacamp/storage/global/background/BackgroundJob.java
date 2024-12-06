package com.woowacamp.storage.global.background;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;

/**
 * 파일과 폴더에 대한 백그라운드 작업을 처리하는 클래스
 * 현재 파일, 폴더의 삭제 작업과 용량 업데이트를 처리함
 */
@Component
@RequiredArgsConstructor
public class BackgroundJob {
	private static final Logger log = LoggerFactory.getLogger(BackgroundJob.class);
	// 멀티스레딩 환경을 고려해서 BlockingQueue 사용
	private LinkedBlockingQueue<FolderMetadata> folderDeleteQueue;
	private LinkedBlockingQueue<FileMetadata> fileDeleteQueue;
	private final FolderMetadataJpaRepository folderMetadataJpaRepository;
	private final FileMetadataJpaRepository fileMetadataJpaRepository;
	private final Executor deleteThreadPoolExecutor;
	private final static int DELETE_DELAY = 1000*5;
	private int folderCount = 0;
	private int fileCount = 0;
	private final int maxCount = 5;

	@Value("${constant.batchSize}")
	private int batchSize;

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

	/**
	 * 여러 스레드가 동시에 size()에 접근하면 불필요하게 DB 접근이 많아진다고 판단하여 synchronized 사용
	 * @param folderMetadataList
	 */
	public void addForDeleteFolder(List<FolderMetadata> folderMetadataList) {
		folderDeleteQueue.addAll(folderMetadataList);
	}

	/**
	 * 파일은 원격 파일을 하나씩 삭제하는 과정이 있어서 하나씩 추가
	 * @param fileMetadata
	 */
	public void addForDeleteFile(FileMetadata fileMetadata) {
		fileDeleteQueue.offer(fileMetadata);
	}

	public void addForDeleteFile(List<FileMetadata> fileMetadataList) {
		fileDeleteQueue.addAll(fileMetadataList);
	}

	public <T> void addForUpdateFile(T changeValue, List<Long> pkList, BiConsumer<T, List<Long>> consumer) {
		doBatchJob(changeValue, pkList, consumer);
	}

	private void folderBatchDelete() {
		this.<FolderMetadata>doBatchJob(folderDeleteQueue,
			folderList -> folderList.stream().map(FolderMetadata::getId).toList(),
			batchList -> folderMetadataJpaRepository.softDeleteAllByIdInBatch(batchList));
	}

	private void fileBatchDelete() {
		this.<FileMetadata>doBatchJob(fileDeleteQueue, fileList -> fileList.stream().map(FileMetadata::getId).toList(),
			batchList -> fileMetadataJpaRepository.deleteAllByIdInBatch(batchList));
	}

	/**
	 * 큐에 있는 데이터를 BATCH_SIZE만큼 추출하여 PK 리스트로 변환 후, 해당 데이터를 바탕으로 배치 작업을 수행
	 * @param queue
	 * @param function
	 * @param consumer
	 * @param <T>
	 */
	private <T> void doBatchJob(BlockingQueue<T> queue, Function<List<T>, List<Long>> function,
		Consumer<List<Long>> consumer) {
		List<T> metadataList = new ArrayList<>();
		queue.drainTo(metadataList, batchSize);
		List<Long> batchList = function.apply(metadataList);
		consumer.accept(batchList);
	}

	private <T> void doBatchJob(T changeValue, List<Long> pkList, BiConsumer<T, List<Long>> consumer) {
		consumer.accept(changeValue, pkList);
	}

	/**
	 * 큐잉된 파일 및 폴더를 배치 쿼리로 삭제하는 스케줄러
	 * 멀티스레드 환경에서 큐에 작업을 추가할 때마다 확인을 하는 것 보다 단일 스레드로 처리하는 것이 더 효율적이어서 스케줄러로 처리
	 */
	@Scheduled(fixedDelay = DELETE_DELAY)
	private void deleteScheduling() {
		folderCount++;
		fileCount++;
		if (folderDeleteQueue.size() >= batchSize) {
			folderCount = 0;
			do {
				deleteThreadPoolExecutor.execute(() -> folderBatchDelete());
			} while (folderDeleteQueue.size() >= batchSize);
		} else if (folderCount >= maxCount) {
			folderCount = 0;
			folderBatchDelete();
		}

		if (fileDeleteQueue.size() >= batchSize) {
			fileCount = 0;
			do {
				deleteThreadPoolExecutor.execute(() -> fileBatchDelete());
			} while (fileDeleteQueue.size() >= batchSize);
		} else if (fileCount >= maxCount) {
			fileCount = 0;
			fileBatchDelete();
		}
	}

}
