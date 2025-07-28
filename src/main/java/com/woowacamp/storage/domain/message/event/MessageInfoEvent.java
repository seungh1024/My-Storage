package com.woowacamp.storage.domain.message.event;

import java.util.UUID;

import com.woowacamp.storage.domain.message.entity.MessageInfoId;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class MessageInfoEvent {
	private final UUID uuid;
	private final long folderMetadataId;

	public MessageInfoId toEntity() {
		return new MessageInfoId(this.uuid, this.folderMetadataId);
	}
}
