package com.woowacamp.storage.domain.message.service;

import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import com.woowacamp.storage.domain.message.dto.OutboxMessage;
import com.woowacamp.storage.domain.message.routing.RabbitRoute;
import com.woowacamp.storage.domain.message.routing.RabbitRoutesProperties;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SendMessageService {
	private final RabbitTemplate rabbitTemplate;
	private final RabbitRoutesProperties routes;

	public void send(OutboxMessage message) {
		RabbitRoute r = routes.route(message.eventType());
		CorrelationData cd = new CorrelationData(String.valueOf(message.id()));

		rabbitTemplate.convertAndSend(
			r.exchange(), r.routingKey(), message,
			msg -> {
				// returns 콜백에서 outboxId를 찾을 수 있게 correlationId도 함께 심어두자
				msg.getMessageProperties().setCorrelationId(String.valueOf(message.id()));
				msg.getMessageProperties().setMessageId(String.valueOf(message.id()));
				return msg;
			},
			cd
		);
	}
}
