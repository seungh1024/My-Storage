package com.woowacamp.storage.domain.folder.event;

import com.woowacamp.storage.domain.message.dto.FolderMoveMessageDto;
import com.woowacamp.storage.domain.message.util.EventType;

public record FolderMoveEvent(Long id, Long parentFolderId, Long lastFolderId) {

	public FolderMoveMessageDto message() {
		return new FolderMoveMessageDto(this.id, this.parentFolderId, this.lastFolderId, EventType.FOLDER_MOVE);
	}
}
