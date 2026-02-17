package com.woowacamp.storage.domain.message.config;

import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitListenerContainerFactory {

	// ===== move =====
	@Value("${spring.rabbitmq.folder.move.consumerSize}")
	private int moveConsumerSize;

	@Value("${spring.rabbitmq.folder.move.maxConsumerSize}")
	private int moveMaxConsumerSize;

	@Value("${spring.rabbitmq.folder.move.prefetch}")
	private int movePrefetch;

	// 재시도 설정은 공통으로 통일
	@Value("${spring.rabbitmq.folder.retryCnt}")
	private int maxAttempts;

	@Bean(name = "folderMoveFactory")
	public SimpleRabbitListenerContainerFactory folderMoveFactory(
		ConnectionFactory connectionFactory,
		MessageConverter messageConverter
	) {
		return buildFactory(connectionFactory, messageConverter,
			moveConsumerSize, moveMaxConsumerSize, movePrefetch);
	}

	private SimpleRabbitListenerContainerFactory buildFactory(
		ConnectionFactory connectionFactory,
		MessageConverter messageConverter,
		int consumerSize,
		int maxConsumerSize,
		int prefetchCount
	) {
		SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
		factory.setConnectionFactory(connectionFactory);
		factory.setMessageConverter(messageConverter);

		factory.setConcurrentConsumers(consumerSize);
		factory.setMaxConcurrentConsumers(maxConsumerSize);

		factory.setBatchListener(false);
		factory.setConsumerBatchEnabled(false);

		// “브로커 -> 컨슈머” in-flight 개수 제어
		factory.setPrefetchCount(prefetchCount);

		// 리스너 레벨 재시도 + 최종 실패 시 reject(requeue=false) -> DLQ로
		factory.setAdviceChain(
			RetryInterceptorBuilder.stateless()
				.maxAttempts(maxAttempts)
				.backOffOptions(200, 2.0, 2000) // 200ms, x2, max 2s
				.recoverer(new RejectAndDontRequeueRecoverer())
				.build()
		);

		return factory;
	}
}
