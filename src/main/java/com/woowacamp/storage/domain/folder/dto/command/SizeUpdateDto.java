package com.woowacamp.storage.domain.folder.dto.command;

import java.util.List;

public record SizeUpdateDto<T>(
	T value,
	List<Long> pkList
) {

}
