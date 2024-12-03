package com.woowacamp.storage.domain.file.controller;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import org.apache.catalina.connector.ClientAbortException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.woowacamp.storage.domain.file.dto.FileDataDto;
import com.woowacamp.storage.domain.file.dto.FileMetadataDto;
import com.woowacamp.storage.domain.file.dto.FormMetadataDto;
import com.woowacamp.storage.domain.file.dto.PartContext;
import com.woowacamp.storage.domain.file.dto.UploadContext;
import com.woowacamp.storage.domain.file.dto.UploadState;
import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.file.service.FileService;
import com.woowacamp.storage.domain.file.service.FileWriterThreadPool;
import com.woowacamp.storage.domain.file.service.S3FileService;
import com.woowacamp.storage.domain.file.service.ThumbnailWriterThreadPool;
import com.woowacamp.storage.global.annotation.CheckField;
import com.woowacamp.storage.global.annotation.RequestType;
import com.woowacamp.storage.global.aop.PermissionFieldsDto;
import com.woowacamp.storage.global.aop.PermissionHandler;
import com.woowacamp.storage.global.aop.type.FieldType;
import com.woowacamp.storage.global.aop.type.FileType;
import com.woowacamp.storage.global.constant.PermissionType;
import com.woowacamp.storage.global.error.CustomException;
import com.woowacamp.storage.global.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/files")
@Slf4j
@Validated
public class MultipartFileController {

	public static final int INITIAL_CAPACITY = 5 * 1024 * 1024 + 1024 * 600;
	private final AmazonS3 amazonS3;
	private final S3FileService s3FileService;
	private final FileWriterThreadPool fileWriterThreadPool;
	private final FileMetadataJpaRepository fileMetadataJpaRepository;
	private final FileService fileService;
	private final ThumbnailWriterThreadPool thumbnailWriterThreadPool;
	private final PermissionHandler permissionHandler;

	@Value("${cloud.aws.credentials.bucketName}")
	private String bucketName;
	@Value("${file.reader.bufferSize}")
	private int bufferSize;
	@Value("${file.reader.lineBufferMaxSize}")
	private int lineBufferMaxSize;
	@Value("${file.reader.chunkSize}")
	private int s3ChunkSize;

	/**
	 * MultipartFile은 임시 저장을 해서 직접 request를 통해 multipart/form-data를 파싱했습니다.
	 * 파싱을 하고 S3에 이미지를 업로드합니다.
	 */
	@ResponseStatus(HttpStatus.CREATED)
	@PostMapping
	public void handleFileUpload(HttpServletRequest request) throws Exception {
		Runtime runtime = Runtime.getRuntime();
		long totalMemory = runtime.totalMemory();
		long freeMemory = runtime.freeMemory();
		long maxMemory = runtime.maxMemory();
		long usedMemory = totalMemory - freeMemory;
		log.info("new request, used mem: {}, free mem: {}, total mem: {}, max mem: {}", usedMemory, freeMemory,
			totalMemory, maxMemory);
		log.info("Active thread count: {}", ManagementFactory.getThreadMXBean().getThreadCount());
		String boundary = "--" + extractBoundary(request.getContentType());
		String finalBoundary = boundary + "--";

		try (InputStream inputStream = request.getInputStream()) {
			UploadContext context = new UploadContext(boundary, finalBoundary, new HashMap<>(), false);
			processMultipartData(inputStream, context);
		}
	}

	/**
	 * 클라이언트 요청을 buffer 단위로 읽어서 processBuffer 메소드를 호출합니다.
	 * processBuffer 메소드에서 헤더 파싱을 하고 boundary 체크를 하여 각 파트를 구분합니다.
	 */
	private void processMultipartData(InputStream inputStream, UploadContext context) throws Exception {
		byte[] buffer = new byte[bufferSize];
		ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(lineBufferMaxSize);
		ByteArrayOutputStream contentBuffer = new ByteArrayOutputStream(INITIAL_CAPACITY);
		PartContext partContext = new PartContext();
		UploadState state = new UploadState();

		try {
			int bytesRead;
			while ((bytesRead = inputStream.read(buffer)) != -1) {
				if (processBuffer(buffer, bytesRead, lineBuffer, contentBuffer, context, partContext, state)) {
					break;
				}
			}
		} catch (ClientAbortException e) {
			log.error("[ClientAbortException] 입력 처리 중 예외 발생. ERROR MESSAGE = {}", e.getMessage());
			if (context.getFileMetadata() == null) {
				throw ErrorCode.INVALID_MULTIPART_FORM_DATA.baseException();
			}
			fileMetadataJpaRepository.updateUploadStatusById(context.getFileMetadata().metadataId());
		} catch (AmazonS3Exception e) {
			log.error("[AmazonS3Exception] 입력 예외로 완성되지 않은 S3 파일 제거 중 예외 발생. ERROR MESSAGE = {}", e.getMessage());
		} catch (CustomException e) {
			throw e;
		} catch (Exception e) {
			log.error("[Exception] 예상치 못한 예외가 발생했습니다: {}, {}", e.getCause(), e.getMessage());
			throw e;
		}
	}

