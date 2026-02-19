package com.woowacamp.storage.domain.folder.dto.type;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class CursorTypeConverter implements Converter<String, CursorType> {
	@Override
	public CursorType convert(String source) {
		return CursorType.fromValue(source);
	}
}
