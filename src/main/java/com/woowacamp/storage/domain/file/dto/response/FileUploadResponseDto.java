package com.woowacamp.storage.domain.file.dto.response;

import java.net.URL;

public record FileUploadResponseDto(
	long id,
	String objectKey,
	URL presignedUrl
) {
}
