package com.woowacamp.storage.domain.total;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;

import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.config.JpaTestConfig;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TotalController.class)
@Import(JpaTestConfig.class)
class TotalControllerWebMvcTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private FileMetadataJpaRepository fileMetadataJpaRepository;

	@MockBean
	private FolderMetadataJpaRepository folderMetadataJpaRepository;

	@Test
	@DisplayName("GET /api/v1/totals/{userId} -> 200 OK")
	void getTotals_returnsOk() throws Exception {
		given(fileMetadataJpaRepository.findByOwnerId(1L)).willReturn(List.of());
		given(folderMetadataJpaRepository.findByOwnerId(1L)).willReturn(List.of());

		mockMvc.perform(get("/api/v1/totals/{userId}", 1L))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.files").isArray())
			.andExpect(jsonPath("$.folders").isArray());

		then(fileMetadataJpaRepository).should().findByOwnerId(1L);
		then(folderMetadataJpaRepository).should().findByOwnerId(1L);
	}
}
