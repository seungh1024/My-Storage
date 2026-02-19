package com.woowacamp.storage.domain.dummy;

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

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DummyController.class)
@Import(JpaTestConfig.class)
class DummyControllerWebMvcTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockBean
	private DummyService dummyService;

	@Test
	@DisplayName("POST /api/v1/dummy/user -> 202 Accepted")
	void createUserDummy_returnsAccepted() throws Exception {
		DummyRequestUserDto dto = new DummyRequestUserDto(1L, 2L);

		mockMvc.perform(post("/api/v1/dummy/user")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(dto)))
			.andExpect(status().isAccepted());

		then(dummyService).should().createUserDummy(dto);
	}

	@Test
	@DisplayName("POST /api/v1/dummy/folder -> 202 Accepted")
	void createFolderDummy_returnsAccepted() throws Exception {
		DummyRequestFolderDto dto = new DummyRequestFolderDto(5L);

		mockMvc.perform(post("/api/v1/dummy/folder")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(dto)))
			.andExpect(status().isAccepted());

		then(dummyService).should().createFolderDummy(5L);
	}

	@Test
	@DisplayName("PATCH /api/v1/dummy/folder/path/length -> 202 Accepted")
	void updateFolderPathLength_returnsAccepted() throws Exception {
		DummyRequestFolderDto dto = new DummyRequestFolderDto(7L);

		mockMvc.perform(patch("/api/v1/dummy/folder/path/length")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(dto)))
			.andExpect(status().isAccepted());

		then(dummyService).should().updateFolderPathLength(dto);
	}
}
