package com.woowacamp.storage.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.woowacamp.storage.container.ContainerBaseConfig;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.repository.FolderJobJpaRepository;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.message.repository.MessageInfoJpaRepository;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class IntegrationTestBase extends ContainerBaseConfig {

	@Autowired
	protected FolderMetadataJpaRepository folderMetadataJpaRepository;
	@Autowired
	protected FileMetadataJpaRepository fileMetadataJpaRepository;
	@Autowired
	protected MessageInfoJpaRepository messageInfoJpaRepository;
	@Autowired
	protected FolderJobJpaRepository folderJobJpaRepository;
	@Autowired
	protected RabbitTemplate rabbitTemplate;

	protected FolderTreeSetUp folderTreeSetUp;

	@BeforeEach
	void baseSetUp() {
		cleanup();
		folderTreeSetUp = new FolderTreeSetUp(
			folderMetadataJpaRepository,
			fileMetadataJpaRepository
		);
	}

	@AfterEach
	void baseTearDown() {
		cleanup();
	}

	protected void cleanup() {
		// ✅ RabbitMQ 큐를 먼저 비우기 (새 메시지 유입 차단)
		purgeAllQueues(rabbitTemplate);

		// ✅ DB 정리 (이제 안전하게 삭제)
		messageInfoJpaRepository.deleteAllInBatch();
		folderJobJpaRepository.deleteAllInBatch();
		fileMetadataJpaRepository.deleteAllInBatch();
		folderMetadataJpaRepository.deleteAllInBatch();
		
		// ✅ 다시 한번 큐 비우기 (전파된 메시지 제거)
		purgeAllQueues(rabbitTemplate);
	}
}
