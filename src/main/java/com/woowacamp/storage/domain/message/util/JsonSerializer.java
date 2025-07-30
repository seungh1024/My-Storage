package com.woowacamp.storage.domain.message.util;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class JsonSerializer {

	private final ObjectMapper objectMapper;

	public <T> String serialize(T event) {
		try {
			return objectMapper.writeValueAsString(event);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

	public <T> T deserialize(String data, Class<T> type) {
		try {
			return objectMapper.readValue(data, type);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}
}