	/**
	 * line 단위로 buffer를 읽어들입니다.
	 * boundary 기준으로 헤더를 먼저 읽고 나머지는 processContent로 content 처리를 합니다.
	 * 헤더 데이터를 읽은 후, 1차로 사용자 정보, 파일 이름 등의 메타데이터를 저장합니다.
	 *
	 * @return - true인 경우 API 명세에 따라 추가 데이터는 읽지 않는다.
	 */
	private boolean processBuffer(byte[] buffer, int bytesRead, ByteArrayOutputStream lineBuffer,
		ByteArrayOutputStream contentBuffer, UploadContext context, PartContext partContext, UploadState state) throws
		Exception {
		for (int i = 0; i < bytesRead; i++) {
			byte b = buffer[i];
			lineBuffer.write(b);

			if (b != '\n') {
				continue;
			}
			String line = lineBuffer.toString().trim();

			if (line.equals(context.getBoundary()) || line.equals(context.getFinalBoundary())) {
				// boundary 한 줄을 읽은 경우
				if (context.isFileRead()) {
					// final boundary
					// 메타데이터 쓰기에 성공을 해야 S3에 파일 업로드를 요청한다
					s3FileService.finalizeMetadata(context.getFileMetadata(),
						state.getFileSize() + contentBuffer.size() - 2);
					processEndOfPart(contentBuffer, context, partContext, state);
					return true;
				}
				processEndOfPart(contentBuffer, context, partContext, state);
				resetState(partContext, state);
				// boundary가 끝난 뒤에 데이터가 남아 있으면 항상 헤더부터 시작
				partContext.setInHeader(true);
			} else if (partContext.isInHeader()) {
				// boundary 읽은 이후
				processHeader(line, partContext);
				if (!partContext.isInHeader() && partContext.getCurrentFileName() != null) {
					FormMetadataDto formMetadataDto = FormMetadataDto.of(context.getFormFields());
					// PermissionHandler로 접근 권한을 확인한다.
					PermissionFieldsDto permissionFieldsDto = new PermissionFieldsDto();
					long userId = formMetadataDto.getUserId();
					long parentFolderId = formMetadataDto.getParentFolderId();
					permissionFieldsDto.setUserId(userId);
					permissionFieldsDto.setFolderId(parentFolderId);
					// 파일 쓰기는 현재 파일이 존재하지 않으므로 폴더에 대한 권한을 검증하고 통과하면 ownerId를 받아온다.
					long ownerId = permissionHandler.getOwnerIdAndCheckPermission(PermissionType.WRITE, FileType.FOLDER,
						permissionFieldsDto);
					formMetadataDto.setUserId(ownerId);
					formMetadataDto.setCreatorId(userId);

					FileMetadataDto fileMetadataDto = s3FileService.createInitialMetadata(formMetadataDto, partContext);
					if (partContext.getCurrentContentType().startsWith("image/")) {
						String imageFormat = partContext.getCurrentContentType().substring(6);
						context.updateImageFormat(imageFormat);
					}
					context.updateFileMetadata(fileMetadataDto);
					context.updateIsFileRead();
					partContext.setUploadFileName(fileMetadataDto.uuid());
					InitiateMultipartUploadResult initiateMultipartUploadResult = initializeFileUpload(
						partContext.getUploadFileName(), partContext.getCurrentContentType());
					state.setInitResponse(initiateMultipartUploadResult);
					state.initPartEtag(partContext.getUploadFileName());
					state.setFileMetadataDto(fileMetadataDto);
				}
			} else {
				if (context.getImageFormat() != null && !context.isAbortedCreateThumbnail()) {
					context.getPos().write(lineBuffer.toByteArray());
					if (!context.isStartedCreatedThumbnail()) {
						context.updateStartedCreatedThumbnail();
						thumbnailWriterThreadPool.createThumbnail(context);
					}
				}
				processContent(contentBuffer, lineBuffer, partContext, state);
			}
			lineBuffer.reset();
		}

		checkLineBufferSize(lineBuffer, contentBuffer, partContext, state);
		return false;
	}

