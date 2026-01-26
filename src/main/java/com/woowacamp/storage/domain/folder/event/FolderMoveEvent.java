package com.woowacamp.storage.domain.folder.event;

import com.woowacamp.storage.domain.message.dto.FolderMoveMessageDto;
import com.woowacamp.storage.domain.message.entity.MessageInfo;
import com.woowacamp.storage.domain.message.util.EventType;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class FolderMoveEvent {
	private Long id;
	private final Long parentFolderId;


	public FolderMoveMessageDto message() {
		return new FolderMoveMessageDto(this.id, this.parentFolderId, EventType.FOLDER_MOVE);
	}

	public MessageInfo toEntity(String payload) {
		return new MessageInfo(
			"FolderMove",
			EventType.FOLDER_MOVE,
			payload
		);
	}

	public void setId(Long id) {
		this.id = id;
	}
}
