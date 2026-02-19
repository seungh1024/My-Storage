package com.woowacamp.storage.domain.message.dto;

import com.woowacamp.storage.domain.message.util.EventType;

public record FolderMoveMessageDto(
	Long id,
	Long parentFolderMetadataId,
	EventType eventType
)
	implements OutboxMessage {
}
