package com.woowacamp.storage.global.util;

import com.woowacamp.storage.domain.file.util.StringFormat;

public class CacheUtil {

	public static String generateKey(Long folderId) {
		return StringFormat.format("FolderList:{}", folderId);
	}
}
