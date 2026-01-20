package com.woowacamp.storage.lock.util;

import org.springframework.stereotype.Component;

@Component("lockKeys") // SpEL에서 @lockKeys로 접근
public class LockKeys {
	public static final String FOLDER_JOB = "folder job:";
	public static final String FOLDER_NAME = "folder name:";

	public String folderJob(Long id) {
		return FOLDER_JOB.concat(id.toString());
	}
	public String folderJob(String id) {
		return FOLDER_JOB.concat(id);
	}

	public String folderName(Long parentId, String folderName) {
		return FOLDER_NAME.concat(parentId.toString()).concat("/").concat(folderName);
	}
}
