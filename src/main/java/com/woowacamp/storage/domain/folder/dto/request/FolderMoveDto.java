package com.woowacamp.storage.domain.folder.dto.request;

import com.woowacamp.storage.global.annotation.CheckField;
import com.woowacamp.storage.global.aop.type.FieldType;

public record FolderMoveDto(
	long userId,
	long targetFolderId,
	String folderName
) {
}