	/**
	 * file인 경우, 남은 buffer의 내용을 upload 하고 field인 경우, response에 추가합니다.
	 */
	private void processEndOfPart(ByteArrayOutputStream contentBuffer, UploadContext context, PartContext partContext,
		UploadState state) {
		if (partContext.isInHeader() || partContext.getCurrentFieldName() == null) {
			return;
		}
		if (partContext.getCurrentFileName() != null) {
			finishFileUpload(contentBuffer, state, partContext);
			s3FileService.checkMetadata(state);
		} else {
			context.getFormFields().put(partContext.getCurrentFieldName(), contentBuffer.toString().trim());
		}
		contentBuffer.reset();
	}

	/**
	 * 다음 part에 대한 요청을 읽어들이기 위해 context, state 초기화
	 */
	private void resetState(PartContext partContext, UploadState state) {
		partContext.reset();
		state.reset();
	}

	/**
	 * 헤더 데이터를 파싱합니다.
	 */
	private void processHeader(String line, PartContext partContext) {
		if (line.isEmpty()) {
			partContext.setCurrentFieldName(extractFieldName(partContext.getHeaders().get("Content-Disposition")));
			partContext.setCurrentFileName(extractFileName(partContext.getHeaders().get("Content-Disposition")));
			partContext.setCurrentContentType(partContext.getHeaders().get("Content-Type"));
			partContext.setInHeader(false);
		} else {
			int colonIndex = line.indexOf(':');
			if (colonIndex > 0) {
				String headerName = line.substring(0, colonIndex).trim();
				String headerValue = line.substring(colonIndex + 1).trim();
				partContext.getHeaders().put(headerName, headerValue);
			}
		}
	}

	/**
	 * header를 읽은 후, file type이라면 S3에 part upload를 알려주는 initiate request 입니다.
	 */
	private InitiateMultipartUploadResult initializeFileUpload(String fileName, String contentType) {
		fileWriterThreadPool.initializePartCount(fileName);
		ObjectMetadata metadata = new ObjectMetadata();
		metadata.setContentType(contentType);
		InitiateMultipartUploadRequest initRequest = new InitiateMultipartUploadRequest(bucketName,
			fileName).withObjectMetadata(metadata);

		return amazonS3.initiateMultipartUpload(initRequest);
	}

	/**
	 * contentBuffer가 5MB 이상 찼을 경우, part upload를 합니다.
	 */
	private void processContent(ByteArrayOutputStream contentBuffer, ByteArrayOutputStream lineBuffer,
		PartContext partContext, UploadState state) throws Exception {
		contentBuffer.write(lineBuffer.toByteArray());
		if (partContext.getCurrentFileName() != null && contentBuffer.size() >= s3ChunkSize) {
			uploadChunk(contentBuffer, state, partContext);
		}
	}

	/**
	 * 실제로 파일 바이너리 데이터 쓰기 작업을 요청하는 메소드입니다.
	 * 별도의 쓰기 작업 스레드 풀에 요청을 넘겨줍니다.
	 */
	private void uploadChunk(ByteArrayOutputStream contentBuffer, UploadState state, PartContext partContext) {
		partContext.plusPartCount();
		state.addPartNumber();
		state.addFileSize(contentBuffer.size());
		fileWriterThreadPool.produce(state.getInitResponse(), partContext.getUploadFileName(), state.getPartNumber(),
			contentBuffer.toByteArray(), contentBuffer.size(),
			state.getPartETagsMap().get(partContext.getUploadFileName()));
		contentBuffer.reset();
	}

