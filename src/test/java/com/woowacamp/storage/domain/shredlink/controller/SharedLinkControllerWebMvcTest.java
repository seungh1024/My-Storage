package com.woowacamp.storage.domain.shredlink.controller;

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
import com.woowacamp.storage.domain.shredlink.dto.request.CancelSharedLinkRequestDto;
import com.woowacamp.storage.domain.shredlink.dto.request.MakeSharedLinkRequestDto;
import com.woowacamp.storage.domain.shredlink.dto.response.SharedLinkResponseDto;
import com.woowacamp.storage.domain.shredlink.service.SharedLinkService;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SharedLinkController.class)
@Import(JpaTestConfig.class)
class SharedLinkControllerWebMvcTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockBean
	private SharedLinkService sharedLinkService;

	@Test
	@DisplayName("POST /api/v1/share -> 201 Created")
	void createSharedLink_returnsCreated() throws Exception {
		MakeSharedLinkRequestDto request = new MakeSharedLinkRequestDto(1L, true, 99L, "Read");
		SharedLinkResponseDto response = new SharedLinkResponseDto("/share/abc");

		given(sharedLinkService.createShareLink(any(MakeSharedLinkRequestDto.class))).willReturn(response);

		mockMvc.perform(post("/api/v1/share")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.link").value("/share/abc"));

		then(sharedLinkService).should().createShareLink(any(MakeSharedLinkRequestDto.class));
	}

	@Test
	@DisplayName("GET /api/v1/share -> 302 Found")
	void redirect_returnsFound() throws Exception {
		given(sharedLinkService.getRedirectUrl("shared-id")).willReturn("https://example.com/resource");

		mockMvc.perform(get("/api/v1/share")
				.param("userId", "10")
				.param("sharedId", "shared-id"))
			.andExpect(status().isFound())
			.andExpect(header().string("Location", containsString("https://example.com/resource")));

		then(sharedLinkService).should().getRedirectUrl("shared-id");
	}

	@Test
	@DisplayName("DELETE /api/v1/share -> 200 OK")
	void deleteSharedLink_returnsOk() throws Exception {
		CancelSharedLinkRequestDto request = new CancelSharedLinkRequestDto(1L, false, 55L);

		mockMvc.perform(delete("/api/v1/share")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isOk());

		then(sharedLinkService).should().cancelShare(request);
	}
}
