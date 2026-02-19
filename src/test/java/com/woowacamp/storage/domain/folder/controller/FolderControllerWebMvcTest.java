package com.woowacamp.storage.domain.folder.controller;

import java.util.List;

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
import com.woowacamp.storage.domain.folder.dto.response.FolderContentsDto;
import com.woowacamp.storage.domain.folder.dto.request.CreateFolderReqDto;
import com.woowacamp.storage.domain.folder.dto.request.FolderMoveDto;
import com.woowacamp.storage.domain.folder.facade.FolderFacade;
import com.woowacamp.storage.domain.folder.service.FolderService;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FolderController.class)
@Import(JpaTestConfig.class)
class FolderControllerWebMvcTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockBean
	private FolderService folderService;
	@MockBean
	private FolderFacade folderFacade;

	@Test
	@DisplayName("POST /api/v1/folders -> 201 Created")
	void createFolder_returnsCreated() throws Exception {
		CreateFolderReqDto request = new CreateFolderReqDto(1L, 1L, 2L, "new-folder", 1L);
		given(folderFacade.createFolder(request)).willReturn(10L);

		mockMvc.perform(post("/api/v1/folders")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(10L));

		then(folderFacade).should().createFolder(request);
	}

	@Test
	@DisplayName("GET /api/v1/folders/{id} -> 200 OK")
	void getFolderContents_returnsOk() throws Exception {
		FolderContentsDto response = new FolderContentsDto(List.of(), List.of());
		given(folderService.getFolderContents(
				eq(5L), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean()))
			.willReturn(response);

		mockMvc.perform(get("/api/v1/folders/{folderId}", 5L)
				.param("userId", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.folderMetadataList").isArray())
			.andExpect(jsonPath("$.fileMetadataList").isArray());

		then(folderService).should().checkFolderOwnedBy(5L, 1L);
	}

	@Test
	@DisplayName("PATCH /api/v1/folders/{id} -> 200 OK")
	void moveFolder_returnsOk() throws Exception {
		FolderMoveDto request = new FolderMoveDto(1L, 2L, 3L, "folder");

		mockMvc.perform(patch("/api/v1/folders/{folderId}", 9L)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isOk());

		then(folderFacade).should().moveFolder(9L, request);
	}

	@Test
	@DisplayName("DELETE /api/v1/folders/{id} -> 200 OK")
	void deleteFolder_returnsOk() throws Exception {
		mockMvc.perform(delete("/api/v1/folders/{folderId}", 12L)
				.param("userId", "3"))
			.andExpect(status().isOk());

		then(folderService).should().deleteFolder(12L, 3L);
	}
}