	/**
	 * OOM 방지를 위해 lineBuffer가 일정 수준 이상에 도달하면 contentBuffer로 flush 합니다.
	 * contentBuffer가 5MB 이상 차면, s3로 part upload를 합니다.
	 */
	private void checkLineBufferSize(ByteArrayOutputStream lineBuffer, ByteArrayOutputStream contentBuffer,
		PartContext partContext, UploadState state) throws Exception {
		if (lineBuffer.size() >= lineBufferMaxSize) {
			contentBuffer.write(lineBuffer.toByteArray());
			lineBuffer.reset();

			if (contentBuffer.size() >= s3ChunkSize) {
				uploadChunk(contentBuffer, state, partContext);
			}
		}
	}

	/**
	 * part upload가 모두 끝난 이후, 최종 finish upload 요청을 합니다.
	 * 버퍼에 보내지 못한 데이터가 존재할 수 있으니 확인을 하고 쓰기 작업 스레드 풀에 작업이 끝났음을 알립니다.
	 */
	private void finishFileUpload(ByteArrayOutputStream contentBuffer, UploadState state, PartContext partContext) {
		uploadLeftOver(contentBuffer, state, partContext);
		fileWriterThreadPool.finishFileUpload(partContext);
	}

	/**
	 * 마지막 part의 contentBuffer가 남아있는 경우, 5MB 이하의 파일을 최종적으로 쓰기 작업 스레드 풀에 요청합니다.
	 */
	private void uploadLeftOver(ByteArrayOutputStream contentBuffer, UploadState state, PartContext partContext) {
		if (contentBuffer.size() == 0) {
			return;
		}
		partContext.plusPartCount();
		state.addPartNumber();
		state.addFileSize(contentBuffer.size() - 2);
		log.info("[Last Upload Ended] file total size = {}", state.getFileSize());
		fileWriterThreadPool.produce(state.getInitResponse(), partContext.getUploadFileName(), state.getPartNumber(),
			contentBuffer.toByteArray(), contentBuffer.size() - 2,
			state.getPartETagsMap().get(partContext.getUploadFileName()));
	}

	/**
	 * boundary를 추출합니다.
	 */
	private String extractBoundary(String contentType) {
		for (String part : contentType.split(";")) {
			if (part.trim().startsWith("boundary=")) {
				return part.split("=", 2)[1].trim().replaceAll("^\"|\"$", "");
			}
		}
		return null;
	}

	private String extractFieldName(String contentDisposition) {
		return extractAttribute(contentDisposition, "name");
	}

	private String extractFileName(String contentDisposition) {
		return extractAttribute(contentDisposition, "filename");
	}

	private String extractAttribute(String source, String attribute) {
		for (String part : source.split(";")) {
			if (part.trim().startsWith(attribute + "=")) {
				return part.split("=", 2)[1].trim().replaceAll("^\"|\"$", "");
			}
		}
		return null;
	}

	@RequestType(permission = PermissionType.READ, fileType = FileType.FILE)
	@GetMapping("/download/{fileId}")
	@Validated
	ResponseEntity<InputStreamResource> download(@CheckField(FieldType.FILE_ID) @PathVariable Long fileId,
		@CheckField(FieldType.USER_ID) @Positive(message = "올바른 입력값이 아닙니다.") @RequestParam("userId") Long userId,
		@RequestParam("isThumbnail") boolean isThumbnail) {

		FileMetadata fileMetadata = fileService.getFileMetadataBy(fileId, userId);
		FileDataDto fileDataDto;
		if (isThumbnail) {
			fileDataDto = s3FileService.downloadByS3(fileId, bucketName, fileMetadata.getThumbnailUUID());
		} else {
			fileDataDto = s3FileService.downloadByS3(fileId, bucketName, fileMetadata.getUuidFileName());
		}
		HttpHeaders headers = new HttpHeaders();
		// HTTP 응답 헤더에 Content-Type 설정
		String fileType = fileMetadata.getFileType();
		if (fileType == null) {
			fileType = "application/octet-stream";
		}
		String fileName = fileDataDto.fileMetadataDto().uploadFileName();
		String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replaceAll("\\+", "%20");

		headers.add(HttpHeaders.CONTENT_TYPE, fileType);
		headers.add(HttpHeaders.CONTENT_DISPOSITION,
			"attachment; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName);

		return ResponseEntity.ok()
			.headers(headers)
			.contentType(MediaType.APPLICATION_OCTET_STREAM)
			.body(new InputStreamResource(fileDataDto.fileInputStream()));
	}

}
