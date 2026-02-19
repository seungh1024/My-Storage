package com.woowacamp.storage.domain.folder.dto.command;

public record MoveLockContext(
	Long sourceFolderId,
	Long targetFolderId,
	Long rootId,
	String folderName
) {
}
