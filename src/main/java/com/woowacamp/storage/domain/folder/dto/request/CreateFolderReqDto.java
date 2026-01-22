package com.woowacamp.storage.domain.folder.dto.request;

import com.woowacamp.storage.global.annotation.CheckField;
import com.woowacamp.storage.global.aop.type.FieldType;

import jakarta.validation.constraints.Size;

public record CreateFolderReqDto(
	long userId,
	long parentFolderId,
	@Size(min = 1, max = 100) String uploadFolderName,
	long creatorId
) {
}
