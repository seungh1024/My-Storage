package com.woowacamp.storage.domain.file.service;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class SyncFileService {
	@Value("${cloud.aws.credentials.bucketName}")
	private String BUCKET_NAME;

	private final Map<String, Integer> maxPartCountMap;
	private final AmazonS3 amazonS3;
	private final FileMetadataJpaRepository fileMetadataJpaRepository;

	public SyncFileService(AmazonS3 amazonS3, FileMetadataJpaRepository fileMetadataJpaRepository) {
		this.amazonS3 = amazonS3;
		this.maxPartCountMap = new HashMap<>();
		this.fileMetadataJpaRepository = fileMetadataJpaRepository;
	}

	public void produce(InitiateMultipartUploadResult initResponse, String currentFileName, int partNumber,
		byte[] contentBuffer, int bufferLength, List<PartETag> partETags) {

		log.info("partNumber: {}", partNumber);
		uploadPart(initResponse.getUploadId(), currentFileName, partNumber, contentBuffer, bufferLength, partETags);

	}

	public void finishFileUpload(InitiateMultipartUploadResult initResponse, String currentFileName,
		List<PartETag> partETags) {
		completeFileUpload(initResponse.getUploadId(), currentFileName, partETags);
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
			fileMetadataJpaRepository.deleteByUuidFileName(key);
			return;
		}
		partETags.add(uploadResult.getPartETag());
	}

	private void completeFileUpload(String uploadId, String currentFileName, List<PartETag> partETags) {
		CompleteMultipartUploadRequest completeRequest = new CompleteMultipartUploadRequest(BUCKET_NAME,
			currentFileName, uploadId, partETags);
		try {
			amazonS3.completeMultipartUpload(completeRequest);
		} catch (AmazonClientException e) {
			log.error("[Error Occurred] completeFileUpload가 정상적으로 동작하지 않습니다.");
			fileMetadataJpaRepository.updateUploadStatusByUuid(currentFileName);
		}
	}
}
