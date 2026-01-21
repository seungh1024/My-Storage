package com.woowacamp.storage.domain.message.config;

import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitListenerContainerFactory {

	@Configuration
	public class RabbitListenerContainerFactoryConfig {

		// ===== size =====
		@Value("${spring.rabbitmq.folder.size.consumerSize}")
		private int sizeConsumerSize;
		@Value("${spring.rabbitmq.folder.size.maxConsumerSize}")
		private int sizeMaxConsumerSize;
		@Value("${spring.rabbitmq.folder.size.batchSize}")
		private int sizeBatchSize;
		@Value("${spring.rabbitmq.folder.size.receiveTimeout}")
		private long sizeReceiveTimeout;

		// ===== move =====
		@Value("${spring.rabbitmq.folder.move.consumerSize}")
		private int moveConsumerSize;
		@Value("${spring.rabbitmq.folder.move.maxConsumerSize}")
		private int moveMaxConsumerSize;
		@Value("${spring.rabbitmq.folder.move.batchSize}")
		private int moveBatchSize;
		@Value("${spring.rabbitmq.folder.move.receiveTimeout}")
		private long moveReceiveTimeout;

		@Bean(name = "folderSizeFactory")
		public SimpleRabbitListenerContainerFactory folderSizeFactory(
			ConnectionFactory connectionFactory,
			MessageConverter messageConverter
		) {
			return buildFactory(connectionFactory, messageConverter,
				sizeConsumerSize, sizeMaxConsumerSize, sizeBatchSize, sizeReceiveTimeout);
		}

		@Bean(name = "folderMoveFactory")
		public SimpleRabbitListenerContainerFactory folderMoveFactory(
			ConnectionFactory connectionFactory,
			MessageConverter messageConverter
		) {
			return buildFactory(connectionFactory, messageConverter,
				moveConsumerSize, moveMaxConsumerSize, moveBatchSize, moveReceiveTimeout);
		}

		private SimpleRabbitListenerContainerFactory buildFactory(
			ConnectionFactory connectionFactory,
			MessageConverter messageConverter,
			int consumerSize,
			int maxConsumerSize,
			int batchSize,
			long receiveTimeout
		) {
			SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
			factory.setConnectionFactory(connectionFactory);
			factory.setMessageConverter(messageConverter);

			factory.setConcurrentConsumers(consumerSize);
			factory.setMaxConcurrentConsumers(maxConsumerSize);

			// batch listener 활성화
			factory.setBatchListener(true);
			factory.setConsumerBatchEnabled(true);

			factory.setBatchSize(batchSize);
			factory.setReceiveTimeout(receiveTimeout);

			return factory;
		}
	}
}