package com.woowacamp.storage.domain.user.controller;

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
import com.woowacamp.storage.domain.user.dto.UserDto;
import com.woowacamp.storage.domain.user.dto.request.CreateUserReqDto;
import com.woowacamp.storage.domain.user.service.UserService;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
@Import(JpaTestConfig.class)
class UserControllerWebMvcTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockBean
	private UserService userService;

	@Test
	@DisplayName("GET /api/v1/users/{id} -> 200 OK")
	void getUser_returnsOk() throws Exception {
		UserDto response = new UserDto(1L, 10L, "user1");
		given(userService.findById(1L)).willReturn(response);

		mockMvc.perform(get("/api/v1/users/{userId}", 1L))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(1L))
			.andExpect(jsonPath("$.rootFolderId").value(10L))
			.andExpect(jsonPath("$.userName").value("user1"));

		then(userService).should().findById(1L);
	}

	@Test
	@DisplayName("POST /api/v1/users -> 201 Created")
	void createUser_returnsCreated() throws Exception {
		CreateUserReqDto request = new CreateUserReqDto("tester");
		UserDto response = new UserDto(2L, 20L, "tester");

		given(userService.save(request)).willReturn(response);

		mockMvc.perform(post("/api/v1/users")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(2L))
			.andExpect(jsonPath("$.rootFolderId").value(20L))
			.andExpect(jsonPath("$.userName").value("tester"));

		then(userService).should().save(request);
	}
}
