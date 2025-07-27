package com.woowacamp.storage.domain.folder.event;

import com.woowacamp.storage.domain.folder.entity.FolderMetadata;

import lombok.Getter;

@Getter
public class FolderSizeEvent {
	private final FolderMetadata folderMetadata;
	private final long size;

	public FolderSizeEvent(FolderMetadata folderMetadata, long size) {
		this.folderMetadata = folderMetadata;
		this.size = size;
	}


	public MessageInfo toEntity(String payload) {
		return new MessageInfo(
			"FolderMove",
			EventType.FOLDER_SIZE,
			payload
		);
	}
}
