package com.woowacamp.storage.domain.folder.event;

import com.woowacamp.storage.domain.message.dto.FolderSizeMessageDto;
import com.woowacamp.storage.domain.message.entity.MessageInfo;
import com.woowacamp.storage.domain.message.util.EventType;

import lombok.Getter;

@Getter
public class FolderSizeEvent {
	private Long id;
	private final Long folderMetadataId;
	private final long size;

	public FolderSizeEvent(Long folderMetadataId, long size) {
		this.folderMetadataId = folderMetadataId;
		this.size = size;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public MessageInfo toEntity(String payload) {
		return new MessageInfo(
			"FolderMove",
			EventType.FOLDER_SIZE,
			payload
		);
	}

	public FolderSizeMessageDto message() {
		return new FolderSizeMessageDto(this.id, this.folderMetadataId, this.size, EventType.FOLDER_SIZE);
	}
}
