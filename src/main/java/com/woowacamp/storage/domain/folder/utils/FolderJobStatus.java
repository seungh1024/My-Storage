package com.woowacamp.storage.domain.folder.utils;

public enum FolderJobStatus {
	WAITING,    // 대기 중 (아직 시작 안함)
	RUNNING,    // 실행 중
	COMPLETED,  // 완료
	FAILED,     // 실패
	TERMINATED  // 강제 종료
}