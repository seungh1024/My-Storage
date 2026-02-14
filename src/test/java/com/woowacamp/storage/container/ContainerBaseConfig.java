package com.woowacamp.storage.container;

import java.time.Duration;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * 통합 테스트용 TestContainers 싱글톤 설정
 * 
 * 모든 통합 테스트 클래스가 동일한 컨테이너 인스턴스를 공유하여
 * 컨테이너 시작 오버헤드를 줄이고 테스트 안정성을 향상시킵니다.
 * 
 * 주의사항:
 * - @Testcontainers 어노테이션 제거 (수동 라이프사이클 관리)
 * - @Container 어노테이션 제거 (JUnit 자동 관리 비활성화)
 * - static 블록에서 컨테이너를 한 번만 시작
 */
public abstract class ContainerBaseConfig {
	// MySQL Container: 싱글톤 인스턴스
	private static final MySQLContainer<?> MY_SQL_CONTAINER;

	// Redis Container: 싱글톤 인스턴스
	private static final GenericContainer<?> REDIS_CONTAINER;

	// RabbitMQ Container: 싱글톤 인스턴스
	private static final GenericContainer<?> RABBIT_MQ_CONTAINER;

	static {
		// MySQL Container 초기화 및 시작
		MY_SQL_CONTAINER = new MySQLContainer<>("mysql:8.0.33")
			.withDatabaseName("test")
			.withUsername("test")
			.withPassword("test")
			.withReuse(true)
			.waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
		MY_SQL_CONTAINER.start();

		// Redis Container 초기화 및 시작
		REDIS_CONTAINER = new GenericContainer<>("redis:7")
			.withExposedPorts(6379)
			.withReuse(true)
			.waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
		REDIS_CONTAINER.start();

		// RabbitMQ Container 초기화 및 시작
		// RabbitMQ는 내부 초기화 시간이 필요하므로 충분한 대기 시간 제공
		RABBIT_MQ_CONTAINER = new GenericContainer<>("rabbitmq:3.12-management")
			.withExposedPorts(5672, 15672)
			.withReuse(true)
			.waitingFor(
				Wait.forLogMessage(".*Server startup complete.*", 1)
					.withStartupTimeout(Duration.ofMinutes(3))
			);

		try {
			RABBIT_MQ_CONTAINER.start();
			// RabbitMQ 완전 준비 대기 (포트 리스닝 확인)
			Thread.sleep(3000);
		} catch (Exception e) {
			throw new RuntimeException("Failed to start RabbitMQ container", e);
		}

		// JVM 종료 시 컨테이너 정리
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (RABBIT_MQ_CONTAINER != null) {
				RABBIT_MQ_CONTAINER.stop();
			}
			if (REDIS_CONTAINER != null) {
				REDIS_CONTAINER.stop();
			}
			if (MY_SQL_CONTAINER != null) {
				MY_SQL_CONTAINER.stop();
			}
		}));
	}

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		// MySQL 설정
		registry.add("spring.datasource.url", MY_SQL_CONTAINER::getJdbcUrl);
		registry.add("spring.datasource.username", MY_SQL_CONTAINER::getUsername);
		registry.add("spring.datasource.password", MY_SQL_CONTAINER::getPassword);
		registry.add("spring.datasource.driver-class-name", MY_SQL_CONTAINER::getDriverClassName);
		registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQL8Dialect");

		// Redis 설정 (기본 Redis 클라이언트용)
		registry.add("spring.redis.host", REDIS_CONTAINER::getHost);
		registry.add("spring.redis.port", REDIS_CONTAINER::getFirstMappedPort);

		// Redisson 설정 (분산 락용)
		registry.add("spring.redisson.address", 
			() -> "redis://" + REDIS_CONTAINER.getHost() + ":" + REDIS_CONTAINER.getFirstMappedPort());

		// RabbitMQ 설정
		registry.add("spring.rabbitmq.addresses",
			() -> RABBIT_MQ_CONTAINER.getHost() + ":" + RABBIT_MQ_CONTAINER.getMappedPort(5672));
		registry.add("spring.rabbitmq.username", () -> "guest");
		registry.add("spring.rabbitmq.password", () -> "guest");
	}

	/**
	 * RabbitMQ 큐를 비우는 헬퍼 메서드
	 * 테스트 간 메시지 격리를 위해 @BeforeEach에서 호출
	 */
	protected void purgeAllQueues(RabbitTemplate rabbitTemplate) {
		try {
			rabbitTemplate.execute(channel -> {
				// folder.size.queue 비우기
				try {
					channel.queuePurge("folder.size.queue");
				} catch (Exception e) {
					// 큐가 없으면 무시
				}

				// folder.move.queue 비우기
				try {
					channel.queuePurge("folder.move.queue");
				} catch (Exception e) {
					// 큐가 없으면 무시
				}

				// DLQ도 비우기
				try {
					channel.queuePurge("folder.dlx.size.queue");
				} catch (Exception e) {
					// 큐가 없으면 무시
				}

				try {
					channel.queuePurge("folder.dlx.move.queue");
				} catch (Exception e) {
					// 큐가 없으면 무시
				}

				return null;
			});
		} catch (Exception e) {
			// 전체 작업 실패 시 무시
		}
	}

}
