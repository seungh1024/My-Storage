package com.woowacamp.storage.domain.folder.dto.type;

import java.util.Arrays;

import com.woowacamp.storage.global.error.ErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum FolderContentsSortField {
	CREATED_AT("createdAt"), DATA_SIZE("size");
	private final String value;

	public static FolderContentsSortField fromValue(String value) {
		return Arrays.stream(FolderContentsSortField.values())
			.filter(type -> type.getValue().equalsIgnoreCase(value))
			.findFirst()
			.orElseThrow(ErrorCode.WRONG_FOLDER_CONTENTS_SORT_FIELD::baseException);
	}
}
