package com.woowacamp.storage.domain.folder.dto;

import java.util.List;

public record SizeUpdateDto<T>(
	T value,
	List<Long> pkList
) {

}
