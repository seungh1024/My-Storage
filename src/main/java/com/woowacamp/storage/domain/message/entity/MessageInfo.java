package com.woowacamp.storage.domain.message.entity;

import java.time.LocalDateTime;

import com.woowacamp.storage.domain.message.util.EventType;
import com.woowacamp.storage.domain.message.util.MessageStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "message_info",indexes = {@Index(name = "message_idx_status_id", columnList = "status, message_info_id")})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MessageInfo {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "message_info_id")
	private Long id;

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

	public MessageInfo(String aggregateType, EventType eventType, String payload) {
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
