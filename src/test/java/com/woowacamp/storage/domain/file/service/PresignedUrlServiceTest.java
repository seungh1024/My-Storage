package com.woowacamp.storage.domain.file.service;

import java.net.URL;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class PresignedUrlServiceTest {

	@InjectMocks
	private PresignedUrlService presignedUrlService;

	@Mock
	private S3Client s3Client;
	@Mock
	private S3Presigner s3Presigner;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(presignedUrlService, "bucketName", "test-bucket");
		ReflectionTestUtils.setField(presignedUrlService, "duration", 7); // minutes
	}

	@Nested
	@DisplayName("getPresignedUrl")
	class GetPresignedUrlTests {

		@Test
		@DisplayName("성공: presignPutObject 호출 + bucket/key/signatureDuration 검증 + url 반환")
		void success_builds_request_and_returns_url() throws Exception {
			// given
			String objectKey = "obj-key";

			PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
			URL url = new URL("https://example.com/put");
			given(presigned.url()).willReturn(url);

			ArgumentCaptor<PutObjectPresignRequest> captor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
			given(s3Presigner.presignPutObject(captor.capture())).willReturn(presigned);

			// when
			URL result = presignedUrlService.getPresignedUrl(objectKey);

			// then
			assertEquals(url, result);

			PutObjectPresignRequest passed = captor.getValue();
			assertEquals(Duration.ofMinutes(7), passed.signatureDuration());
			assertEquals("test-bucket", passed.putObjectRequest().bucket());
			assertEquals(objectKey, passed.putObjectRequest().key());
		}
	}

	@Nested
	@DisplayName("getFileMetadata")
	class GetFileMetadataTests {

		@Test
		@DisplayName("성공: headObject 호출 후 응답 반환")
		void success_calls_head_object() {
			// given
			String objectKey = "obj";
			HeadObjectResponse head = HeadObjectResponse.builder().contentLength(10L).build();
			given(s3Client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
				.willReturn(head);

			// when
			HeadObjectResponse result = presignedUrlService.getFileMetadata(objectKey);

			// then
			assertEquals(head, result);

			ArgumentCaptor<software.amazon.awssdk.services.s3.model.HeadObjectRequest> captor =
				ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class);

			then(s3Client).should(times(1)).headObject(captor.capture());
			assertEquals("test-bucket", captor.getValue().bucket());
			assertEquals(objectKey, captor.getValue().key());
		}
	}

	@Nested
	@DisplayName("getDownloadUrl")
	class GetDownloadUrlTests {

		@Test
		@DisplayName("성공: headObject로 존재 확인 후 presignGetObject 호출 + bucket/key/signatureDuration 검증 + url 반환")
		void success_checks_exists_then_presigns() throws Exception {
			// given
			String objectKey = "obj-key";

			// 존재 확인
			given(s3Client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
				.willReturn(HeadObjectResponse.builder().contentLength(1L).build());

			PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
			URL url = new URL("https://example.com/get");
			given(presigned.url()).willReturn(url);

			ArgumentCaptor<GetObjectPresignRequest> captor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
			given(s3Presigner.presignGetObject(captor.capture())).willReturn(presigned);

			// when
			URL result = presignedUrlService.getDownloadUrl(objectKey);

			// then
			assertEquals(url, result);

			GetObjectPresignRequest passed = captor.getValue();
			assertEquals(Duration.ofMinutes(7), passed.signatureDuration());
			assertEquals("test-bucket", passed.getObjectRequest().bucket());
			assertEquals(objectKey, passed.getObjectRequest().key());

			// 호출 순서: headObject -> presignGetObject
			InOrder inOrder = inOrder(s3Client, s3Presigner);
			inOrder.verify(s3Client).headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class));
			inOrder.verify(s3Presigner).presignGetObject(any(GetObjectPresignRequest.class));
		}
	}

	@Nested
	@DisplayName("deleteFile")
	class DeleteFileTests {

		@Test
		@DisplayName("성공: deleteObject 호출 + bucket/key 검증 + 응답 반환")
		void success_calls_delete_object() {
			// given
			String objectKey = "obj";
			DeleteObjectResponse resp = DeleteObjectResponse.builder().build();
			given(s3Client.deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class)))
				.willReturn(resp);

			// when
			DeleteObjectResponse result = presignedUrlService.deleteFile(objectKey);

			// then
			assertEquals(resp, result);

			ArgumentCaptor<software.amazon.awssdk.services.s3.model.DeleteObjectRequest> captor =
				ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class);

			then(s3Client).should(times(1)).deleteObject(captor.capture());
			assertEquals("test-bucket", captor.getValue().bucket());
			assertEquals(objectKey, captor.getValue().key());
		}
	}
}
