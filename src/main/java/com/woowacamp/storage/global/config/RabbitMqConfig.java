package com.woowacamp.storage.global.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.woowacamp.storage.domain.message.repository.MessageInfoJpaRepository;
import com.woowacamp.storage.domain.message.util.MessageStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class RabbitMqConfig {

	private final MessageInfoJpaRepository messageInfoJpaRepository;

	// common exchange (one)
	@Value("${spring.rabbitmq.folder.exchange}")
	private String folderExchangeName;

	@Value("${spring.rabbitmq.folder.ttl}")
	private int messageTtl;  // TTL 추가


	// ===== move =====
	@Value("${spring.rabbitmq.folder.move.queue}")
	private String folderMoveQueueName;
	@Value("${spring.rabbitmq.folder.move.key}")
	private String folderMoveBindingKey;

	@Value("${spring.rabbitmq.folder.move.dlx.exchange}")
	private String folderMoveDlxExchangeName;
	@Value("${spring.rabbitmq.folder.move.dlx.queue}")
	private String folderMoveDlqName;
	@Value("${spring.rabbitmq.folder.move.dlx.key}")
	private String folderMoveDlxRoutingKey;

	/**
	 * 메인 Exchange: Topic
	 */
	@Bean
	public TopicExchange folderExchange() {
		return new TopicExchange(folderExchangeName);
	}


	// =========================
	// Move Queue / Binding / DLX
	// =========================
	@Bean
	public Queue folderMoveQueue() {
		return QueueBuilder.durable(folderMoveQueueName)
			.withArgument("x-dead-letter-exchange", folderMoveDlxExchangeName)
			.withArgument("x-dead-letter-routing-key", folderMoveDlxRoutingKey)
			.withArgument("x-message-ttl", messageTtl)  // ✅ TTL 추가
			.build();
	}

	@Bean
	public Binding folderMoveBinding(TopicExchange folderExchange, Queue folderMoveQueue) {
		return BindingBuilder.bind(folderMoveQueue).to(folderExchange).with(folderMoveBindingKey);
	}

	@Bean
	public DirectExchange folderMoveDlxExchange() {
		return new DirectExchange(folderMoveDlxExchangeName);
	}

	@Bean
	public Queue folderMoveDlq() {
		return QueueBuilder.durable(folderMoveDlqName).build();
	}

	@Bean
	public Binding folderMoveDlqBinding(Queue folderMoveDlq, DirectExchange folderMoveDlxExchange) {
		return BindingBuilder.bind(folderMoveDlq).to(folderMoveDlxExchange).with(folderMoveDlxRoutingKey);
	}

	// =========================
	// Converter / Template
	// =========================
	@Bean
	public MessageConverter messageConverter() {
		return new Jackson2JsonMessageConverter();
	}

	@Bean
	public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
		RabbitTemplate template = new RabbitTemplate(connectionFactory);
		template.setMessageConverter(messageConverter);
		template.setMandatory(true);

		template.setConfirmCallback(this::handleConfirm);
		template.setReturnsCallback(this::handleReturn);


		return template;
	}

	private void handleConfirm(org.springframework.amqp.rabbit.connection.CorrelationData correlationData,
		boolean ack, String cause) {
		Long outboxId = extractOutboxId(correlationData);
		if (outboxId == null) {
			return;
		}
		if (ack) {
			markSent(outboxId);
		} else {
			markFailed(outboxId);
		}
	}

	private void handleReturn(org.springframework.amqp.core.ReturnedMessage returned) {
		Long outboxId = extractOutboxId(returned);
		if (outboxId == null) {
			return;
		}
		markFailed(outboxId);
	}

	private Long extractOutboxId(org.springframework.amqp.rabbit.connection.CorrelationData correlationData) {
		if (correlationData == null) {
			return null;
		}
		String correlationId = correlationData.getId();
		if (correlationId.isBlank()) {
			return null;
		}
		return Long.parseLong(correlationId);
	}

	private Long extractOutboxId(org.springframework.amqp.core.ReturnedMessage returned) {
		var props = returned.getMessage().getMessageProperties();
		String corrId = props.getCorrelationId(); // send()에서 주입
		if (corrId == null) {
			return null;
		}
		return Long.parseLong(corrId);
	}

	private void markSent(long outboxId) {
		int updated = messageInfoJpaRepository.markSent(outboxId, MessageStatus.SENT);
		if (updated == 0) {
			log.debug("markSent skipped in confirm callback. id={}", outboxId);
		}
	}

	private void markFailed(long outboxId) {
		int updated = messageInfoJpaRepository.markFailed(outboxId, MessageStatus.FAILED);
		if (updated == 0) {
			log.debug("markFailed skipped in return/confirm callback. id={}", outboxId);
		}
	}
}
