package com.woowacamp.storage.domain.file.dto.request;

public record FileUploadCompleteRequestDto(
	long userId,
	String objectKey
) {
}
