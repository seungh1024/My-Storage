package com.woowacamp.storage.domain.message.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import com.woowacamp.storage.domain.message.util.EventType;
import com.woowacamp.storage.domain.message.util.MessageStatus;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MessageInfo {

	@EmbeddedId
	private MessageInfoId id;

	@Column(name = "aggregate_type", nullable = false)
	private String aggregateType;

	@Column(name = "event_type", nullable = false)
	@Enumerated(EnumType.STRING)
	private EventType eventType;

	@Lob
	@Column(name = "payload", nullable = false, columnDefinition = "TEXT")
	private String payload;

	@Column(name = "status", nullable = false)
	@Enumerated(EnumType.STRING)
	private MessageStatus status;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "sent_at")
	private LocalDateTime sentAt;

	@Column(name = "retry_count", nullable = false)
	private int retryCount;

	public MessageInfo(UUID uuid, long folderMetadataId, String aggregateType, EventType eventType, String payload) {
		this.id = new MessageInfoId(uuid, folderMetadataId);
		this.aggregateType = aggregateType;
		this.eventType = eventType;
		this.payload = payload;
		this.status = MessageStatus.PENDING;
		this.createdAt = LocalDateTime.now();
		this.retryCount = 0;
	}

	public void markSent() {
		this.status = MessageStatus.SUCCESS;
		this.sentAt = LocalDateTime.now();
	}

	public void incrementRetryCount() {
		this.retryCount++;
	}
}
