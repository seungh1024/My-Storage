package com.woowacamp.storage.domain.folder.dto.type;

import java.util.Arrays;

import com.woowacamp.storage.global.error.ErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CursorType {

	FOLDER("Folder"), FILE("File");
	private final String value;

	public static CursorType fromValue(String value) {
		return Arrays.stream(CursorType.values())
			.filter(type -> type.getValue().equalsIgnoreCase(value))
			.findFirst()
			.orElseThrow(ErrorCode.WRONG_CURSOR_TYPE::baseException);
	}
}
