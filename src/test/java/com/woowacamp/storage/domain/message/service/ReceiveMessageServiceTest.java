package com.woowacamp.storage.domain.message.service;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.woowacamp.storage.domain.folder.service.FolderMoveProcessor;
import com.woowacamp.storage.domain.message.dto.FolderMoveMessageDto;
import com.woowacamp.storage.domain.message.repository.MessageInfoJpaRepository;
import com.woowacamp.storage.domain.message.util.EventType;
import com.woowacamp.storage.domain.message.util.MessageStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ReceiveMessageServiceTest {

	@InjectMocks
	private ReceiveMessageService receiveMessageService;

	@Mock
	private FolderMoveProcessor folderMoveProcessor;

	@Mock
	private MessageInfoJpaRepository messageInfoJpaRepository;

	@Test
	@DisplayName("handleFolderMoveEvent: 완료된 메시지면 종료")
	void handleFolderMoveEvent_alreadyProcessed() {
		FolderMoveMessageDto message = new FolderMoveMessageDto(2L, 20L, EventType.FOLDER_MOVE);
		given(messageInfoJpaRepository.existsByIdAndStatusIn(eq(2L), anyList())).willReturn(true);

		receiveMessageService.handleFolderMoveEvent(message);

		then(folderMoveProcessor).shouldHaveNoInteractions();
		then(messageInfoJpaRepository).should(never()).updateMessageInfoStatus(anyLong(), any());
	}

	@Test
	@DisplayName("handleFolderMoveEvent: 정상 처리 시 상태 갱신 및 프로세스 수행")
	void handleFolderMoveEvent_success() {
		FolderMoveMessageDto message = new FolderMoveMessageDto(3L, 30L, EventType.FOLDER_MOVE);
		given(messageInfoJpaRepository.existsByIdAndStatusIn(eq(3L), anyList())).willReturn(false);

		receiveMessageService.handleFolderMoveEvent(message);

		then(folderMoveProcessor).should().processMove(30L);
		then(messageInfoJpaRepository).should().updateMessageInfoStatus(3L, MessageStatus.SUCCESS);
	}
}
