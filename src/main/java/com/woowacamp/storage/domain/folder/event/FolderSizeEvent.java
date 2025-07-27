package com.woowacamp.storage.domain.folder.event;

import java.util.UUID;

import com.woowacamp.storage.domain.folder.dto.message.FolderSizeMessageDto;
import com.woowacamp.storage.domain.message.entity.MessageInfo;
import com.woowacamp.storage.domain.message.util.EventType;

import lombok.Getter;

@Getter
public class FolderSizeEvent {
	private final UUID uuid;
	private final Long folderMetadataId;
	private final long size;

	public FolderSizeEvent(Long folderMetadataId, long size) {
		this.uuid = UUID.randomUUID();
		this.folderMetadataId = folderMetadataId;
		this.size = size;
	}

	public MessageInfo toEntity(String payload) {
		return new MessageInfo(
			this.uuid,
			this.folderMetadataId,
			"FolderMove",
			EventType.FOLDER_SIZE,
			payload
		);
	}

	public FolderSizeMessageDto toDto(EventType eventType) {
		return new FolderSizeMessageDto(this.uuid, this.folderMetadataId, this.size, eventType);
	}
}
