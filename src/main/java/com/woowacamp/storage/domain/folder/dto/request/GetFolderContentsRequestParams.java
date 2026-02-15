package com.woowacamp.storage.domain.folder.dto.request;

import java.time.LocalDateTime;

import org.springframework.data.domain.Sort;

import com.woowacamp.storage.domain.folder.dto.type.CursorType;
import com.woowacamp.storage.domain.folder.dto.type.FolderContentsSortField;
import com.woowacamp.storage.global.annotation.CheckField;
import com.woowacamp.storage.global.aop.type.FieldType;
import com.woowacamp.storage.global.constant.CommonConstant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record GetFolderContentsRequestParams(@NotNull @Positive @CheckField(value = FieldType.USER_ID) Long userId,
											 @NotNull @Positive Long cursorId, @NotNull CursorType cursorType,
											 @Positive @Max(MAX_SIZE) Integer limit, FolderContentsSortField sortBy,
											 Sort.Direction sortDirection, LocalDateTime localDateTime, Long size,
											 @CheckField(FieldType.CREATOR_ID) Long creatorId) {
	private static final int MAX_SIZE = 1000;
	private static final int DEFAULT_SIZE = 100;

	// 기본 생성자 정의
	public GetFolderContentsRequestParams {
		limit = resolveLimit(limit);
		sortBy = resolveSortBy(sortBy);
		sortDirection = resolveSortDirection(sortDirection);
		cursorId = resolveCursorId(cursorId, sortDirection);
		cursorType = resolveCursorType(cursorType);

		if (isFirstPage(localDateTime, size)) {
			localDateTime = resolveFirstPageLocalDateTime(localDateTime, sortBy, sortDirection);
			size = resolveFirstPageSize(size, sortBy, sortDirection);
		}
	}

	private static int resolveLimit(Integer limit) {
		return limit == null ? DEFAULT_SIZE : limit;
	}

	private static FolderContentsSortField resolveSortBy(FolderContentsSortField sortBy) {
		return sortBy == null ? FolderContentsSortField.CREATED_AT : sortBy;
	}

	private static Sort.Direction resolveSortDirection(Sort.Direction sortDirection) {
		return sortDirection == null ? Sort.Direction.DESC : sortDirection;
	}

	private static Long resolveCursorId(Long cursorId, Sort.Direction sortDirection) {
		if (cursorId != null) {
			return cursorId;
		}
		return sortDirection.isAscending() ? Long.MAX_VALUE : 1L;
	}

	private static CursorType resolveCursorType(CursorType cursorType) {
		return cursorType == null ? CursorType.FOLDER : cursorType;
	}

	private static LocalDateTime resolveFirstPageLocalDateTime(LocalDateTime localDateTime,
		FolderContentsSortField sortBy, Sort.Direction sortDirection) {
		return switch (sortBy) {
			case CREATED_AT -> sortDirection.isAscending()
				? CommonConstant.UNAVAILABLE_TIME
				: LocalDateTime.now().plusYears(1000);
			case DATA_SIZE -> localDateTime;
		};
	}

	private static Long resolveFirstPageSize(Long size, FolderContentsSortField sortBy, Sort.Direction sortDirection) {
		return switch (sortBy) {
			case CREATED_AT -> size;
			case DATA_SIZE -> sortDirection.isAscending() ? Long.MAX_VALUE : 0L;
		};
	}

	private static boolean isFirstPage(LocalDateTime localDateTime, Long size) {
		return localDateTime == null || size == null;
	}
}
