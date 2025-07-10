package com.woowacamp.storage.domain.file.util;

import org.slf4j.helpers.MessageFormatter;

public class StringFormat {
	public static String format(String format, Object... objects) {
		return MessageFormatter.arrayFormat(format, objects).getMessage();
	}
}
