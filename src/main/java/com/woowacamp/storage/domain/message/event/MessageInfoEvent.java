package com.woowacamp.storage.domain.message.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class MessageInfoEvent {
	private final Long id;
}
