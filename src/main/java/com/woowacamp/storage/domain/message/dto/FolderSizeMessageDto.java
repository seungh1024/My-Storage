package com.woowacamp.storage.domain.message.dto;

import com.woowacamp.storage.domain.message.util.EventType;

public record FolderSizeMessageDto(
	Long id,
	Long folderMetadataId,
	long size,
	EventType eventType
) implements OutboxMessage {

}
