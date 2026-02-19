package com.woowacamp.storage.domain.file.dto.command;

public record FileCreateLockContext(
	Long parentFolderId,
	Long rootId,
	String fileName
) {
}
