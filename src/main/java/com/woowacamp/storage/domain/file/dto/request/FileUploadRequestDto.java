package com.woowacamp.storage.domain.file.dto.request;

public record FileUploadRequestDto(
	long userId,
	long parentFolderId,
	long fileSize,
	long creatorId,
	long rootId,
	String fileName,
	String fileExtension
) {
}
