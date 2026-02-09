package com.woowacamp.storage.domain.file.controller;

import java.net.URL;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.woowacamp.storage.config.JpaTestConfig;
import com.woowacamp.storage.domain.file.dto.FileMoveDto;
import com.woowacamp.storage.domain.file.dto.request.FileUploadCompleteRequestDto;
import com.woowacamp.storage.domain.file.dto.request.FileUploadRequestDto;
import com.woowacamp.storage.domain.file.dto.response.FileUploadResponseDto;
import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.service.FileService;
import com.woowacamp.storage.domain.file.service.S3FileService;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FileController.class)
@Import(JpaTestConfig.class)
class FileControllerWebMvcTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockBean
	private FileService fileService;

	@MockBean
	private S3FileService s3FileService;

	@Test
	@DisplayName("PATCH /api/v1/files/{id} -> 200 OK")
	void moveFile_returnsOk() throws Exception {
		FileMoveDto dto = new FileMoveDto(2L, 1L, 10L, "file.txt");

		mockMvc.perform(patch("/api/v1/files/{fileId}", 5L)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(dto)))
			.andExpect(status().isOk());

		then(fileService).should().getFileMetadataBy(5L, dto.userId());
		then(fileService).should().moveFile(5L, dto);
	}

	@Test
	@DisplayName("DELETE /api/v1/files/{id} -> 200 OK")
	void deleteFile_returnsOk() throws Exception {
		mockMvc.perform(delete("/api/v1/files/{fileId}", 7L)
				.param("userId", "3"))
			.andExpect(status().isOk());

		then(fileService).should().deleteFile(7L, 3L);
	}

	@Test
	@DisplayName("POST /api/v1/files -> 201 Created")
	void createFile_returnsCreated() throws Exception {
		FileUploadRequestDto request = new FileUploadRequestDto(1L, 2L, 100L, 1L, 1L, "doc", "txt");
		FileUploadResponseDto response = new FileUploadResponseDto(10L, "object-key", new URL("https://example.com/upload"));

		given(s3FileService.createFileMetadata(request)).willReturn(response);

		mockMvc.perform(post("/api/v1/files")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(10L))
			.andExpect(jsonPath("$.objectKey").value("object-key"))
			.andExpect(jsonPath("$.presignedUrl").value("https://example.com/upload"));

		then(s3FileService).should().createFileMetadata(request);
	}

	@Test
	@DisplayName("PATCH /api/v1/files/complete/{id} -> 200 OK")
	void createComplete_returnsOk() throws Exception {
		FileUploadCompleteRequestDto request = new FileUploadCompleteRequestDto(9L, "object-key");

		mockMvc.perform(patch("/api/v1/files/complete/{fileId}", 4L)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isOk());

		then(s3FileService).should().createComplete(4L, 9L, "object-key");
	}

	@Test
	@DisplayName("GET /api/v1/files/{id} -> 200 OK")
	void getFileUrl_returnsOk() throws Exception {
		URL url = new URL("https://example.com/download");

		given(fileService.getFileMetadataBy(6L, 2L)).willReturn(mock(FileMetadata.class));
		given(s3FileService.getFileUrl(6L)).willReturn(url);

		mockMvc.perform(get("/api/v1/files/{fileId}", 6L)
				.param("userId", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").value("https://example.com/download"));

		then(fileService).should().getFileMetadataBy(6L, 2L);
		then(s3FileService).should().getFileUrl(6L);
	}
}
