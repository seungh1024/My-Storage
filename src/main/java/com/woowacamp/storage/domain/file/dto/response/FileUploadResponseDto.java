package com.woowacamp.storage.domain.file.dto.response;

import java.net.URL;

public record FileUploadResponseDto(
	String objectKey,
	URL presignedUrl
) {
}
