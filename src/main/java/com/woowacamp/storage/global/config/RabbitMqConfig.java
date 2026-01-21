package com.woowacamp.storage.global.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

	// connection
	@Value("${spring.rabbitmq.addresses}")
	private String addresses;
	@Value("${spring.rabbitmq.username}")
	private String userName;
	@Value("${spring.rabbitmq.password}")
	private String password;
	@Value("${spring.rabbitmq.virtual-host}")
	private String virtualHost;

	// common exchange (one)
	@Value("${spring.rabbitmq.folder.exchange}")
	private String folderExchangeName;

	// ===== common =====
	@Value("${spring.rabbitmq.folder.ttl}")
	private Integer folderMessageTtl;

	// ===== size =====
	@Value("${spring.rabbitmq.folder.size.queue}")
	private String folderSizeQueueName;
	@Value("${spring.rabbitmq.folder.size.key}")
	private String folderSizeBindingKey;

	@Value("${spring.rabbitmq.folder.size.dlx.exchange}")
	private String folderSizeDlxExchangeName;
	@Value("${spring.rabbitmq.folder.size.dlx.queue}")
	private String folderSizeDlqName;
	@Value("${spring.rabbitmq.folder.size.dlx.key}")
	private String folderSizeDlxRoutingKey;

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

	@Bean
	public ConnectionFactory connectionFactory() {
		CachingConnectionFactory cf = new CachingConnectionFactory();
		cf.setAddresses(addresses);
		cf.setUsername(userName);
		cf.setPassword(password);
		cf.setVirtualHost(virtualHost);
		cf.setConnectionTimeout(3000);
		return cf;
	}

	/**
	 * 메인 Exchange: Topic (folder.size.* / folder.move.* 같은 패턴 바인딩용)
	 */
	@Bean
	public TopicExchange folderExchange() {
		return new TopicExchange(folderExchangeName);
	}

	// =========================
	// Size Queue / Binding / DLX
	// =========================
	@Bean
	public Queue folderSizeQueue() {
		return QueueBuilder.durable(folderSizeQueueName)
			// 필요하면 TTL도 yml로 빼서 주는 걸 추천. 우선 기존 유지 예시:
			.withArgument("x-message-ttl", folderMessageTtl)
			.withArgument("x-dead-letter-exchange", folderSizeDlxExchangeName)
			.withArgument("x-dead-letter-routing-key", folderSizeDlxRoutingKey)
			.build();
	}

	@Bean
	public Binding folderSizeBinding(TopicExchange folderExchange, Queue folderSizeQueue) {
		return BindingBuilder.bind(folderSizeQueue).to(folderExchange).with(folderSizeBindingKey);
	}

	@Bean
	public DirectExchange folderSizeDlxExchange() {
		return new DirectExchange(folderSizeDlxExchangeName);
	}

	@Bean
	public Queue folderSizeDlq() {
		return QueueBuilder.durable(folderSizeDlqName).build();
	}

	@Bean
	public Binding folderSizeDlqBinding(Queue folderSizeDlq, DirectExchange folderSizeDlxExchange) {
		return BindingBuilder.bind(folderSizeDlq).to(folderSizeDlxExchange).with(folderSizeDlxRoutingKey);
	}

	// =========================
	// Move Queue / Binding / DLX
	// =========================
	@Bean
	public Queue folderMoveQueue() {
		return QueueBuilder.durable(folderMoveQueueName)
			.withArgument("x-message-ttl", folderMessageTtl)
			.withArgument("x-dead-letter-exchange", folderMoveDlxExchangeName)
			.withArgument("x-dead-letter-routing-key", folderMoveDlxRoutingKey)
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
		return template;
	}
}
