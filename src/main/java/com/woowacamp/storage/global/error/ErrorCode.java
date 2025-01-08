package com.woowacamp.storage.global.error;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode {

	// 400
	USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
	FOLDER_NOT_FOUND(HttpStatus.NOT_FOUND, "폴더를 찾을 수 없습니다."),
	FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "파일을 찾을 수 없습니다."),
	ACCESS_DENIED(HttpStatus.FORBIDDEN, "권한이 없습니다"),
	WRONG_CURSOR_TYPE(HttpStatus.BAD_REQUEST, "잘못된 커서타입입니다."),
	WRONG_FOLDER_CONTENTS_SORT_FIELD(HttpStatus.BAD_REQUEST, "잘못된 폴더 컨텐츠 정렬 기준입니다."),
	WRONG_PAGE_SIZE(HttpStatus.BAD_REQUEST, "잘못된 페이지 사이즈입니다."),
	INVALID_FILE_NAME(HttpStatus.BAD_REQUEST, "파일 이름이 부적절합니다."),
	EXCEED_MAX_FILE_SIZE(HttpStatus.BAD_REQUEST, "요청 가능한 파일 크기를 초과했습니다."),
	EXCEED_MAX_STORAGE_SIZE(HttpStatus.BAD_REQUEST, "최대 저장 공간 크기를 초과했습니다."),
	INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "올바르지 않은 입력입니다."),
	FILE_METADATA_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 파일을 찾을 수 없습니다."),
	EXCEED_MAX_FOLDER_DEPTH(HttpStatus.BAD_REQUEST, "최대 폴더 깊이를 초과했습니다."),
	INVALID_FILE_SIZE(HttpStatus.BAD_REQUEST, "요청 파일 사이즈와 실제 파일 사이즈가 일치하지 않습니다."),
	FOLDER_MOVE_NOT_AVAILABLE(HttpStatus.BAD_REQUEST, "올바르지 않은 폴더 이동입니다."),
	CANNOT_MOVE_FOLDER_WHEN_UPLOADING(HttpStatus.BAD_REQUEST, "파일 업로드 중에 폴더 이동할 수 없습니다."),
	FILE_NAME_DUPLICATE(HttpStatus.CONFLICT, "파일 또는 폴더 이름이 중복되었습니다."),
	API_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로의 API를 찾을 수 없습니다."),
	INVALID_DELETE_REQUEST(HttpStatus.BAD_REQUEST, "최상위 폴더는 제거할 수 없습니다."),
	INVALID_ARGUMENT_ERROR(HttpStatus.BAD_REQUEST, "올바르지 않은 파라미터입니다."),
	MISSING_REQUIRED_PARAMETER(HttpStatus.BAD_REQUEST, "필수 파라미터가 누락되었습니다."),
	UNABLE_TO_CREATE_FILE(HttpStatus.BAD_REQUEST, "해당 위치에 파일을 생성할 수 없습니다."),
	CANNOT_DELETE_FILE_WHEN_UPLOADING(HttpStatus.BAD_REQUEST, "파일 업로드 중에 폴더 삭제를 할 수 없습니다."),
	INVALID_MULTIPART_FORM_DATA(HttpStatus.BAD_REQUEST, "올바른 데이터 형식으로 요청을 보내주세요."),
	DUPLICATED_SHARED_LINK(HttpStatus.CONFLICT, "공유 링크 생성에 실패했습니다. 잠시 후에 다시 시도해 주세요."),
	WRONG_PERMISSION_TYPE(HttpStatus.BAD_REQUEST, "잘못된 권한 타입입니다."),
	SHARED_LINK_NOT_FOUND(HttpStatus.NOT_FOUND, "공유 링크를 찾을 수 없습니다."),
	EXPIRED_SHARED_LINK(HttpStatus.BAD_REQUEST, "만료된 공유 링크입니다."),
	WRONG_OBJECT_KEY(HttpStatus.BAD_REQUEST, "OBJECT KEY가 일치하지 않습니다. 요청 데이터를 다시 확인해 주세요."),
	// 500,
	FILE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "파일 업로드에 실패했습니다."),
	FILE_DELETE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "파일 삭제에 실패했습니다."),
	PERMISSION_CHECK_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "권한 확인 중 예외가 발생했습니다."),
	TOO_MUCH_REQUEST(HttpStatus.INTERNAL_SERVER_ERROR, "요청이 많습니다. 잠시 후에 다시 시도해주세요.");
	private final HttpStatus status;
	private final String message;

	public CustomException baseException() {
		return new CustomException(status, message);
	}

	public CustomException baseException(String debugMessage, Object... args) {
		return new CustomException(status, message, String.format(debugMessage, args));
	}
}
