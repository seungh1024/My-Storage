package com.woowacamp.storage.domain.folder.dto.command;

import java.time.LocalDateTime;

public record FolderJobInsertCommand(
	Long rootId,
	Long folderId,
	Long currentParentId,
	Long lastFolderId,
	Long lastFileId,
	String parentStack,
	LocalDateTime updatedAt,
	String status,
	int retryCount
) {
}
