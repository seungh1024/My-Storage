package com.woowacamp.storage.domain.file.service;

import static com.woowacamp.storage.global.constant.CommonConstant.*;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;
import com.woowacamp.storage.domain.file.dto.PartContext;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.file.util.CustomS3BlockingQueuePolicy;
import com.woowacamp.storage.global.error.ErrorCode;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class FileWriterThreadPool {
	@Value("${cloud.aws.credentials.bucketName}")
	private String BUCKET_NAME;

	private final Map<String, Integer> maxPartCountMap;
	private final Map<String, AtomicInteger> currentPartCountMap;
	private final AmazonS3 amazonS3;
	private final ThreadPoolExecutor executorService;
	private final FileMetadataJpaRepository fileMetadataJpaRepository;
	public final ArrayBlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>(FILE_WRITER_QUEUE_SIZE);

	public FileWriterThreadPool(AmazonS3 amazonS3, FileMetadataJpaRepository fileMetadataJpaRepository) {
		this.amazonS3 = amazonS3;
		this.maxPartCountMap = new HashMap<>();
		this.currentPartCountMap = new HashMap<>();
		this.executorService = new ThreadPoolExecutor(
			FILE_WRITER_CORE_POOL_SIZE,
			FILE_WRITER_MAXIMUM_POOL_SIZE,
			FILE_WRITER_KEEP_ALIVE_TIME,
			TimeUnit.SECONDS,
			workQueue,
			new CustomS3BlockingQueuePolicy()
		);
		// this.executorService.prestartAllCoreThreads();
		this.fileMetadataJpaRepository = fileMetadataJpaRepository;
		log.info("file writer queue size: {}", FILE_WRITER_QUEUE_SIZE);
		// log.info("initial thread count: {}", ((ThreadPoolExecutor)executorService).getActiveCount());
		// log.info("initial queue size: {}", ((ThreadPoolExecutor)executorService).getQueue().size());
	}

	public void produce(InitiateMultipartUploadResult initResponse, String currentFileName, int partNumber,
		byte[] contentBuffer, int bufferLength, List<PartETag> partETags) {

		if (!currentPartCountMap.containsKey(currentFileName)) {
			log.info("[Error Occurred] 이미 중단된 작업입니다. partNumber: {} ", partNumber);
			fileMetadataJpaRepository.deleteByUuidFileName(currentFileName);
			throw ErrorCode.FILE_UPLOAD_FAILED.baseException();
		}
		// log.info("current thread count: {}", ((ThreadPoolExecutor)executorService).getActiveCount());
		// log.info("current queue size: {}", ((ThreadPoolExecutor)executorService).getQueue().size());
		executorService.execute(() -> {
			log.info("current file: {}, currentThread: {}, partNumber: {}", currentFileName,
				Thread.currentThread().getId(), partNumber);
			log.info("current thread count: {}", ((ThreadPoolExecutor)executorService).getActiveCount());
			log.info("current queue size: {}", workQueue.size());
			long start = System.currentTimeMillis();
			uploadPart(initResponse.getUploadId(), currentFileName, partNumber, contentBuffer, bufferLength, partETags);
			AtomicInteger currentConsumeCount = currentPartCountMap.get(currentFileName);
			if (currentConsumeCount != null) {
				currentConsumeCount.incrementAndGet();
			}
			Integer maxConsumeCount = maxPartCountMap.get(currentFileName);
			if (maxConsumeCount != null
				&& currentConsumeCount.get() >= maxConsumeCount) {
				completeFileUpload(initResponse.getUploadId(), currentFileName, partETags);
			}
			log.info("uploadPart execute time: {}", System.currentTimeMillis() - start);
		});
	}

	public void finishFileUpload(PartContext partContext) {
		maxPartCountMap.put(partContext.getUploadFileName(), partContext.getPartCount());
	}

	private void uploadPart(String uploadId, String key, int partNumber, byte[] data, int length,
		List<PartETag> partETags) {
		UploadPartRequest uploadRequest = new UploadPartRequest()
			.withBucketName(BUCKET_NAME)
			.withKey(key)
			.withUploadId(uploadId)
			.withPartNumber(partNumber)
			.withInputStream(new ByteArrayInputStream(data, 0, length))
			.withPartSize(length);
		UploadPartResult uploadResult;
		try {
			uploadResult = amazonS3.uploadPart(uploadRequest);
		} catch (AmazonClientException e) {
			log.error("partNumber: {}, part upload가 정상적으로 동작하지 않습니다.", partNumber);
			currentPartCountMap.remove(key);
			fileMetadataJpaRepository.deleteByUuidFileName(key);
			return;
		}
		partETags.add(uploadResult.getPartETag());
	}

	private void completeFileUpload(String uploadId, String currentFileName, List<PartETag> partETags) {
		log.info("currentThread: {}, finish upload", Thread.currentThread().getId());
		maxPartCountMap.remove(currentFileName);
		currentPartCountMap.remove(currentFileName);
		CompleteMultipartUploadRequest completeRequest = new CompleteMultipartUploadRequest(BUCKET_NAME,
			currentFileName, uploadId, partETags);
		try {
			amazonS3.completeMultipartUpload(completeRequest);
		} catch (AmazonClientException e) {
			log.error("[Error Occurred] completeFileUpload가 정상적으로 동작하지 않습니다.");
			fileMetadataJpaRepository.updateUploadStatusByUuid(currentFileName);
		}
	}

	public void initializePartCount(String fileName) {
		currentPartCountMap.put(fileName, new AtomicInteger(0));
	}
}
