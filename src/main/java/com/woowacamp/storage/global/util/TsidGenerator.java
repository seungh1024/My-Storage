package com.woowacamp.storage.global.util;

import org.springframework.stereotype.Service;

import com.github.f4b6a3.tsid.Tsid;
import com.github.f4b6a3.tsid.TsidFactory;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TsidGenerator {
	private final TsidFactory tsidFactory;

	public Long nextKey() {
		Tsid tsid = tsidFactory.create();
		return tsid.toLong();
	}

}
