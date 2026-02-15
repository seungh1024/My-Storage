package com.woowacamp.storage.domain.folder.dto.type;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class FolderContentsSortFieldConverter implements Converter<String, FolderContentsSortField> {
	@Override
	public FolderContentsSortField convert(String source) {
		return FolderContentsSortField.fromValue(source);
	}
}
