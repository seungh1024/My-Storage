package com.woowacamp.storage.domain.folder.dto.command;

public record FolderJobInsertCommand(
	Long rootId,
	Long folderId,
	Long currentParentId,
	Long lastFolderId,
	Long lastFileId,
	String parentStack,
	String status,
	int retryCount
) {
}
