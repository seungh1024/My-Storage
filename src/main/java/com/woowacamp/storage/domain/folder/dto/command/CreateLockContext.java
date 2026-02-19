package com.woowacamp.storage.domain.folder.dto.command;

public record CreateLockContext(
	Long parentFolderId,
	Long rootId,
	String folderName
) {
}
