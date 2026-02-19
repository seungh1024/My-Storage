package com.woowacamp.storage.domain.folder.dto.request;

import jakarta.validation.constraints.Size;

public record CreateFolderReqDto(
	long userId,
	long rootId,
	long parentFolderId,
	@Size(min = 1, max = 100) String uploadFolderName,
	long creatorId
) {
}
