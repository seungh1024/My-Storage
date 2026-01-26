package com.woowacamp.storage.domain.message.dto;

import com.woowacamp.storage.domain.message.util.EventType;

public interface OutboxMessage {
	Long id();
	EventType eventType();
}
