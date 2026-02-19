package com.woowacamp.storage.domain.file.dto.command;

public record FileMoveLockContext(
	Long fileId,
	Long targetFolderId,
	Long rootId,
	String fileName
) {
}
