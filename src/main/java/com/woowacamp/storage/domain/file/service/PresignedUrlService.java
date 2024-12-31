package com.woowacamp.storage.domain.file.service;

import java.net.URL;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
@RequiredArgsConstructor
public class PresignedUrlService {
	private final S3Client s3Client;
	private final S3Presigner s3Presigner;

	@Value("${cloud.aws.credentials.bucketName}")
	private String bucketName;
	@Value("${cloud.aws.credentials.duration}")
	private int duration;

	private PutObjectRequest getPutObjectRequest(String objectKey) {
		return PutObjectRequest.builder()
			.bucket(bucketName)
			.key(objectKey)
			.build();
	}

	/**
	 * presigned url을 생성하는 메서드
	 * @param objectKey
	 * @return
	 */
	public URL getPresignedUrl(String objectKey) {
		// 객체 업로드를 위한 객체 정보 설정
		PutObjectRequest objectRequest = getPutObjectRequest(objectKey);

		// presigned url 설정
		PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
			.signatureDuration(Duration.ofMinutes(duration))
			.putObjectRequest(objectRequest)
			.build();

		// presigned url 생성 및 url 반환
		return s3Presigner.presignPutObject(presignRequest).url();
	}

	private GetObjectRequest getGetObjectRequest(String objectKey) {
		return GetObjectRequest.builder()
			.bucket(bucketName)
			.key(objectKey)
			.build();
	}

	public URL getDownloadUrl(String objectKey) {
		GetObjectRequest objectRequest = getGetObjectRequest(objectKey);

		GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
			.signatureDuration(Duration.ofMinutes(duration))
			.getObjectRequest(objectRequest)
			.build();

		return s3Presigner.presignGetObject(presignRequest).url();
	}

	private HeadObjectRequest getHeadObjectRequest(String objectKey) {
		return HeadObjectRequest.builder()
			.bucket(bucketName)
			.key(objectKey)
			.build();
	}

	public HeadObjectResponse getFileMetadata(String objectKey) {
		HeadObjectRequest headObjectRequest = getHeadObjectRequest(objectKey);

		return s3Client.headObject(headObjectRequest);
	}
}
