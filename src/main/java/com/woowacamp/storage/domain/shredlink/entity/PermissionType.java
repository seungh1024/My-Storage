package com.woowacamp.storage.domain.shredlink.entity;

import java.util.Arrays;

import com.woowacamp.storage.global.error.ErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PermissionType {
	WRITE("Write"), READ("Read");
	private final String value;

	public static PermissionType fromValue(String value) {
		return Arrays.stream(PermissionType.values())
			.filter(type -> type.getValue().equalsIgnoreCase(value))
			.findFirst()
			.orElseThrow(ErrorCode.WRONG_PERMISSION_TYPE::baseException);
	}
}
