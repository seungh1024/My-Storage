package com.woowacamp.storage.domain.folder.dto.message;

import java.util.UUID;

import com.woowacamp.storage.domain.message.util.EventType;

public record FolderSizeMessageDto(
	UUID uuid,
	long folderMetadataId,
	long size,
	EventType eventType
) {
}
