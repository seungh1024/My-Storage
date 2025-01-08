package com.woowacamp.storage.domain.file.dto.request;

import com.woowacamp.storage.global.annotation.CheckField;
import com.woowacamp.storage.global.aop.type.FieldType;

public record FileUploadRequestDto(
	@CheckField(FieldType.USER_ID) long userId,
	@CheckField(FieldType.FOLDER_ID) long parentFolderId,
	long fileSize,
	long creatorId,
	String fileName,
	String fileExtension
) {
}
